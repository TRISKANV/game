package com.kotlinsurvivors.engine.ecs.systems

import android.util.Log
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.spatial.IntList
import com.kotlinsurvivors.engine.spatial.SpatialGrid
import kotlin.math.sqrt

class CollisionSystem {

    companion object {
        private const val TAG = "KS_Collision"
        private const val RUN_OVER_GRACE_SECONDS = 0.5f
    }

    private val grid        = SpatialGrid(cellSize = 128f)
    private val queryResult = IntList(64)

    fun update(world: World, dt: Float, events: MutableList<GameEvent>) {
        try {
            buildGrid(world)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in buildGrid: transforms=${world.transforms.size}", e); throw e
        }
        try {
            checkProjectileVsEnemies(world, events)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in checkProjectileVsEnemies: proj=${world.projectiles.size} enemies=${world.enemies.size}", e); throw e
        }
        try {
            checkCarRunOver(world, events)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in checkCarRunOver", e); throw e
        }
        try {
            checkPlayerVsEnemies(world, dt, events)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in checkPlayerVsEnemies", e); throw e
        }
        try {
            checkPlayerVsPickups(world, events)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in checkPlayerVsPickups: pickups=${world.pickups.size}", e); throw e
        }
        try {
            checkEnemySeparation(world)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in checkEnemySeparation: enemies=${world.enemies.size}", e); throw e
        }
    }

    private fun buildGrid(world: World) {
        grid.clear()
        for (id in world.getTransformSnapshot()) {
            val t = world.transforms[id] ?: continue
            val c = world.colliders[id]  ?: continue
            grid.insert(id, t.x, t.y, c.radius)
        }
    }

