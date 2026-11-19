package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.FlowField
import com.kotlinsurvivors.engine.map.LevelMap
import kotlin.math.*
import kotlin.random.Random

/**
 * MovementSystem
 *
 * All iterations use World.getXxxSnapshot() (ID-only, zero Pair allocation).
 */
class MovementSystem(
    private val worldWidth : Float,
    private val worldHeight: Float
) {

    /** Collision grid of the current level; null means open ground with no obstacles. */
    var levelMap: LevelMap? = null
        set(value) {
            field = value
            flow  = value?.let { FlowField(it) }
        }

    /** Walking distances to the player, so enemies can find their way round obstacles. */
    private var flow: FlowField? = null

    fun update(world: World, dt: Float, joystick: JoystickState) {
        updatePlayer(world, dt, joystick)
        updateEnemies(world, dt)
        integratePositions(world, dt)
    }

    private fun updatePlayer(world: World, dt: Float, joystick: JoystickState) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return

        val player   = world.players[pid]    ?: return
        val velocity = world.velocities[pid] ?: return
        val health   = world.healths[pid]    ?: return

        player.survivalTime += dt

        if (player.regenPerSecond > 0f && health.current < health.max) {
            health.current = (health.current + player.regenPerSecond * dt)
                .toInt().coerceAtMost(health.max)
        }

        if (joystick.isActive) {
            velocity.vx = joystick.dx * player.speed
            velocity.vy = joystick.dy * player.speed
        } else {
            velocity.vx *= (1f - dt * 15f).coerceAtLeast(0f)
            velocity.vy *= (1f - dt * 15f).coerceAtLeast(0f)
        }
    }

    private fun updateEnemies(world: World, dt: Float) {
        val pid     = world.getPlayerEntity()
        val playerX = world.transforms[pid]?.x ?: 0f
        val playerY = world.transforms[pid]?.y ?: 0f
        val map     = levelMap
        val field   = flow
        field?.update(playerX, playerY + PLAYER_FEET_OFFSET, dt)

        for (eid in world.getEnemySnapshot()) {
            val enemy     = world.enemies[eid]    ?: continue
            val velocity  = world.velocities[eid] ?: continue
            val transform = world.transforms[eid] ?: continue

            enemy.attackTimer = (enemy.attackTimer - dt).coerceAtLeast(0f)
            enemy.stateTimer  = (enemy.stateTimer  - dt).coerceAtLeast(0f)
            enemy.wanderTimer = (enemy.wanderTimer  - dt).coerceAtLeast(0f)

            var dx   = playerX - transform.x
            var dy   = playerY - transform.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val spd  = enemySpeed(enemy.type)

            if (map != null && field != null && enemy.followingPath) {
                val feetOffset = (world.colliders[eid]?.radius ?: 12f) * ENEMY_FEET_FACTOR
                val feetY      = transform.y + feetOffset
                // Back to walking straight as soon as the player is in plain sight again.
                enemy.pathCheckTimer -= dt
                if (enemy.pathCheckTimer <= 0f) {
                    enemy.pathCheckTimer = PATH_RECHECK_SECONDS
                    if (map.hasLineOfSight(transform.x, feetY, playerX, playerY + PLAYER_FEET_OFFSET)) {
                        enemy.followingPath = false
                    }
                }
                // Otherwise head for the neighbouring cell that is closer to the player by
                // way of free ground. Keeping the real distance leaves each type's own
                // behaviour (ranged stand-off, boss phases) as it was.
                if (enemy.followingPath && field.directionAt(transform.x, feetY)) {
                    dx = field.dirX * dist
                    dy = field.dirY * dist
                }
            }

            when (enemy.type) {
                EnemyType.FAST -> {
                    velocity.vx = (dx / dist) * spd
                    velocity.vy = (dy / dist) * spd
                }
                EnemyType.TANK -> {
                    velocity.vx = (dx / dist) * spd
                    velocity.vy = (dy / dist) * spd
                }
                EnemyType.RANGED -> {
                    val pref = 300f
                    when {
                        dist > pref + 40f -> { velocity.vx = (dx/dist)*spd;  velocity.vy = (dy/dist)*spd }
                        dist < pref - 40f -> { velocity.vx = -(dx/dist)*spd*0.7f; velocity.vy = -(dy/dist)*spd*0.7f }
                        else -> { velocity.vx = -dy/dist*spd*0.5f; velocity.vy = dx/dist*spd*0.5f }
                    }
                }
                EnemyType.EXPLODER -> {
                    val m = if (dist < 80f) 1.5f else 1f
                    velocity.vx = (dx / dist) * spd * m
                    velocity.vy = (dy / dist) * spd * m
                }
                EnemyType.SWARM -> {
                    val j = 30f
                    velocity.vx = (dx/dist)*spd + Random.nextFloat()*j - j*0.5f
                    velocity.vy = (dy/dist)*spd + Random.nextFloat()*j - j*0.5f
                }
                EnemyType.BOSS_SHADOW,
                EnemyType.BOSS_GOLEM,
                EnemyType.BOSS_NECROMANCER -> updateBossAI(world, eid, dt, dx, dy, dist, spd)
                EnemyType.BASIC -> {
                    if (!enemy.followingPath && enemy.wanderTimer <= 0f && Random.nextFloat() < 0.005f) {
                        enemy.wanderTimer     = 0.5f + Random.nextFloat() * 0.5f
                        enemy.wanderDirection = Random.nextFloat() * 2f * PI.toFloat()
                        enemy.aiState         = AIState.WANDER
                    }
                    if (!enemy.followingPath && enemy.aiState == AIState.WANDER && enemy.wanderTimer > 0f) {
                        velocity.vx = cos(enemy.wanderDirection) * spd * 0.5f
                        velocity.vy = sin(enemy.wanderDirection) * spd * 0.5f
                    } else {
                        enemy.aiState = AIState.CHASE
                        velocity.vx = (dx / dist) * spd
                        velocity.vy = (dy / dist) * spd
                    }
                }
            }
        }
    }

    private fun updateBossAI(world: World, eid: Int, dt: Float,
                              dx: Float, dy: Float, dist: Float, baseSpeed: Float) {
        val boss     = world.bosses[eid]     ?: return
        val velocity = world.velocities[eid] ?: return
        val health   = world.healths[eid]    ?: return

        boss.specialAttackTimer -= dt
        val hpPct = health.percentage
        if (boss.phase == 1 && hpPct < 0.66f) boss.phase = 2
        if (boss.phase == 2 && hpPct < 0.33f) boss.phase = 3
        val sm = 1f + (boss.phase - 1) * 0.4f

        when (boss.phase) {
            1 -> { velocity.vx = (dx/dist)*baseSpeed*sm; velocity.vy = (dy/dist)*baseSpeed*sm }
            2 -> {
                if (boss.specialAttackTimer <= 0f) {
                    boss.specialAttackTimer = boss.specialAttackCooldown
                    boss.enrageTimer = 1.5f; boss.isEnraged = true
                }
                if (boss.isEnraged) {
                    boss.enrageTimer -= dt
                    if (boss.enrageTimer <= 0f) boss.isEnraged = false
                    velocity.vx = (dx/dist)*baseSpeed*sm*2.5f
                    velocity.vy = (dy/dist)*baseSpeed*sm*2.5f
                } else {
                    velocity.vx = (dx/dist)*baseSpeed*sm; velocity.vy = (dy/dist)*baseSpeed*sm
                }
            }
            else -> {
                velocity.vx = (-dy/dist)*baseSpeed*sm*1.2f + (dx/dist)*baseSpeed
                velocity.vy = ( dx/dist)*baseSpeed*sm*1.2f + (dy/dist)*baseSpeed
            }
        }
    }

    private fun enemySpeed(type: EnemyType) = when (type) {
        EnemyType.BASIC            -> 85f
        EnemyType.FAST             -> 170f
        EnemyType.TANK             -> 55f
        EnemyType.RANGED           -> 75f
        EnemyType.EXPLODER         -> 100f
        EnemyType.SWARM            -> 110f
        EnemyType.BOSS_SHADOW      -> 70f
        EnemyType.BOSS_GOLEM       -> 45f
        EnemyType.BOSS_NECROMANCER -> 65f
    }

    private fun integratePositions(world: World, dt: Float) {
        val pid = world.getPlayerEntity()
        val map = levelMap
        // ID snapshot — no Pair allocation
        for (id in world.getVelocitySnapshot()) {
            val vel = world.velocities[id] ?: continue
            val t   = world.transforms[id] ?: continue
            when {
                world.projectiles.containsKey(id) -> {
                    val nx = t.x + vel.vx * dt
                    val ny = t.y + vel.vy * dt
                    val proj = world.projectiles[id]
                    // A shot that flies into a wall, tree or car is lost there. One that starts
                    // inside a blocked cell (fired from right next to a wall) is let out.
                    if (map != null && proj != null && !proj.isAreaEffect &&
                        map.isBlockedAt(nx, ny) && !map.isBlockedAt(t.x, t.y)) {
                        EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.SPARK, 3)
                        world.destroyEntity(id)
                    } else {
                        t.x = nx; t.y = ny
                    }
                }
                id == pid -> {
                    val nx = (t.x + vel.vx * dt).coerceIn(40f, worldWidth  - 40f)
                    val ny = (t.y + vel.vy * dt).coerceIn(40f, worldHeight - 40f)
                    if (map == null) { t.x = nx; t.y = ny }
                    else moveAgainstMap(map, t, vel, nx, ny, PLAYER_HALF, PLAYER_HALF, PLAYER_FEET_OFFSET)
                }
                else -> {
                    val nx = t.x + vel.vx * dt
                    val ny = t.y + vel.vy * dt
                    if (map == null) { t.x = nx; t.y = ny }
                    else {
                        val r = world.colliders[id]?.radius ?: 12f
                        val half = (r * 0.6f).coerceIn(ENEMY_HALF_MIN, ENEMY_HALF_MAX)
                        val hit = moveAgainstMap(map, t, vel, nx, ny, half, half, r * ENEMY_FEET_FACTOR)
                        val enemy = world.enemies[id]
                        if (hit && enemy != null && !enemy.followingPath) {
                            enemy.followingPath  = true
                            enemy.pathCheckTimer = PATH_FIRST_CHECK_SECONDS
                        }
                    }
                }
            }
        }
    }

    /**
     * Moves one axis at a time so a body that hits a wall slides along it instead of
     * stopping dead. The blocked axis loses its speed, which the AI or joystick
     * recomputes on the next tick. Returns true when a wall stopped it on either axis.
     */
    private fun moveAgainstMap(
        map: LevelMap, t: TransformComponent, vel: VelocityComponent,
        nx: Float, ny: Float, halfW: Float, halfH: Float, feetOffset: Float
    ): Boolean {
        // The box sits at the feet, not at the centre of the sprite: that is the part
        // that must not overlap a wall, while the head may pass in front of it.
        var blocked = false
        if (map.canMove(t.x, t.y + feetOffset, nx, t.y + feetOffset, halfW, halfH)) t.x = nx
        else { vel.vx = 0f; blocked = true }
        if (map.canMove(t.x, t.y + feetOffset, t.x, ny + feetOffset, halfW, halfH)) t.y = ny
        else { vel.vy = 0f; blocked = true }
        return blocked
    }

    companion object {
        // Half size of the box that collides with the level, in world pixels. It covers
        // only the feet (the sprite is 90 px tall and its feet are about 38 px below the
        // position), so the head can overlap lamps and trees like in any top-down game.
        // It fits through a two-cell (32 px) gap.
        const val PLAYER_HALF        = 12f
        const val PLAYER_FEET_OFFSET = 26f
        const val ENEMY_FEET_FACTOR  = 0.8f

        // An enemy that bumps into something goes round it by the flow field for at least
        // this long, then checks every PATH_RECHECK_SECONDS whether it can see the player.
        const val PATH_FIRST_CHECK_SECONDS = 0.6f
        const val PATH_RECHECK_SECONDS     = 0.3f
        const val ENEMY_HALF_MIN = 6f
        const val ENEMY_HALF_MAX = 14f
    }
}
