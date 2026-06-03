package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.AIState
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.input.JoystickState
import kotlin.math.*
import kotlin.random.Random

/**
 * MovementSystem
 *
 * Responsibilities:
 *  1. Apply joystick input to the player velocity.
 *  2. Run per-enemy AI state machines (chase / wander / charge / circle-strafe).
 *  3. Integrate velocities into positions (Euler integration).
 *  4. Clamp player within world bounds.
 */
class MovementSystem(
    private val worldWidth: Float,
    private val worldHeight: Float
) {

    fun update(world: World, dt: Float, joystick: JoystickState) {
        updatePlayer(world, dt, joystick)
        updateEnemies(world, dt)
        integratePositions(world, dt)
    }

    // ── Player movement ────────────────────────────────────────────────────

    private fun updatePlayer(world: World, dt: Float, joystick: JoystickState) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return

        val player   = world.players[pid]    ?: return
        val velocity = world.velocities[pid] ?: return
        val health   = world.healths[pid]    ?: return

        // Update survival timer
        player.survivalTime += dt

        // Regen
        if (player.regenPerSecond > 0f && health.current < health.max) {
            health.current = (health.current + player.regenPerSecond * dt).toInt().coerceAtMost(health.max)
        }

        // Apply joystick to velocity
        val speed = player.speed
        if (joystick.isActive) {
            velocity.vx = joystick.dx * speed
            velocity.vy = joystick.dy * speed
        } else {
            // Decelerate smoothly
            velocity.vx *= (1f - dt * 15f).coerceAtLeast(0f)
            velocity.vy *= (1f - dt * 15f).coerceAtLeast(0f)
        }
    }

    // ── Enemy AI ───────────────────────────────────────────────────────────

    private fun updateEnemies(world: World, dt: Float) {
        val pid = world.getPlayerEntity()
        val playerX = world.transforms[pid]?.x ?: 0f
        val playerY = world.transforms[pid]?.y ?: 0f

        for (eid in world.getEnemyEntities()) {
            val enemy    = world.enemies[eid]    ?: continue
            val velocity = world.velocities[eid] ?: continue
            val transform= world.transforms[eid] ?: continue

            enemy.attackTimer  = (enemy.attackTimer - dt).coerceAtLeast(0f)
            enemy.stateTimer   = (enemy.stateTimer  - dt).coerceAtLeast(0f)
            enemy.wanderTimer  = (enemy.wanderTimer  - dt).coerceAtLeast(0f)

            val dx = playerX - transform.x
            val dy = playerY - transform.y
            val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

            val baseSpeed = enemySpeed(enemy.type)

            when (enemy.type) {
                EnemyType.FAST -> {
                    // Always charge at high speed
                    velocity.vx = (dx / dist) * baseSpeed
                    velocity.vy = (dy / dist) * baseSpeed
                }

                EnemyType.TANK -> {
                    // Slow, relentless approach
                    velocity.vx = (dx / dist) * baseSpeed
                    velocity.vy = (dy / dist) * baseSpeed
                    // Slight separation from other enemies handled by collision
                }

                EnemyType.RANGED -> {
                    // Maintain distance, attack from range
                    val preferredDist = 300f
                    when {
                        dist > preferredDist + 40f -> {
                            velocity.vx = (dx / dist) * baseSpeed
                            velocity.vy = (dy / dist) * baseSpeed
                        }
                        dist < preferredDist - 40f -> {
                            velocity.vx = -(dx / dist) * baseSpeed * 0.7f
                            velocity.vy = -(dy / dist) * baseSpeed * 0.7f
                        }
                        else -> {
                            // Strafe
                            velocity.vx = -dy / dist * baseSpeed * 0.5f
                            velocity.vy =  dx / dist * baseSpeed * 0.5f
                        }
                    }
                }

                EnemyType.EXPLODER -> {
                    // Rush toward player, explodes on contact
                    if (dist < 80f) {
                        // handled by collision system — trigger explosion
                        velocity.vx = (dx / dist) * baseSpeed * 1.5f
                        velocity.vy = (dy / dist) * baseSpeed * 1.5f
                    } else {
                        velocity.vx = (dx / dist) * baseSpeed
                        velocity.vy = (dy / dist) * baseSpeed
                    }
                }

                EnemyType.SWARM -> {
                    // Swarm: mostly chase but jitter slightly
                    val jitter = 30f
                    velocity.vx = (dx / dist) * baseSpeed + Random.nextFloat() * jitter - jitter * 0.5f
                    velocity.vy = (dy / dist) * baseSpeed + Random.nextFloat() * jitter - jitter * 0.5f
                }

                EnemyType.BOSS_SHADOW -> updateBossAI(world, eid, dt, dx, dy, dist, baseSpeed)
                EnemyType.BOSS_GOLEM  -> updateBossAI(world, eid, dt, dx, dy, dist, baseSpeed)
                EnemyType.BOSS_NECROMANCER -> updateBossAI(world, eid, dt, dx, dy, dist, baseSpeed)

                EnemyType.BASIC -> {
                    // Standard chase
                    if (enemy.wanderTimer <= 0f && Random.nextFloat() < 0.005f) {
                        // Occasionally wander
                        enemy.wanderTimer    = 0.5f + Random.nextFloat() * 0.5f
                        enemy.wanderDirection= Random.nextFloat() * 2f * PI.toFloat()
                        enemy.aiState        = AIState.WANDER
                    }
                    if (enemy.aiState == AIState.WANDER && enemy.wanderTimer > 0f) {
                        velocity.vx = cos(enemy.wanderDirection) * baseSpeed * 0.5f
                        velocity.vy = sin(enemy.wanderDirection) * baseSpeed * 0.5f
                    } else {
                        enemy.aiState = AIState.CHASE
                        velocity.vx = (dx / dist) * baseSpeed
                        velocity.vy = (dy / dist) * baseSpeed
                    }
                }
            }
        }
    }

    private fun updateBossAI(
        world: World, eid: Int, dt: Float,
        dx: Float, dy: Float, dist: Float, baseSpeed: Float
    ) {
        val boss     = world.bosses[eid]    ?: return
        val velocity = world.velocities[eid] ?: return
        val health   = world.healths[eid]    ?: return

        boss.specialAttackTimer -= dt

        // Phase transitions based on health percentage
        val hpPct = health.percentage
        if (boss.phase == 1 && hpPct < 0.66f) boss.phase = 2
        if (boss.phase == 2 && hpPct < 0.33f) boss.phase = 3

        // Speed and aggression scale with phase
        val speedMult = 1f + (boss.phase - 1) * 0.4f

        when (boss.phase) {
            1 -> {
                // Basic chase
                velocity.vx = (dx / dist) * baseSpeed * speedMult
                velocity.vy = (dy / dist) * baseSpeed * speedMult
            }
            2 -> {
                // Charge + circle strafe alternating
                if (boss.specialAttackTimer <= 0f) {
                    boss.specialAttackTimer = boss.specialAttackCooldown
                    boss.enrageTimer = 1.5f
                    boss.isEnraged = true
                }
                if (boss.isEnraged) {
                    boss.enrageTimer -= dt
                    if (boss.enrageTimer <= 0f) boss.isEnraged = false
                    // Charge
                    velocity.vx = (dx / dist) * baseSpeed * speedMult * 2.5f
                    velocity.vy = (dy / dist) * baseSpeed * speedMult * 2.5f
                } else {
                    velocity.vx = (dx / dist) * baseSpeed * speedMult
                    velocity.vy = (dy / dist) * baseSpeed * speedMult
                }
            }
            3 -> {
                // Erratic: circles + rushes
                val circleSpeed = baseSpeed * speedMult * 1.2f
                velocity.vx = (-dy / dist) * circleSpeed + (dx / dist) * baseSpeed
                velocity.vy = ( dx / dist) * circleSpeed + (dy / dist) * baseSpeed
            }
        }
    }

    private fun enemySpeed(type: EnemyType) = when (type) {
        EnemyType.BASIC   -> 85f
        EnemyType.FAST    -> 170f
        EnemyType.TANK    -> 55f
        EnemyType.RANGED  -> 75f
        EnemyType.EXPLODER-> 100f
        EnemyType.SWARM   -> 110f
        EnemyType.BOSS_SHADOW     -> 70f
        EnemyType.BOSS_GOLEM      -> 45f
        EnemyType.BOSS_NECROMANCER-> 65f
    }

    // ── Integration ────────────────────────────────────────────────────────

    private fun integratePositions(world: World, dt: Float) {
        val pid = world.getPlayerEntity()

        for ((id, vel) in world.velocities) {
            val t = world.transforms[id] ?: continue
            if (world.projectiles.containsKey(id)) {
                // Projectiles: full velocity, no clamp
                t.x += vel.vx * dt
                t.y += vel.vy * dt
            } else if (id == pid) {
                // Player: clamp to world bounds
                t.x = (t.x + vel.vx * dt).coerceIn(40f, worldWidth - 40f)
                t.y = (t.y + vel.vy * dt).coerceIn(40f, worldHeight - 40f)
            } else {
                // Enemies / pickups: free movement (they wrap or get culled elsewhere)
                t.x += vel.vx * dt
                t.y += vel.vy * dt
            }
        }
    }
}
