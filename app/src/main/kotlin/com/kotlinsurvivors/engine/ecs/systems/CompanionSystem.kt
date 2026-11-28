package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.CompanionComponent
import com.kotlinsurvivors.engine.ecs.components.CompanionState
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.engine.map.LevelMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The police dog that fights beside the player once he is out of his cell.
 *
 * He keeps near the player, goes for the closest enemy that has noticed the fight and bites it, and the
 * enemies hurt him in return. When his health runs out he does not die: he lies down for [DOWN_SECONDS]
 * and gets up with half of it. Everything here reads and writes [World] on the engine thread, like the
 * other systems; he has no health component or collider, so no weapon or contact rule meant for enemies
 * ever touches him.
 */
class CompanionSystem {

    private var bites = 0

    /** How many times the dog has bitten since the last call, so the engine can bark once per bite. */
    fun consumeBites(): Int { val n = bites; bites = 0; return n }

    fun update(world: World, dt: Float, map: LevelMap?, events: MutableList<GameEvent>) {
        if (world.companions.isEmpty()) return
        val pid = world.getPlayerEntity()
        val pt = world.transforms[pid] ?: return
        val driving = (world.players[pid]?.drivingCar ?: -1) >= 0
        for ((id, c) in world.companions) {
            val t = world.transforms[id] ?: continue
            c.sinceBite += dt
            if (c.biteTimer > 0f) c.biteTimer -= dt
            if (c.hurtTimer > 0f) c.hurtTimer -= dt
            when (c.state) {
                CompanionState.CAGED -> Unit
                CompanionState.DOWNED -> {
                    c.vx = 0f; c.vy = 0f
                    c.downTimer -= dt
                    if (c.downTimer <= 0f) { c.state = CompanionState.FOLLOW; c.hp = c.maxHp / 2; c.hurtTimer = GRACE_AFTER_GETTING_UP }
                }
                CompanionState.FOLLOW -> {
                    if (driving) { t.x = pt.x; t.y = pt.y; c.vx = 0f; c.vy = 0f; continue }
                    follow(world, id, c, t.x, t.y, pt.x, pt.y, dt, map, events)
                }
            }
        }
    }

    private fun follow(
        world: World, id: Int, c: CompanionComponent, x: Float, y: Float, px: Float, py: Float,
        dt: Float, map: LevelMap?, events: MutableList<GameEvent>
    ) {
        val t = world.transforms[id] ?: return
        val toPlayer = hypot(px - x, py - y)
        if (toPlayer > TELEPORT_DISTANCE || (c.stuckTimer > STUCK_SECONDS && toPlayer > STUCK_MIN_DISTANCE)) {
            placeNear(world, id, map, px, py)
            c.stuckTimer = 0f
            return
        }

        var target = -1
        var best = SIGHT * SIGHT
        for ((eid, e) in world.enemies) {
            if (e.asleep || e.patrol == PatrolState.WALK || e.type.isFlying) continue
            val h = world.healths[eid] ?: continue
            if (h.isDead || !world.isAlive(eid)) continue
            val et = world.transforms[eid] ?: continue
            if (hypot(et.x - px, et.y - py) > LEASH_FROM_PLAYER) continue
            val d2 = (et.x - x) * (et.x - x) + (et.y - y) * (et.y - y)
            if (d2 < best) { best = d2; target = eid }
        }

        val goalX: Float; val goalY: Float; var speed: Float
        if (target >= 0) {
            val et = world.transforms[target]!!
            goalX = et.x; goalY = et.y; speed = RUN_SPEED
            val reach = BITE_RANGE + (world.colliders[target]?.radius ?: 12f)
            if (hypot(et.x - x, et.y - y) <= reach && c.biteTimer <= 0f) bite(world, c, target, events)
        } else {
            // Stay a few steps behind the player, off to one side, and slow down when close.
            val a = atan2(y - py, x - px)
            goalX = px + cos(a) * FOLLOW_DISTANCE; goalY = py + sin(a) * FOLLOW_DISTANCE
            val d = hypot(goalX - x, goalY - y)
            speed = if (toPlayer > FOLLOW_DISTANCE * 2.5f) RUN_SPEED else (d * EASE).coerceAtMost(TROT_SPEED)
            if (d < 6f) speed = 0f
        }

        val dx = goalX - x; val dy = goalY - y
        val dist = hypot(dx, dy)
        var moved = 0f
        if (speed > 0f && dist > 0.001f) {
            val step = speed * dt
            val nx = x + dx / dist * step; val ny = y + dy / dist * step
            if (map == null) { t.x = nx; t.y = ny }
            else {
                if (map.canMove(t.x, t.y + FEET, nx, t.y + FEET, HALF, HALF)) t.x = nx
                if (map.canMove(t.x, t.y + FEET, t.x, ny + FEET, HALF, HALF)) t.y = ny
            }
            moved = hypot(t.x - x, t.y - y)
            c.vx = (t.x - x) / dt; c.vy = (t.y - y) / dt
            if (moved > 0.01f) c.heading = atan2(t.y - y, t.x - x)
        } else { c.vx = 0f; c.vy = 0f }
        if (target >= 0) world.transforms[target]?.let { c.heading = atan2(it.y - t.y, it.x - t.x) }
        // trying to go somewhere and not getting there (a doorway, a corner)
        if (speed > 0f && moved < speed * dt * 0.25f) c.stuckTimer += dt else c.stuckTimer = 0f

        keepOutOfThings(world, id, t, map)
        if (c.state == CompanionState.FOLLOW) {
            regenerate(c, dt)
            takeHits(world, id, c, t.x, t.y)
        }
    }

