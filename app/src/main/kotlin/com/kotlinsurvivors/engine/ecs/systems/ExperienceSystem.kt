package com.kotlinsurvivors.engine.ecs.systems

import android.util.Log
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class ExperienceSystem {

    private fun cansOnTheFloor(world: World): Int =
        world.pickups.values.count { it.type == PickupType.HEALTH_SMALL || it.type == PickupType.HEALTH_LARGE }

    companion object {
        const val MAX_LEVELS_PER_FRAME = 1

        /** Experience needed to go from level 1 to 2: about five kills of the weakest enemy. */
        const val XP_FIRST_LEVEL = 25.0

        /** Each level asks for this many times the experience of the one before (+40%). */
        const val XP_GROWTH_PER_LEVEL = 1.40

        /** Seconds the chest takes to open once the player touches it. */
        const val CHEST_OPEN_SECONDS = 0.25f

        /** Chance that any dead enemy leaves an energy can (heals the player). */
        const val HEAL_DROP_CHANCE = 0.30f

        /** No more cans fall while this many wait on the floor, so the map does not fill with them. */
        const val MAX_CANS_ON_THE_FLOOR = 3

        /** Chance that a dead boss leaves a chest. */
        const val BOSS_CHEST_CHANCE = 0.5f

        /** Chance that a dead mini-boss leaves a chest. */
        const val MINI_BOSS_CHEST_CHANCE = 0.3f
        private const val TAG = "KS_XP"
    }

    private val toDestroy = ArrayList<Int>(128)

    // Reused every frame by reapUnreportedKills(); avoids allocating a set per tick.
    private val reportedKills = HashSet<Int>(64)

    fun processEvents(
        world  : World,
        events : MutableList<GameEvent>,
        dt     : Float = 0.016f
    ): List<GameEvent> {
        val outputEvents = mutableListOf<GameEvent>()

        reapUnreportedKills(world, events)

        for (event in events) {
            when (event) {
                is GameEvent.EnemyKilled     -> handleEnemyKilled(world, event, outputEvents)
                is GameEvent.PickupCollected -> handlePickup(world, event, outputEvents)
                else                         -> outputEvents.add(event)
            }
        }

        try { tickPickupLifetimes(world, dt, outputEvents) }
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

    /**
     * Single exit point for enemy deaths.
     *
     * Only projectile hits (CollisionSystem.applyDamage) report an EnemyKilled
     * event. Every other damage source (whip, lightning, aura, orbitals, holy
     * water, bomb) just marks the enemy as dead. Without this step those enemies
     * would stay in the world forever: no XP drop, no kill count, still counted
     * against the spawn cap.
     *
     * Any enemy that is dead, not already scheduled for destruction and not
     * already reported this frame gets its EnemyKilled event here, so
     * handleEnemyKilled() treats every death the same way.
     *
     * The engine calls this before it counts the frame's kills for the combo and the death sounds, so
     * a whip or aura kill counts like any other; [processEvents] calls it again, which adds nothing
     * because those deaths are already reported by then.
     */
    fun reapUnreportedKills(world: World, events: MutableList<GameEvent>) {
        reportedKills.clear()
        for (event in events) {
            if (event is GameEvent.EnemyKilled) reportedKills.add(event.entityId)
        }

        // Only reads the enemy map and appends to the event list, so iterating
        // the live map is safe here (nothing is created or destroyed meanwhile).
        for ((id, enemy) in world.enemies) {
            val health = world.healths[id] ?: continue
            if (!health.isDead) continue
            if (id in world.destroyedTags || id in reportedKills) continue
            events.add(GameEvent.EnemyKilled(id, enemy.type, enemy.experienceValue, enemy.coinDropChance, enemy.isMiniBoss))
        }
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

            val coinChance = event.coinChance * (world.players[world.getPlayerEntity()]?.coinDropMultiplier ?: 1f)
            if (Random.nextFloat() < coinChance) {
                val coinType = if (Random.nextFloat() < 0.15f) PickupType.COIN_BAG else PickupType.COIN
                EntityFactory.createPickup(world, coinType, t.x, t.y)
            }

            if (Random.nextFloat() < HEAL_DROP_CHANCE && cansOnTheFloor(world) < MAX_CANS_ON_THE_FLOOR) {
                EntityFactory.createPickup(world, PickupType.HEALTH_SMALL, t.x, t.y)
            }

            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.BLOOD, 5)

            val chestChance = when {
                event.type.isBoss -> BOSS_CHEST_CHANCE
                event.miniBoss    -> MINI_BOSS_CHEST_CHANCE
                else              -> 0f
            }
            if (chestChance > 0f && Random.nextFloat() < chestChance) {
                EntityFactory.createPickup(world, PickupType.CHEST, t.x, t.y)
                output.add(GameEvent.ChestDropped)
                Log.d(TAG, "${event.type} dropped a chest (mini-boss: ${event.miniBoss})")
            }
        } else {
            Log.w(TAG, "handleEnemyKilled: entity $eid has no transform — already removed?")
        }

        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        if (player != null) {
            player.killCount++
            if (event.type.isBoss) player.bossKills++
        }
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
                player.experience += (event.value * player.xpMultiplier * player.comboXpMultiplier).roundToInt()

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
                    val pk = world.pickups[pkId]; if (pk != null && !pk.type.staysPut) { pk.magnetized = true; count++ }
                }
                Log.d(TAG, "Magnet activated: magnetized $count pickups")
            }
            PickupType.CHEST -> {
                // The weapon it gives is chosen by the engine, which owns the weapon list.
                val at = world.transforms[event.entityId] ?: pt
                if (at != null) {
                    EntityFactory.createParticleBurst(world, at.x, at.y, ParticleType.LEVEL_UP, 14)
                    output.add(GameEvent.ChestOpened(at.x, at.y))
                }
            }
            PickupType.KEY -> Unit                  // the engine takes it from here: it opens the street door of the station
            PickupType.CELL_KEY -> Unit             // the engine takes it from here: it lets the police dog out
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
        val result = (XP_FIRST_LEVEL * XP_GROWTH_PER_LEVEL.pow(cappedLevel - 1.0)).toInt().coerceIn(1, 500_000)
        if (level > 50) {
            Log.d(TAG, "xpForLevel: level $level capped at 50 → xpNeeded=$result")
        }
        return result
    }

    private fun tickPickupLifetimes(world: World, dt: Float, output: MutableList<GameEvent>) {
        toDestroy.clear()
        for (id in world.getPickupSnapshot()) {
            val pickup = world.pickups[id] ?: continue
            if (id in world.destroyedTags) continue
            if (pickup.openTimer >= 0f) {
                // A chest that is opening: it does not expire, and it pays out when the animation ends.
                pickup.openTimer += dt
                if (pickup.openTimer >= CHEST_OPEN_SECONDS) {
                    handlePickup(world, GameEvent.PickupCollected(id, pickup.type, pickup.value), output)
                    toDestroy.add(id)
                }
                continue
            }
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
