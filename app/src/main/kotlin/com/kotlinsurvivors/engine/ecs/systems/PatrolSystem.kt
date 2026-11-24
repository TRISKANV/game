package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.AIState
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.engine.map.LevelMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The K9 patrol: a guard walks with a dog on a leash and has not noticed the player yet.
 *
 *  - Walking: the guard strolls in a direction, now and then turning; the dog trots ahead of
 *    him, sniffing from side to side, and can never get further than the leash allows.
 *  - Noticing: the guard sees the player inside [GUARD_SIGHT] in a wide cone in front of him, and
 *    only within [GUARD_SENSE_ALL_ROUND] behind his back; the dog, which trots ahead of him,
 *    smells the player within [DOG_SMELL] all round, through walls. Hurting either of them also raises the alarm, and so does losing the other.
 *  - Alarm: the dog is let go at once and runs at the player; the guard stops for
 *    [ALERT_SECONDS] with a "!" over his head and then goes after him like any other guard.
 *
 * A patrolling enemy has [PatrolState.WALK] or [PatrolState.ALERT]; [MovementSystem] leaves it
 * alone until it is back to [PatrolState.NONE], when it behaves like an ordinary enemy.
 */
class PatrolSystem {

    /** Alarms raised since the last [consumeAlarms]; the engine turns them into a bark. */
    private var alarms = 0

    private var clock = 0f

    fun consumeAlarms(): Int { val n = alarms; alarms = 0; return n }

    fun update(world: World, dt: Float, map: LevelMap?, worldSize: Float) {
        clock += dt
        val pid = world.getPlayerEntity()
        val pt  = world.transforms[pid]
        for (eid in world.getEnemySnapshot()) {
            val enemy = world.enemies[eid] ?: continue
            if (enemy.patrol == PatrolState.NONE) continue
            val t = world.transforms[eid] ?: continue
            val v = world.velocities[eid] ?: continue
            when (enemy.type) {
                EnemyType.BASIC -> updateGuard(world, eid, dt, pt?.x, pt?.y, map, worldSize)
                EnemyType.DOG   -> updateDog(world, eid, pt?.x, pt?.y, map)
                else            -> { enemy.patrol = PatrolState.NONE }
            }
            // anything that is not a walking patrol must not keep a stale leash
            if (enemy.patrol == PatrolState.NONE && enemy.partner >= 0) enemy.partner = -1
            if (t.x.isNaN() || v.vx.isNaN()) enemy.patrol = PatrolState.NONE
        }
    }

    /** After the bodies have moved: the dog cannot go further than the leash from its guard. */
    fun holdLeashes(world: World) {
        for ((eid, enemy) in world.enemies) {
            if (enemy.type != EnemyType.DOG || enemy.patrol != PatrolState.WALK || enemy.partner < 0) continue
            val dt = world.transforms[eid] ?: continue
            val gt = world.transforms[enemy.partner] ?: continue
            val dx = dt.x - gt.x; val dy = dt.y - gt.y
            val d  = sqrt(dx * dx + dy * dy)
            if (d > LEASH_MAX && d > 0.001f) {
                dt.x = gt.x + dx / d * LEASH_MAX
                dt.y = gt.y + dy / d * LEASH_MAX
            }
        }
    }

    // ── Guard ───────────────────────────────────────────────────────────────

    private fun updateGuard(world: World, eid: Int, dt: Float, px: Float?, py: Float?, map: LevelMap?, worldSize: Float) {
        val enemy = world.enemies[eid] ?: return
        val v     = world.velocities[eid] ?: return
        val t     = world.transforms[eid] ?: return
        val hp    = world.healths[eid]

        if (enemy.patrol == PatrolState.ALERT) {
            v.vx = 0f; v.vy = 0f
            enemy.alertTimer -= dt
            if (enemy.alertTimer <= 0f) { enemy.patrol = PatrolState.NONE; enemy.aiState = AIState.CHASE }
            return
        }

        val partner   = if (enemy.partner >= 0) world.enemies[enemy.partner] else null
        val partnerHp = if (enemy.partner >= 0) world.healths[enemy.partner] else null
        val hurt = (hp != null && hp.current < hp.max) || (partnerHp != null && partnerHp.current < partnerHp.max)
        if (partner == null || hurt || (px != null && py != null && guardSees(t.x, t.y, enemy.patrolDir, px, py, map, world.colliders[eid]?.radius ?: 16f))) {
            raiseAlarm(world, eid)
            return
        }

        // wall in the way: turn away from it
        if (enemy.followingPath) {
            enemy.followingPath = false
            enemy.patrolDir += PI.toFloat() + (Random.nextFloat() - 0.5f) * 1.6f
            enemy.patrolTimer = nextTurnIn()
        }
        enemy.patrolTimer -= dt
        if (enemy.patrolTimer <= 0f) {
            enemy.patrolDir  += (Random.nextFloat() - 0.5f) * 2.2f
            enemy.patrolTimer = nextTurnIn()
        }
        // near the edge of the world: head back inwards
        val m = EDGE_MARGIN
        if (t.x < m || t.y < m || t.x > worldSize - m || t.y > worldSize - m) {
            enemy.patrolDir = atan2(worldSize * 0.5f - t.y, worldSize * 0.5f - t.x)
        }
        v.vx = cos(enemy.patrolDir) * GUARD_PATROL_SPEED
        v.vy = sin(enemy.patrolDir) * GUARD_PATROL_SPEED
    }

