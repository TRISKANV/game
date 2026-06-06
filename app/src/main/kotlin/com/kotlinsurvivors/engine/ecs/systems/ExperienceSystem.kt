package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.pow
import kotlin.random.Random

/**
 * ExperienceSystem
 *
 * Memory fixes:
 *  1. All map iterations use ID snapshots — zero Pair allocation per frame.
 *  2. tickPickupLifetimes / tickParticles / tickDamageNumbers each collect
 *     IDs to destroy into a pre-cleared reusable list, not a new mutableListOf().
 */
class ExperienceSystem {

    // Pre-allocated destroy list — reused every tick, avoids mutableListOf() allocation
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

        val pid = world.getPlayerEntity()
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
                while (player.experience >= player.experienceToNextLevel) {
                    player.experience            -= player.experienceToNextLevel
                    player.level++
                    player.experienceToNextLevel  = xpForLevel(player.level)
                    output.add(GameEvent.LevelUp(player.level))
                    if (pt != null) EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.LEVEL_UP, 10)
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

    private fun xpForLevel(level: Int): Int =
        (10 * (1.18.pow(level - 1.0))).toInt().coerceAtLeast(1)

    // ── Lifetime tickers — ID snapshot, reused destroy list ───────────────

    private fun tickPickupLifetimes(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getPickupSnapshot()) {
            val pickup = world.pickups[id] ?: continue
            pickup.lifetime -= dt
            if (pickup.lifetime <= 0f) toDestroy.add(id)
        }
        for (id in toDestroy) world.destroyEntity(id)
    }

    private fun tickDamageNumbers(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getDamageNumberSnapshot()) {
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
            val p = world.particles[id]   ?: continue
            val t = world.transforms[id]  ?: continue
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
