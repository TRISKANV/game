package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.spatial.SpatialGrid
import kotlin.math.sqrt

/**
 * CollisionSystem
 *
 * Uses a SpatialGrid for broad-phase and circle-circle tests for narrow-phase.
 * Handles:
 *  - Projectile → Enemy/Boss: deal damage, spawn damage numbers, particles
 *  - Player → Enemy:          deal damage to player (with iframes)
 *  - Player → Pickup:         collect experience, coins, health
 *  - Enemy → Enemy:           separation push (prevents stacking)
 */
class CollisionSystem {

    private val grid        = SpatialGrid(cellSize = 128f)
    private val queryResult = ArrayList<Int>(64)

    fun update(world: World, dt: Float, events: MutableList<GameEvent>) {
        buildGrid(world)
        checkProjectileVsEnemies(world, events)
        checkPlayerVsEnemies(world, dt, events)
        checkPlayerVsPickups(world, events)
        checkEnemySeparation(world)
    }

    // ── Grid construction ──────────────────────────────────────────────────

    private fun buildGrid(world: World) {
        grid.clear()
        for ((id, t) in world.transforms) {
            val c = world.colliders[id] ?: continue
            grid.insert(id, t.x, t.y, c.radius)
        }
    }

    // ── Projectile vs Enemies ──────────────────────────────────────────────

