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

    /** The beat of each officer in the station: x, y pairs in world pixels, keyed by entity id. */
    val routes = HashMap<Int, FloatArray>()

    /** True from the moment the officers raise the alarm until [consumeStationAlarm] is called. */
    private var stationAlarm = false

    fun consumeStationAlarm(): Boolean { val a = stationAlarm; stationAlarm = false; return a }

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
                EnemyType.COP   -> updateCop(world, eid, dt, pt?.x, pt?.y, map)
                EnemyType.CHIEF -> updateChief(world, eid, dt)
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

    // ── Police officer ──────────────────────────────────────────────────────

    /**
     * An officer on his beat walks from point to point of his route and waits a moment at each one;
     * an officer with a single point stands at his post. He sees the player only within [COP_SIGHT], in front of him
     * and in plain view, so there is time to read the poster before anyone looks round.
     */
    private fun updateCop(world: World, eid: Int, dt: Float, px: Float?, py: Float?, map: LevelMap?) {
        val enemy = world.enemies[eid] ?: return
        val v     = world.velocities[eid] ?: return
        val t     = world.transforms[eid] ?: return
        val hp    = world.healths[eid]

        if (enemy.patrol == PatrolState.ALERT) {
            v.vx = 0f; v.vy = 0f
            enemy.alertTimer -= dt
            if (enemy.alertTimer <= 0f) {
                enemy.patrol = PatrolState.NONE; enemy.aiState = AIState.CHASE; enemy.shouting = false
                routes.remove(eid)
            }
            return
        }
        val hurt = hp != null && hp.current < hp.max
        if (hurt || (px != null && py != null && copSees(t.x, t.y, enemy.patrolDir, px, py, map, world.colliders[eid]?.radius ?: 16f))) {
            raiseStationAlarm(world, px ?: t.x, py ?: t.y)
            return
        }

        val route = routes[eid]
        if (route == null || route.size < 4) { v.vx = 0f; v.vy = 0f; return }      // a post: he stands there
        enemy.patrolTimer -= dt
        if (enemy.patrolTimer > 0f) { v.vx = 0f; v.vy = 0f; return }                // waiting at a point
        val count = route.size / 2
        val i  = enemy.routeIndex.coerceIn(0, count - 1)
        val tx = route[i * 2]; val ty = route[i * 2 + 1]
        val dx = tx - t.x; val dy = ty - t.y
        val d  = sqrt(dx * dx + dy * dy)
        if (d < COP_ARRIVE) {
            enemy.routeIndex = (i + 1) % count
            enemy.patrolTimer = COP_WAIT_MIN + Random.nextFloat() * (COP_WAIT_MAX - COP_WAIT_MIN)
            v.vx = 0f; v.vy = 0f
            return
        }
        enemy.patrolDir = atan2(dy, dx)
        v.vx = dx / d * COP_PATROL_SPEED
        v.vy = dy / d * COP_PATROL_SPEED
    }

    private fun copSees(x: Float, y: Float, heading: Float, px: Float, py: Float, map: LevelMap?, radius: Float): Boolean {
        val dx = px - x; val dy = py - y
        val d2 = dx * dx + dy * dy
        if (d2 > COP_SIGHT * COP_SIGHT) return false
        val d = sqrt(d2).coerceAtLeast(0.001f)
        if (d > COP_SENSE_ALL_ROUND) {
            val facing = (dx / d) * cos(heading) + (dy / d) * sin(heading)
            if (facing < COP_CONE_COS) return false
        }
        return map == null || map.hasLineOfSight(x, y + radius * 0.8f, px, py + FEET_OFFSET)
    }

    /**
     * The alarm of the station: the officer nearest to the player shouts that he is the one they are looking for,
     * and all the others stop for a moment, one after the other, and then go after him.
     */
    fun raiseStationAlarm(world: World, px: Float, py: Float) {
        var shouter = -1
        var best = Float.MAX_VALUE
        for ((eid, enemy) in world.enemies) {
            if (enemy.type != EnemyType.COP || enemy.patrol != PatrolState.WALK) continue
            val t = world.transforms[eid] ?: continue
            val dx = t.x - px; val dy = t.y - py
            val d = dx * dx + dy * dy
            if (d < best) { best = d; shouter = eid }
        }
        if (shouter < 0) return
        for ((eid, enemy) in world.enemies) {
            if (enemy.type != EnemyType.COP || enemy.patrol != PatrolState.WALK) continue
            enemy.patrol = PatrolState.ALERT
            if (eid == shouter) {
                enemy.shouting = true
                enemy.alertTimer = COP_SHOUT_SECONDS
            } else {
                enemy.alertTimer = COP_ALERT_MIN + Random.nextFloat() * (COP_ALERT_MAX - COP_ALERT_MIN)
            }
        }
        stationAlarm = true
    }

    // ── Police chief ────────────────────────────────────────────────────────

    /** The chief sleeps where he is; once woken he stops for [CHIEF_WAKE_SECONDS] with his speech bubble and then fights. */
    private fun updateChief(world: World, eid: Int, dt: Float) {
        val enemy = world.enemies[eid] ?: return
        val v     = world.velocities[eid] ?: return
        v.vx = 0f; v.vy = 0f
        if (enemy.patrol != PatrolState.ALERT) return              // asleep
        enemy.alertTimer -= dt
        if (enemy.alertTimer <= 0f) {
            enemy.patrol = PatrolState.NONE; enemy.aiState = AIState.CHASE; enemy.shouting = false
        }
    }

    fun wakeChief(world: World, chiefId: Int) {
        val enemy = world.enemies[chiefId] ?: return
        if (enemy.type != EnemyType.CHIEF || !enemy.asleep) return
        enemy.asleep     = false
        enemy.patrol     = PatrolState.ALERT
        enemy.shouting   = true
        enemy.alertTimer = CHIEF_WAKE_SECONDS
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
        const val CHIEF_WAKE_SECONDS = 3.2f
        const val COP_PATROL_SPEED   = 46f
        const val COP_ARRIVE         = 10f
        const val COP_WAIT_MIN       = 1.0f
        const val COP_WAIT_MAX       = 2.6f
        const val COP_SIGHT          = 210f
        const val COP_SENSE_ALL_ROUND = 60f
        const val COP_CONE_COS       = 0.25f          // about 75 degrees to each side of where he looks
        const val COP_SHOUT_SECONDS  = 1.7f
        const val COP_ALERT_MIN      = 0.5f
        const val COP_ALERT_MAX      = 1.2f
        private const val FEET_OFFSET = 26f           // the player's feet, as MovementSystem uses them
    }
}