    private fun guardSees(x: Float, y: Float, heading: Float, px: Float, py: Float, map: LevelMap?, radius: Float): Boolean {
        val dx = px - x; val dy = py - y
        val d2 = dx * dx + dy * dy
        if (d2 > GUARD_SIGHT * GUARD_SIGHT) return false
        val d = sqrt(d2).coerceAtLeast(0.001f)
        if (d > GUARD_SENSE_ALL_ROUND) {
            val facing = (dx / d) * cos(heading) + (dy / d) * sin(heading)
            if (facing < CONE_COS) return false                      // behind his back
        }
        return map == null || map.hasLineOfSight(x, y + radius * 0.8f, px, py + FEET_OFFSET)
    }

    // ── Dog ─────────────────────────────────────────────────────────────────

    private fun updateDog(world: World, eid: Int, px: Float?, py: Float?, map: LevelMap?) {
        val enemy = world.enemies[eid] ?: return
        val v     = world.velocities[eid] ?: return
        val t     = world.transforms[eid] ?: return
        val guard = if (enemy.partner >= 0) world.enemies[enemy.partner] else null
        val gt    = if (enemy.partner >= 0) world.transforms[enemy.partner] else null
        if (guard == null || gt == null) { enemy.patrol = PatrolState.NONE; enemy.aiState = AIState.CHASE; return }

        val hp = world.healths[eid]
        val smelled = px != null && py != null && run {
            val dx = px - t.x; val dy = py - t.y
            dx * dx + dy * dy < DOG_SMELL * DOG_SMELL
        }
        if (smelled || (hp != null && hp.current < hp.max)) { raiseAlarm(world, enemy.partner); return }

        // trot ahead of the guard, swinging from side to side
        val h  = guard.patrolDir
        val sway = sin(clock * 1.7f + eid) * LEASH_SWAY
        val tx = gt.x + cos(h) * LEASH_AHEAD - sin(h) * sway
        val ty = gt.y + sin(h) * LEASH_AHEAD + cos(h) * sway
        val dx = tx - t.x; val dy = ty - t.y
        val d  = sqrt(dx * dx + dy * dy)
        if (d < 3f) { v.vx = 0f; v.vy = 0f; return }
        val speed = (d * DOG_EASE).coerceAtMost(DOG_PATROL_SPEED)
        v.vx = dx / d * speed
        v.vy = dy / d * speed
    }

    // ── Alarm ───────────────────────────────────────────────────────────────

    /** Lets the dog go and stops the guard for a moment; both are enemies like any other afterwards. */
    fun raiseAlarm(world: World, guardId: Int) {
        val guard = world.enemies[guardId] ?: return
        if (guard.patrol == PatrolState.ALERT) return
        val dogId = guard.partner
        world.enemies[dogId]?.let { dog ->
            dog.patrol  = PatrolState.NONE
            dog.partner = -1
            dog.aiState = AIState.CHASE
        }
        guard.partner    = -1
        guard.patrol     = PatrolState.ALERT
        guard.alertTimer = ALERT_SECONDS
        alarms++
    }

    private fun nextTurnIn() = TURN_MIN + Random.nextFloat() * (TURN_MAX - TURN_MIN)

    companion object {
        const val GUARD_PATROL_SPEED = 38f
        const val DOG_PATROL_SPEED   = 70f
        const val DOG_EASE           = 4f
        const val GUARD_SIGHT        = 300f
        const val GUARD_SENSE_ALL_ROUND = 100f
        const val CONE_COS           = -0.17f        // about 100 degrees to each side of where he looks
        const val DOG_SMELL          = 160f
        const val ALERT_SECONDS      = 0.9f
        const val LEASH_MAX          = 64f
        const val LEASH_AHEAD        = 46f
        const val LEASH_SWAY         = 20f
        const val TURN_MIN           = 2.5f
        const val TURN_MAX           = 4.5f
        const val EDGE_MARGIN        = 220f
        private const val FEET_OFFSET = 26f           // the player's feet, as MovementSystem uses them
    }
}