    private fun checkProjectileVsEnemies(world: World, events: MutableList<GameEvent>) {
        val projIds = world.getProjectileSnapshot()
        for (pid in projIds) {
            val proj = world.projectiles[pid] ?: continue
            val pt   = world.transforms[pid]  ?: continue
            val pc   = world.colliders[pid]   ?: continue

            if (proj.piercingLeft <= 0) { world.destroyEntity(pid); continue }

            queryResult.clear()
            grid.query(pt.x, pt.y, pc.radius + 64f, queryResult)

            for (qi in 0 until queryResult.size) {
                val eid = queryResult[qi]
                if (eid == pid) continue
                if (eid in proj.hitEntities) continue
                val et      = world.transforms[eid] ?: continue
                val ec      = world.colliders[eid]  ?: continue
                val eHealth = world.healths[eid]    ?: continue
                if (!world.isAlive(eid) || eHealth.isDead) continue
                if (world.enemies[eid] == null && world.bosses[eid] == null) continue

                val dx = pt.x - et.x; val dy = pt.y - et.y
                val distSq = dx * dx + dy * dy
                val minDist = pc.radius + ec.radius
                if (distSq > minDist * minDist) continue

                proj.hitEntities.add(eid)
                proj.piercingLeft--
                applyDamage(world, eid, proj.damage, proj.isCritical, events)

                if (proj.knockback > 0f) {
                    val dist = sqrt(distSq).coerceAtLeast(0.001f)
                    world.velocities[eid]?.let { it.vx -= (dx / dist) * proj.knockback; it.vy -= (dy / dist) * proj.knockback }
                }

                EntityFactory.createDamageNumber(world, et.x, et.y - ec.radius, proj.damage, proj.isCritical)
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.BLOOD, 4)

                // A shot is used up by the first enemy it hits. Only the water, which acts as an
                // area, lives on and damages everything inside it.
                if (proj.weaponType != WeaponType.SANTA_WATER || proj.piercingLeft <= 0) {
                    world.destroyEntity(pid)
                    break
                }
            }

            val remaining = world.projectiles[pid]?.lifetime ?: continue
            if (remaining <= 0f) world.destroyEntity(pid)
        }
    }

    /** A car at speed hurts whoever it drives into, and loses a little of its own health for each one. */
    private fun checkCarRunOver(world: World, events: MutableList<GameEvent>) {
        val playerId = world.getPlayerEntity()
        val carId = world.players[playerId]?.drivingCar ?: return
        if (carId < 0) return
        val car = world.cars[carId] ?: return
        val ct  = world.transforms[carId] ?: return
        if (car.speed < CarRules.RUN_OVER_SPEED) return

        queryResult.clear()
        grid.query(ct.x, ct.y, CarRules.BODY_RADIUS + 60f, queryResult)
        for (qi in 0 until queryResult.size) {
            val eid = queryResult[qi]
            if (world.enemies[eid] == null) continue
            val et = world.transforms[eid] ?: continue
            val ec = world.colliders[eid]  ?: continue
            val eHealth = world.healths[eid] ?: continue
            if (!world.isAlive(eid) || eHealth.isDead || eHealth.invincibleTimer > 0f) continue
            val dx = et.x - ct.x; val dy = et.y - ct.y
            val reach = CarRules.BODY_RADIUS + ec.radius
            val distSq = dx * dx + dy * dy
            if (distSq > reach * reach) continue

            val damage = CarRules.runOverDamage(car.speed)
            applyDamage(world, eid, damage, false, events)
            eHealth.invincibleTimer = RUN_OVER_GRACE_SECONDS          // one pass, one hit
            val dist = sqrt(distSq).coerceAtLeast(0.001f)
            world.velocities[eid]?.let { it.vx += dx / dist * car.speed * 1.2f; it.vy += dy / dist * car.speed * 1.2f }
            EntityFactory.createDamageNumber(world, et.x, et.y - ec.radius, damage, false)
            EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.BLOOD, 5)
            car.hp = (car.hp - 1).coerceAtLeast(0)
            car.hurtTimer = 0.1f
        }
    }

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

        for (qi in 0 until queryResult.size) {
            val eid = queryResult[qi]
            if (eid == playerId) continue
            val enemy   = world.enemies[eid]    ?: continue
            val et      = world.transforms[eid] ?: continue
            val ec      = world.colliders[eid]  ?: continue
            val eHealth = world.healths[eid]    ?: continue
            if (eHealth.isDead || enemy.attackTimer > 0f || enemy.asleep || enemy.shieldUp) continue

            val dx = pt.x - et.x; val dy = pt.y - et.y
            val distSq = dx * dx + dy * dy
            if (distSq > (pc.radius + ec.radius) * (pc.radius + ec.radius)) continue

            // Inside a car, the blows land on the car and the driver is not touched.
            val car = if (pComp.drivingCar >= 0) world.cars[pComp.drivingCar] else null
            if (car != null) {
                car.hp = (car.hp - enemy.attackDamage).coerceAtLeast(0)
                car.hurtTimer = 0.15f
                enemy.attackTimer = enemy.attackCooldown
                EntityFactory.createParticleBurst(world, pt.x, pt.y, ParticleType.BLOOD, 3)
                continue
            }

            val dmg = (enemy.attackDamage - pComp.armor).coerceAtLeast(1)
            pHealth.current         = (pHealth.current - dmg).coerceAtLeast(0)
            pHealth.invincibleTimer = pHealth.invincibleDuration
            enemy.attackTimer       = enemy.attackCooldown
            events.add(GameEvent.PlayerHit(dmg))
            Log.d(TAG, "Player hit by ${enemy.type} for $dmg dmg. HP=${pHealth.current}/${pHealth.max}")

            if (pHealth.current <= 0) {
                pHealth.isDead = true
                Log.d(TAG, "Player died!")
                events.add(GameEvent.PlayerDied)
            }

            if (pComp.lifeSteal > 0f) {
                val healed = (dmg * pComp.lifeSteal).toInt().coerceAtLeast(1)
                pHealth.current = (pHealth.current + healed).coerceAtMost(pHealth.max)
            }

            if (enemy.type == EnemyType.EXPLODER) {
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.EXPLOSION, 8)
                world.destroyEntity(eid)
                events.add(GameEvent.EnemyKilled(eid, enemy.type, enemy.experienceValue, enemy.coinDropChance, enemy.isMiniBoss))
            }
        }
    }

    private fun checkPlayerVsPickups(world: World, events: MutableList<GameEvent>) {
        val playerId = world.getPlayerEntity()
        if (playerId == -1) return
        val pt    = world.transforms[playerId]  ?: return
        val pc    = world.colliders[playerId]   ?: return
        val pComp = world.players[playerId]     ?: return

        val magnetRadius = pComp.magnetRadius * pComp.comboMagnetMultiplier   // the kill streak can widen it for a while
        queryResult.clear()
        grid.query(pt.x, pt.y, magnetRadius, queryResult)

        for (qi in 0 until queryResult.size) {
            val pkId = queryResult[qi]
            val pickup = world.pickups[pkId]    ?: continue
            val pkt    = world.transforms[pkId] ?: continue
            val pkc    = world.colliders[pkId]  ?: continue
            val isChest = pickup.type == PickupType.CHEST
            if (isChest && pickup.openTimer >= 0f) continue          // already opening

            val dx = pt.x - pkt.x; val dy = pt.y - pkt.y
            val distSq = dx * dx + dy * dy

            if (!pickup.type.staysPut && distSq < magnetRadius * magnetRadius) {
                pickup.magnetized = true
                val dist = sqrt(distSq).coerceAtLeast(0.001f)
                pickup.magnetSpeed = (magnetRadius - dist) / magnetRadius * 400f + 80f
                world.velocities[pkId]?.let {
                    it.vx += (dx / dist) * pickup.magnetSpeed * 0.16f
                    it.vy += (dy / dist) * pickup.magnetSpeed * 0.16f
                }
            }

            val pickupDist = pc.radius + pkc.radius + 8f
            if (distSq < pickupDist * pickupDist) {
                if (isChest) {
                    // The chest stays where it is and plays its opening; ExperienceSystem finishes it.
                    pickup.openTimer = 0f
                    continue
                }
                events.add(GameEvent.PickupCollected(pkId, pickup.type, pickup.value))
                world.destroyEntity(pkId)
            }
        }
    }

    private fun checkEnemySeparation(world: World) {
        val enemyIds = world.getEnemySnapshot()
        for (id in enemyIds) {
            val t = world.transforms[id] ?: continue
            val c = world.colliders[id]  ?: continue
            queryResult.clear()
            grid.query(t.x, t.y, c.radius * 3f, queryResult)
            for (qi in 0 until queryResult.size) {
                val otherId = queryResult[qi]
                if (otherId <= id || !world.enemies.containsKey(otherId)) continue
                val ot = world.transforms[otherId] ?: continue
                val oc = world.colliders[otherId]  ?: continue
                val dx = t.x - ot.x; val dy = t.y - ot.y
                val distSq  = dx * dx + dy * dy
                val minDist = c.radius + oc.radius
                if (distSq >= minDist * minDist || distSq < 0.001f) continue
                val dist = sqrt(distSq)
                val push = (minDist - dist) * 0.5f
                val nx = dx / dist; val ny = dy / dist
                t.x  += nx * push; t.y  += ny * push
                ot.x -= nx * push; ot.y -= ny * push
            }
        }
    }

    private fun applyDamage(world: World, eid: Int, damage: Int, isCritical: Boolean, events: MutableList<GameEvent>) {
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
            events.add(GameEvent.EnemyKilled(eid, type, xp, coinChance, enemy?.isMiniBoss ?: false))
        }
    }
}