    private fun checkProjectileVsEnemies(world: World, events: MutableList<GameEvent>) {
        val projIds = world.getProjectileEntities().toList() // snapshot
        for (pid in projIds) {
            val proj   = world.projectiles[pid] ?: continue
            val pt     = world.transforms[pid]  ?: continue
            val pc     = world.colliders[pid]   ?: continue

            if (proj.piercingLeft <= 0) {
                world.destroyEntity(pid)
                continue
            }

            queryResult.clear()
            grid.query(pt.x, pt.y, pc.radius + 64f, queryResult)

            for (eid in queryResult) {
                if (eid == pid) continue
                if (eid in proj.hitEntities) continue

                val enemyComp = world.enemies[eid] ?: world.bosses[eid] ?: continue
                val et        = world.transforms[eid] ?: continue
                val ec        = world.colliders[eid]  ?: continue
                val eHealth   = world.healths[eid]    ?: continue

                if (!world.isAlive(eid)) continue
                if (eHealth.isDead) continue

                val dx = pt.x - et.x
                val dy = pt.y - et.y
                val distSq = dx * dx + dy * dy
                val minDist = pc.radius + ec.radius

                if (distSq > minDist * minDist) continue

                // Hit!
                proj.hitEntities.add(eid)
                proj.piercingLeft--

                val rawDmg = proj.damage
                applyDamage(world, eid, rawDmg, proj.isCritical, events)

                // Knockback
                if (proj.knockback > 0f) {
                    val dist = sqrt(distSq).coerceAtLeast(0.001f)
                    val vel  = world.velocities[eid]
                    vel?.let {
                        it.vx -= (dx / dist) * proj.knockback
                        it.vy -= (dy / dist) * proj.knockback
                    }
                }

                // Spawn damage number
                EntityFactory.createDamageNumber(world, et.x, et.y - ec.radius, rawDmg, proj.isCritical)

                // Particles on hit
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.BLOOD, 4)

                if (proj.piercingLeft <= 0) {
                    world.destroyEntity(pid)
                    break
                }
            }

            // Check projectile lifetime (also handled in WeaponSystem but defensive)
            val remaining = world.projectiles[pid]?.lifetime ?: continue
            if (remaining <= 0f) world.destroyEntity(pid)
        }
    }

    // ── Player vs Enemies ──────────────────────────────────────────────────

    private fun checkPlayerVsEnemies(world: World, dt: Float, events: MutableList<GameEvent>) {
        val playerId  = world.getPlayerEntity()
        if (playerId == -1) return
        val pt        = world.transforms[playerId]  ?: return
        val pc        = world.colliders[playerId]   ?: return
        val pHealth   = world.healths[playerId]     ?: return
        val pComp     = world.players[playerId]     ?: return

        if (pHealth.invincibleTimer > 0f) return

        queryResult.clear()
        grid.query(pt.x, pt.y, pc.radius + 80f, queryResult)

        for (eid in queryResult) {
            if (eid == playerId) continue
            val enemy  = world.enemies[eid]    ?: continue
            val et     = world.transforms[eid] ?: continue
            val ec     = world.colliders[eid]  ?: continue
            val eHealth= world.healths[eid]    ?: continue
            if (eHealth.isDead) continue

            val dx = pt.x - et.x
            val dy = pt.y - et.y
            val distSq = dx * dx + dy * dy
            val minDist = pc.radius + ec.radius

            if (distSq > minDist * minDist) continue
            if (enemy.attackTimer > 0f) continue

            // Deal damage
            val dmg = (enemy.attackDamage - pComp.armor).coerceAtLeast(1)
            pHealth.current    = (pHealth.current - dmg).coerceAtLeast(0)
            pHealth.invincibleTimer = pHealth.invincibleDuration
            enemy.attackTimer  = enemy.attackCooldown

            events.add(GameEvent.PlayerHit(dmg))

            if (pHealth.current <= 0) {
                pHealth.isDead = true
                events.add(GameEvent.PlayerDied)
            }

            // Life steal
            if (pComp.lifeSteal > 0f) {
                val healed = (dmg * pComp.lifeSteal).toInt().coerceAtLeast(1)
                pHealth.current = (pHealth.current + healed).coerceAtMost(pHealth.max)
            }

            // Exploder special: explode on contact
            if (enemy.type == EnemyType.EXPLODER) {
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.EXPLOSION, 16)
                world.destroyEntity(eid)
                events.add(GameEvent.EnemyKilled(eid, enemy.type, enemy.experienceValue, enemy.coinDropChance))
            }
        }
    }

    // ── Player vs Pickups ──────────────────────────────────────────────────

    private fun checkPlayerVsPickups(world: World, events: MutableList<GameEvent>) {
        val playerId = world.getPlayerEntity()
        if (playerId == -1) return
        val pt       = world.transforms[playerId]  ?: return
        val pc       = world.colliders[playerId]   ?: return
        val pComp    = world.players[playerId]     ?: return

        queryResult.clear()
        grid.query(pt.x, pt.y, pComp.magnetRadius, queryResult)

        for (pkId in queryResult) {
            val pickup = world.pickups[pkId]   ?: continue
            val pkt    = world.transforms[pkId]?: continue
            val pkc    = world.colliders[pkId] ?: continue

            val dx     = pt.x - pkt.x
            val dy     = pt.y - pkt.y
            val distSq = dx * dx + dy * dy

            // Magnet range — start pulling
            if (distSq < pComp.magnetRadius * pComp.magnetRadius) {
                pickup.magnetized = true
                val dist = sqrt(distSq).coerceAtLeast(0.001f)
                pickup.magnetSpeed = (pComp.magnetRadius - dist) / pComp.magnetRadius * 400f + 80f
                val vel = world.velocities[pkId]
                vel?.let {
                    it.vx += (dx / dist) * pickup.magnetSpeed * 0.16f
                    it.vy += (dy / dist) * pickup.magnetSpeed * 0.16f
                }
            }

            // Actual collection
            val pickupDist = pc.radius + pkc.radius + 8f
            if (distSq < pickupDist * pickupDist) {
                events.add(GameEvent.PickupCollected(pkId, pickup.type, pickup.value))
                world.destroyEntity(pkId)
            }
        }
    }

    // ── Enemy Separation ───────────────────────────────────────────────────

    private fun checkEnemySeparation(world: World) {
        val enemyIds = world.getEnemyEntities().toList()
        // Only do separation within nearby pairs using the grid
        for (id in enemyIds) {
            val t  = world.transforms[id] ?: continue
            val c  = world.colliders[id]  ?: continue

            queryResult.clear()
            grid.query(t.x, t.y, c.radius * 3f, queryResult)

            for (otherId in queryResult) {
                if (otherId <= id) continue // avoid double processing
                if (!world.enemies.containsKey(otherId)) continue
                val ot = world.transforms[otherId] ?: continue
                val oc = world.colliders[otherId]  ?: continue

                val dx = t.x - ot.x
                val dy = t.y - ot.y
                val distSq = dx * dx + dy * dy
                val minDist = c.radius + oc.radius

                if (distSq >= minDist * minDist || distSq < 0.001f) continue

                val dist  = sqrt(distSq)
                val push  = (minDist - dist) * 0.5f
                val nx    = dx / dist
                val ny    = dy / dist

                t.x  += nx * push
                t.y  += ny * push
                ot.x -= nx * push
                ot.y -= ny * push
            }
        }
    }

    // ── Damage helper ──────────────────────────────────────────────────────

    private fun applyDamage(
        world: World, eid: Int, damage: Int,
        isCritical: Boolean, events: MutableList<GameEvent>
    ) {
        val health = world.healths[eid] ?: return
        val enemy  = world.enemies[eid]

        if (health.invincibleTimer > 0f) return
        health.invincibleTimer = health.invincibleDuration

        health.current = (health.current - damage).coerceAtLeast(0)

        // Flash on hit
        world.renders[eid]?.flashTimer = 0.1f

        if (health.current <= 0 && !health.isDead) {
            health.isDead = true
            val type = enemy?.type ?: EnemyType.BASIC
            val xp   = enemy?.experienceValue ?: 5
            val coinChance = enemy?.coinDropChance ?: 0.1f
            events.add(GameEvent.EnemyKilled(eid, type, xp, coinChance))
        }
    }
}

