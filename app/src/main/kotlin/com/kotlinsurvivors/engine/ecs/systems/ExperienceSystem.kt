package com.kotlinsurvivors.engine.ecs.systems

import android.util.Log
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.pow
import kotlin.random.Random

class ExperienceSystem {

    companion object {
        const val MAX_LEVELS_PER_FRAME = 1
        private const val TAG = "KS_XP"
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

        try { tickPickupLifetimes(world, dt) }
        catch (e: Exception) {
            Log.e(TAG, "CRASH in tickPickupLifetimes: pickups=${world.pickups.size}", e); throw e
        }

        try { tickDamageNumbers(world, dt) }
        catch (e: Exception) {
            Log.e(TAG, "CRASH in tickDamageNumbers: damageNums=${world.damageNumbers.size}", e); throw e
        }

        try { tickParticles(world, dt) }
        catch (e: Exception) {
            Log.e(TAG, "CRASH in tickParticles: particles=${world.particles.size}", e); throw e
        }

        return outputEvents
    }

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
        } else {
            Log.w(TAG, "handleEnemyKilled: entity $eid has no transform — already removed?")
        }

        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        if (player != null) player.killCount++
        world.destroyEntity(eid)
        output.add(event)
    }

    private fun handlePickup(
        world  : World,
        event  : GameEvent.PickupCollected,
        output : MutableList<GameEvent>
    ) {
        val pid    = world.getPlayerEntity()
        if (pid == -1) { Log.w(TAG, "handlePickup: no player entity"); return }
        val player = world.players[pid] ?: run { Log.w(TAG, "handlePickup: no PlayerComponent for pid=$pid"); return }
        val health = world.healths[pid] ?: run { Log.w(TAG, "handlePickup: no HealthComponent for pid=$pid"); return }
        val pt     = world.transforms[pid]

        when (event.type) {
            PickupType.EXPERIENCE_SMALL,
            PickupType.EXPERIENCE_MEDIUM,
            PickupType.EXPERIENCE_LARGE -> {
                val xpBefore = player.experience
                val levelBefore = player.level
                player.experience += event.value

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
                    Log.d(TAG, "Level up: ${levelBefore} → ${player.level} | xpNext=${player.experienceToNextLevel}")
                    if (pt != null) {
                        EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.LEVEL_UP, 10)
                    }
                }

                if (levelsThisFrame == 0 && player.level > levelBefore) {
                    Log.w(TAG, "Level mismatch after XP pickup! levelBefore=$levelBefore current=${player.level}")
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
                var count = 0
                for (pkId in world.getPickupSnapshot()) {
                    world.pickups[pkId]?.magnetized = true; count++
                }
                Log.d(TAG, "Magnet activated: magnetized $count pickups")
            }
            PickupType.BOMB -> {
                if (pt != null) {
                    var count = 0
                    for (eid in world.getEnemySnapshot()) {
                        val eh = world.healths[eid] ?: continue
                        eh.current = (eh.current - 50).coerceAtLeast(0)
                        world.renders[eid]?.flashTimer = 0.2f
                        if (eh.current <= 0 && !eh.isDead) eh.isDead = true
                        count++
                    }
                    EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.EXPLOSION, 8)
                    Log.d(TAG, "Bomb detonated: hit $count enemies")
                }
            }
        }
        output.add(event)
    }

    private fun xpForLevel(level: Int): Int {
        val cappedLevel = level.coerceAtMost(50)
        val result = (10.0 * (1.18.pow(cappedLevel - 1.0))).toInt().coerceIn(1, 500_000)
        if (level > 50) {
            Log.d(TAG, "xpForLevel: level $level capped at 50 → xpNeeded=$result")
        }
        return result
    }

    private fun tickPickupLifetimes(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getPickupSnapshot()) {
            val pickup = world.pickups[id] ?: continue
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
