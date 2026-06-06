package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.spatial.SpatialGrid
import kotlin.math.sqrt

/**
 * CollisionSystem
 *
 * Fix: buildGrid now uses getTransformSnapshot() so that inserting
 * entities into the spatial grid never iterates a live HashMap view.
 * Enemy separation uses getEnemySnapshot() for the outer loop.
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
        // ID snapshot — no Pair allocation, direct component lookup
        for (id in world.getTransformSnapshot()) {
            val t = world.transforms[id] ?: continue
            val c = world.colliders[id]  ?: continue
            grid.insert(id, t.x, t.y, c.radius)
        }
    }

    // ── Projectile vs Enemies ──────────────────────────────────────────────

    private fun checkProjectileVsEnemies(world: World, events: MutableList<GameEvent>) {
        // SNAPSHOT — we call destroyEntity() inside this loop
        val projIds = world.getProjectileSnapshot()
        for (pid in projIds) {
            val proj = world.projectiles[pid] ?: continue
            val pt   = world.transforms[pid]  ?: continue
            val pc   = world.colliders[pid]   ?: continue

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
                val distSq  = dx * dx + dy * dy
                val minDist = pc.radius + ec.radius

                if (distSq > minDist * minDist) continue

                proj.hitEntities.add(eid)
                proj.piercingLeft--

                applyDamage(world, eid, proj.damage, proj.isCritical, events)

                if (proj.knockback > 0f) {
                    val dist = sqrt(distSq).coerceAtLeast(0.001f)
                    world.velocities[eid]?.let {
                        it.vx -= (dx / dist) * proj.knockback
                        it.vy -= (dy / dist) * proj.knockback
                    }
                }

                EntityFactory.createDamageNumber(world, et.x, et.y - ec.radius, proj.damage, proj.isCritical)
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.BLOOD, 4)

                if (proj.piercingLeft <= 0) {
                    world.destroyEntity(pid)
                    break
                }
            }

            val remaining = world.projectiles[pid]?.lifetime ?: continue
            if (remaining <= 0f) world.destroyEntity(pid)
        }
    }

    // ── Player vs Enemies ──────────────────────────────────────────────────

    private fun checkPlayerVsEnemies(world: World, dt: Float, events: MutableList<GameEvent>) {
        val playerId = world.getPlayerEntity()
        if (playerId == -1) return
        val pt      = world.transforms[playerId]  ?: return
        val pc      = world.colliders[playerId]   ?: return
        val pHealth = world.healths[playerId]     ?: return
        val pComp   = world.players[playerId]     ?: return

        if (pHealth.invincibleTimer > 0f) return

        queryResult.clear()
        grid.query(pt.x, pt.y, pc.radius + 80f, queryResult)

        for (eid in queryResult) {
            if (eid == playerId) continue
            val enemy   = world.enemies[eid]    ?: continue
            val et      = world.transforms[eid] ?: continue
            val ec      = world.colliders[eid]  ?: continue
            val eHealth = world.healths[eid]    ?: continue
            if (eHealth.isDead) continue

            val dx = pt.x - et.x; val dy = pt.y - et.y
            val distSq  = dx * dx + dy * dy
            val minDist = pc.radius + ec.radius

            if (distSq > minDist * minDist) continue
            if (enemy.attackTimer > 0f) continue

            val dmg = (enemy.attackDamage - pComp.armor).coerceAtLeast(1)
            pHealth.current         = (pHealth.current - dmg).coerceAtLeast(0)
            pHealth.invincibleTimer = pHealth.invincibleDuration
            enemy.attackTimer       = enemy.attackCooldown

            events.add(GameEvent.PlayerHit(dmg))

            if (pHealth.current <= 0) {
                pHealth.isDead = true
                events.add(GameEvent.PlayerDied)
            }

            if (pComp.lifeSteal > 0f) {
                val healed = (dmg * pComp.lifeSteal).toInt().coerceAtLeast(1)
                pHealth.current = (pHealth.current + healed).coerceAtMost(pHealth.max)
            }

            if (enemy.type == EnemyType.EXPLODER) {
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.EXPLOSION, 8)
                world.destroyEntity(eid)
                events.add(GameEvent.EnemyKilled(eid, enemy.type, enemy.experienceValue, enemy.coinDropChance))
            }
        }
    }

    // ── Player vs Pickups ──────────────────────────────────────────────────

    private fun checkPlayerVsPickups(world: World, events: MutableList<GameEvent>) {
        val playerId = world.getPlayerEntity()
        if (playerId == -1) return
        val pt    = world.transforms[playerId]  ?: return
        val pc    = world.colliders[playerId]   ?: return
        val pComp = world.players[playerId]     ?: return

        queryResult.clear()
        grid.query(pt.x, pt.y, pComp.magnetRadius, queryResult)

        for (pkId in queryResult) {
            val pickup = world.pickups[pkId]    ?: continue
            val pkt    = world.transforms[pkId] ?: continue
            val pkc    = world.colliders[pkId]  ?: continue

            val dx = pt.x - pkt.x; val dy = pt.y - pkt.y
            val distSq = dx * dx + dy * dy

            if (distSq < pComp.magnetRadius * pComp.magnetRadius) {
                pickup.magnetized = true
                val dist = sqrt(distSq).coerceAtLeast(0.001f)
                pickup.magnetSpeed = (pComp.magnetRadius - dist) / pComp.magnetRadius * 400f + 80f
                world.velocities[pkId]?.let {
                    it.vx += (dx / dist) * pickup.magnetSpeed * 0.16f
                    it.vy += (dy / dist) * pickup.magnetSpeed * 0.16f
                }
            }

            val pickupDist = pc.radius + pkc.radius + 8f
            if (distSq < pickupDist * pickupDist) {
                events.add(GameEvent.PickupCollected(pkId, pickup.type, pickup.value))
                world.destroyEntity(pkId)
            }
        }
    }

    // ── Enemy Separation ───────────────────────────────────────────────────

    private fun checkEnemySeparation(world: World) {
        // SNAPSHOT outer loop — separation pushes modify transforms but not the
        // enemies map structure, still safer to snapshot
        val enemyIds = world.getEnemySnapshot()
        for (id in enemyIds) {
            val t = world.transforms[id] ?: continue
            val c = world.colliders[id]  ?: continue

            queryResult.clear()
            grid.query(t.x, t.y, c.radius * 3f, queryResult)

            for (otherId in queryResult) {
                if (otherId <= id) continue
                if (!world.enemies.containsKey(otherId)) continue
                val ot = world.transforms[otherId] ?: continue
                val oc = world.colliders[otherId]  ?: continue

                val dx = t.x - ot.x; val dy = t.y - ot.y
                val distSq  = dx * dx + dy * dy
                val minDist = c.radius + oc.radius

                if (distSq >= minDist * minDist || distSq < 0.001f) continue

                val dist = sqrt(distSq)
                val push = (minDist - dist) * 0.5f
                val nx   = dx / dist; val ny = dy / dist

                t.x  += nx * push;  t.y  += ny * push
                ot.x -= nx * push;  ot.y -= ny * push
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
        world.renders[eid]?.flashTimer = 0.1f

        if (health.current <= 0 && !health.isDead) {
            health.isDead = true
            val type       = enemy?.type           ?: EnemyType.BASIC
            val xp         = enemy?.experienceValue ?: 5
            val coinChance = enemy?.coinDropChance  ?: 0.1f
            events.add(GameEvent.EnemyKilled(eid, type, xp, coinChance))
        }
    }
}