    /**
     * The dog is a solid body: enemies (a boss most of all) push him out of the space they take, and a wall, a
     * parked car or anything else the level marks as solid never ends up under him, even if it was put down on top of
     * him (a car parked where he stood).
     */
    private fun keepOutOfThings(world: World, id: Int, t: com.kotlinsurvivors.engine.ecs.components.TransformComponent, map: LevelMap?) {
        for ((eid, e) in world.enemies) {
            if (e.type.isFlying || !world.isAlive(eid)) continue
            val et = world.transforms[eid] ?: continue
            val dx = t.x - et.x; val dy = t.y - et.y
            val min = BODY_RADIUS + (world.colliders[eid]?.radius ?: 12f) * BODY_OVERLAP
            val d2 = dx * dx + dy * dy
            if (d2 >= min * min) continue
            val d = kotlin.math.sqrt(d2)
            val nx: Float; val ny: Float
            if (d < 0.01f) { nx = 1f; ny = 0f } else { nx = dx / d; ny = dy / d }
            val push = min - d
            val px = t.x + nx * push; val py = t.y + ny * push
            // do not shove him into a wall: slide along it instead
            if (map == null || map.isBoxFree(px, py + FEET, HALF, HALF)) { t.x = px; t.y = py }
            else if (map.isBoxFree(px, t.y + FEET, HALF, HALF)) t.x = px
            else if (map.isBoxFree(t.x, py + FEET, HALF, HALF)) t.y = py
        }
        if (map != null && !map.isBoxFree(t.x, t.y + FEET, HALF, HALF)) {
            // growing rings round him until there is room; the same search the other bodies use
            var radius = 6f
            search@ while (radius <= EJECT_RADIUS) {
                for (k in 0 until 16) {
                    val a = k * (2f * PI.toFloat() / 16f)
                    val nx = t.x + cos(a) * radius; val ny = t.y + sin(a) * radius
                    if (map.isBoxFree(nx, ny + FEET, HALF, HALF)) { t.x = nx; t.y = ny; break@search }
                }
                radius += 6f
            }
        }
    }

