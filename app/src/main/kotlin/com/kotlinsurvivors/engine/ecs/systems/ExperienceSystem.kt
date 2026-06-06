package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.pow
import kotlin.random.Random

/**
 * ExperienceSystem
 *
 * Fixes applied:
 *  1. Level-up loop is bounded to MAX_LEVELS_PER_FRAME (prevents stacking
 *     many LevelUp events in one frame when collecting mass XP).
 *     Extra XP beyond the frame cap is carried over correctly.
 *  2. XP formula capped at level 50 to prevent Double overflow.
 *  3. toDestroy list reused across tickers (single allocation).
 */
class ExperienceSystem {

    companion object {
        // Max level-ups to process per frame. Prevents emitting dozens of
        // LevelUp events in one tick when player collects a magnet + many orbs.
        // Each LevelUp pauses the game for player choice anyway.
        const val MAX_LEVELS_PER_FRAME = 1
    }

    private val toDestroy = ArrayList<Int>(128)

    fun processEvents(
        world  : World,
        events : MutableList<GameEvent>,
        dt     : Float = 0.016f
    ): List<GameEvent> {
        val outputEvents = mutableListOf<GameEvent>()

        for (event in events) {
            when (event) {
                is GameEvent.EnemyKilled     -> handleEnemyKilled(world, event, outputEvents)
                is GameEvent.PickupCollected -> handlePickup(world, event, outputEvents)
                else                         -> outputEvents.add(event)
            }
        }

        tickPickupLifetimes(world, dt)
        tickDamageNumbers(world, dt)
        tickParticles(world, dt)

        return outputEvents
    }

    // ── Enemy killed ───────────────────────────────────────────────────────

    private fun handleEnemyKilled(
        world  : World,
        event  : GameEvent.EnemyKilled,
        output : MutableList<GameEvent>
    ) {
        val eid = event.entityId
        val t   = world.transforms[eid]

        if (t != null) {
            val xpType = when {
                event.xp >= 30 -> PickupType.EXPERIENCE_LARGE
                event.xp >= 10 -> PickupType.EXPERIENCE_MEDIUM
                else           -> PickupType.EXPERIENCE_SMALL
            }
            EntityFactory.createPickup(world, xpType, t.x, t.y)

            if (Random.nextFloat() < event.coinChance) {
                val coinType = if (Random.nextFloat() < 0.15f) PickupType.COIN_BAG else PickupType.COIN
                EntityFactory.createPickup(world, coinType, t.x, t.y)
            }

            if (Random.nextFloat() < 0.03f) {
                EntityFactory.createPickup(world, PickupType.HEALTH_SMALL, t.x, t.y)
            }

            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.BLOOD, 5)
        }

        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        if (player != null) player.killCount++
        world.destroyEntity(eid)
        output.add(event)
    }

    // ── Pickup collected ───────────────────────────────────────────────────

    private fun handlePickup(
        world  : World,
        event  : GameEvent.PickupCollected,
        output : MutableList<GameEvent>
    ) {
        val pid    = world.getPlayerEntity()
        if (pid == -1) return
        val player = world.players[pid] ?: return
        val health = world.healths[pid] ?: return
        val pt     = world.transforms[pid]

        when (event.type) {
            PickupType.EXPERIENCE_SMALL,
            PickupType.EXPERIENCE_MEDIUM,
            PickupType.EXPERIENCE_LARGE -> {
                player.experience += event.value

                // Bounded level-up loop: emit at most MAX_LEVELS_PER_FRAME LevelUp
                // events. Surplus XP is retained in player.experience and will
                // trigger another LevelUp next time a pickup is collected.
                var levelsThisFrame = 0
                while (
                    player.experience >= player.experienceToNextLevel &&
                    levelsThisFrame < MAX_LEVELS_PER_FRAME
                ) {
                    player.experience            -= player.experienceToNextLevel
                    player.level++
                    player.experienceToNextLevel  = xpForLevel(player.level)
                    output.add(GameEvent.LevelUp(player.level))
                    levelsThisFrame++
                    if (pt != null) {
                        EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.LEVEL_UP, 10)
                    }
                }
            }

            PickupType.COIN,
            PickupType.COIN_BAG -> player.coins += event.value

            PickupType.HEALTH_SMALL -> {
                health.current = (health.current + event.value).coerceAtMost(health.max)
                if (pt != null) EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.HEAL, 4)
            }

            PickupType.HEALTH_LARGE -> {
                health.current = (health.current + event.value).coerceAtMost(health.max)
                if (pt != null) EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.HEAL, 8)
            }

            PickupType.MAGNET -> {
                for (pkId in world.getPickupSnapshot()) {
                    world.pickups[pkId]?.magnetized = true
                }
            }

            PickupType.BOMB -> {
                if (pt != null) {
                    for (eid in world.getEnemySnapshot()) {
                        val eh = world.healths[eid] ?: continue
                        eh.current = (eh.current - 50).coerceAtLeast(0)
                        world.renders[eid]?.flashTimer = 0.2f
                        if (eh.current <= 0 && !eh.isDead) eh.isDead = true
                    }
                    EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.EXPLOSION, 8)
                }
            }
        }
        output.add(event)
    }

    // ── XP formula — capped at level 50 to prevent Int/Double overflow ─────
    private fun xpForLevel(level: Int): Int {
        val cappedLevel = level.coerceAtMost(50)
        return (10.0 * (1.18.pow(cappedLevel - 1.0))).toInt().coerceIn(1, 500_000)
    }

    // ── Lifetime tickers ───────────────────────────────────────────────────

    private fun tickPickupLifetimes(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getPickupSnapshot()) {
            val pickup = world.pickups[id] ?: continue
            // Skip pickups already queued for destruction (collected this frame)
            if (id in world.destroyedTags) continue
            pickup.lifetime -= dt
            if (pickup.lifetime <= 0f) toDestroy.add(id)
        }
        for (id in toDestroy) world.destroyEntity(id)
    }

    private fun tickDamageNumbers(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getDamageNumberSnapshot()) {
            if (id in world.destroyedTags) continue
            val dn = world.damageNumbers[id] ?: continue
            val t  = world.transforms[id]    ?: continue
            dn.lifetime -= dt
            t.y         += dn.vy * dt
            if (dn.lifetime <= 0f) toDestroy.add(id)
        }
        for (id in toDestroy) world.destroyEntity(id)
    }

    private fun tickParticles(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getParticleSnapshot()) {
            if (id in world.destroyedTags) continue
            val p = world.particles[id]  ?: continue
            val t = world.transforms[id] ?: continue
            p.lifetime -= dt
            t.x        += p.vx * dt
            t.y        += p.vy * dt
            p.vx       *= p.drag
            p.vy       *= p.drag
            if (p.lifetime <= 0f) toDestroy.add(id)
        }
        for (id in toDestroy) world.destroyEntity(id)
    }
}