    private fun bite(world: World, c: CompanionComponent, eid: Int, events: MutableList<GameEvent>) {
        val health = world.healths[eid] ?: return
        val enemy = world.enemies[eid] ?: return
        val et = world.transforms[eid] ?: return
        c.biteTimer = BITE_COOLDOWN
        c.sinceBite = 0f
        bites++
        if (health.invincibleTimer > 0f) return
        health.invincibleTimer = health.invincibleDuration
        health.current = (health.current - BITE_DAMAGE).coerceAtLeast(0)
        world.renders[eid]?.flashTimer = 0.1f
        EntityFactory.createDamageNumber(world, et.x, et.y - (world.colliders[eid]?.radius ?: 12f), BITE_DAMAGE, false)
        EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.BLOOD, 4)
        if (health.current <= 0 && !health.isDead) {
            health.isDead = true
            events.add(GameEvent.EnemyKilled(eid, enemy.type, enemy.experienceValue, enemy.coinDropChance, enemy.isMiniBoss))
        }
    }

    /** Enemies that reach the dog bite back, a little softer than they hit the player. */
    private fun takeHits(world: World, id: Int, c: CompanionComponent, x: Float, y: Float) {
        if (c.hurtTimer > 0f) return
        for ((eid, e) in world.enemies) {
            if (e.asleep || e.shieldUp || e.attackTimer > 0f || e.patrol == PatrolState.WALK) continue
            val h = world.healths[eid] ?: continue
            if (h.isDead) continue
            val et = world.transforms[eid] ?: continue
            val reach = HIT_RANGE + (world.colliders[eid]?.radius ?: 12f)
            if (hypot(et.x - x, et.y - y) > reach) continue
            val dmg = (e.attackDamage / 2).coerceAtLeast(1)
            e.attackTimer = e.attackCooldown
            c.hp = (c.hp - dmg).coerceAtLeast(0)
            c.hurtTimer = HURT_GRACE
            c.regen = 0f
            world.renders[id]?.flashTimer = 0.12f
            EntityFactory.createParticleBurst(world, x, y, ParticleType.BLOOD, 3)
            if (c.hp <= 0) {
                c.state = CompanionState.DOWNED
                c.downTimer = DOWN_SECONDS
                c.vx = 0f; c.vy = 0f
            }
            return
        }
    }

    private fun regenerate(c: CompanionComponent, dt: Float) {
        if (c.hp >= c.maxHp || c.hurtTimer > 0f) return
        c.regen += REGEN_PER_SECOND * dt
        val whole = c.regen.toInt()
        if (whole > 0) { c.hp = (c.hp + whole).coerceAtMost(c.maxHp); c.regen -= whole }
    }

    /** Puts the dog on the free spot nearest to (x, y), a few steps from it. */
    fun placeNear(world: World, id: Int, map: LevelMap?, x: Float, y: Float) {
        val t = world.transforms[id] ?: return
        var radius = FOLLOW_DISTANCE
        while (radius <= 260f) {
            for (k in 0 until 12) {
                val a = k * (2f * PI.toFloat() / 12f)
                val nx = x + cos(a) * radius; val ny = y + sin(a) * radius
                if (map == null || map.isBoxFree(nx, ny + FEET, HALF, HALF)) { t.x = nx; t.y = ny; return }
            }
            radius += 30f
        }
        t.x = x; t.y = y
    }

    companion object {
        const val HALF = 10f
        /** From the dog's centre to his paws, which is where he touches the ground. */
        const val FEET = 22f
        /** Radius of his body against other bodies, and how much of an enemy's own radius counts. */
        const val BODY_RADIUS = 10f
        const val BODY_OVERLAP = 0.9f
        const val EJECT_RADIUS = 160f
        const val SIGHT = 300f
        const val LEASH_FROM_PLAYER = 440f
        const val TELEPORT_DISTANCE = 760f
        const val STUCK_SECONDS = 1.4f
        const val STUCK_MIN_DISTANCE = 160f
        const val FOLLOW_DISTANCE = 64f
        const val RUN_SPEED = 235f
        const val TROT_SPEED = 130f
        const val EASE = 4f
        const val BITE_RANGE = 26f
        const val BITE_COOLDOWN = 0.55f
        const val BITE_DAMAGE = 14
        const val HIT_RANGE = 22f
        const val HURT_GRACE = 0.45f
        const val DOWN_SECONDS = 8f
        const val GRACE_AFTER_GETTING_UP = 1.5f
        const val REGEN_PER_SECOND = 2f
    }
}
