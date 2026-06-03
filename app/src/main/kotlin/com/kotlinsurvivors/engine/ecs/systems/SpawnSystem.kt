package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import kotlin.math.*
import kotlin.random.Random

/**
 * SpawnSystem
 *
 * Controls progressive enemy spawning:
 *  - Base spawn rate increases with elapsed time
 *  - Enemy pool diversifies as time progresses
 *  - Boss spawns on fixed time intervals
 *  - Enemies spawn off-screen around the player
 */
class SpawnSystem(
    private val viewportWidth: Float,
    private val viewportHeight: Float
) {

    private var spawnTimer       = 0f
    private var bossTimer        = 0f
    private var waveTimer        = 0f
    private var currentWave      = 0

    // Spawn rate: starts at 1 enemy/1.2s, scales to 1 enemy/0.18s at minute 20
    private var spawnInterval    = 1.2f
    private val minSpawnInterval = 0.18f

    // How many enemies can exist simultaneously (scales with time)
    private var maxEnemies       = 60
    private val absoluteMaxEnemies = 800

    // Boss spawn every 3 minutes
    private val bossInterval     = 180f

    fun update(world: World, dt: Float, elapsedTime: Float): SpawnResult {
        val result = SpawnResult()
        val playerEntity = world.getPlayerEntity()
        if (playerEntity == -1) return result

        val pt = world.transforms[playerEntity] ?: return result

        // Update max enemy cap over time
        maxEnemies = (60 + (elapsedTime / 60f) * 80f).toInt().coerceAtMost(absoluteMaxEnemies)

        // Scale spawn interval down with time
        val progress     = (elapsedTime / 1200f).coerceIn(0f, 1f) // 20 min = 1.0
        spawnInterval    = lerp(1.2f, minSpawnInterval, easeIn(progress))

        // Wave timer
        waveTimer += dt
        if (waveTimer >= 30f) {
            waveTimer = 0f
            currentWave++
            result.newWave = currentWave
        }

        // Regular spawns
        spawnTimer += dt
        if (spawnTimer >= spawnInterval) {
            spawnTimer = 0f
            val count  = world.getEnemyEntities().size
            if (count < maxEnemies) {
                val burstCount = spawnBurstCount(elapsedTime)
                repeat(burstCount) {
                    if (world.getEnemyEntities().size < maxEnemies) {
                        spawnEnemy(world, pt.x, pt.y, elapsedTime)
                        result.enemiesSpawned++
                    }
                }
            }
        }

        // Boss spawns
        bossTimer += dt
        if (bossTimer >= bossInterval) {
            bossTimer = 0f
            val bossType = pickBossType(elapsedTime)
            spawnBoss(world, pt.x, pt.y, bossType, elapsedTime)
            result.bossSpawned = true
        }

        return result
    }

    private fun spawnBurstCount(elapsed: Float): Int {
        return when {
            elapsed < 60f   -> 1
            elapsed < 180f  -> 2
            elapsed < 360f  -> 3
            elapsed < 600f  -> Random.nextInt(3, 6)
            else            -> Random.nextInt(5, 10)
        }
    }

    private fun spawnEnemy(world: World, px: Float, py: Float, elapsed: Float) {
        val type  = pickEnemyType(elapsed)
        val pos   = randomSpawnPosition(px, py)
        val mult  = waveMultiplier(elapsed)
        EntityFactory.createEnemy(world, type, pos.first, pos.second, mult)
    }

    private fun spawnBoss(world: World, px: Float, py: Float, type: EnemyType, elapsed: Float) {
        val pos  = randomSpawnPosition(px, py)
        val mult = (1f + elapsed / 180f).coerceAtMost(4f)
        EntityFactory.createEnemy(world, type, pos.first, pos.second, mult)
    }

    /**
     * Gradually introduce stronger enemy types as the game progresses.
     */
    private fun pickEnemyType(elapsed: Float): EnemyType {
        val pool = buildEnemyPool(elapsed)
        return pool[Random.nextInt(pool.size)]
    }

    private fun buildEnemyPool(elapsed: Float): List<EnemyType> {
        val pool = mutableListOf(EnemyType.BASIC, EnemyType.BASIC, EnemyType.BASIC)
        if (elapsed > 30f)  pool.addAll(listOf(EnemyType.FAST, EnemyType.FAST))
        if (elapsed > 60f)  pool.addAll(listOf(EnemyType.SWARM, EnemyType.SWARM, EnemyType.SWARM))
        if (elapsed > 90f)  pool.add(EnemyType.TANK)
        if (elapsed > 120f) pool.addAll(listOf(EnemyType.RANGED, EnemyType.RANGED))
        if (elapsed > 180f) pool.add(EnemyType.EXPLODER)
        if (elapsed > 240f) pool.add(EnemyType.TANK)
        return pool
    }

    private fun pickBossType(elapsed: Float): EnemyType {
        val bosses = mutableListOf(EnemyType.BOSS_SHADOW)
        if (elapsed > 180f) bosses.add(EnemyType.BOSS_GOLEM)
        if (elapsed > 360f) bosses.add(EnemyType.BOSS_NECROMANCER)
        return bosses[Random.nextInt(bosses.size)]
    }

    private fun waveMultiplier(elapsed: Float): Float {
        return 1f + (elapsed / 120f) * 0.5f  // +50% stats every 2 minutes
    }

    /**
     * Spawn off-screen by positioning outside the visible viewport + margin.
     * Uses a random point on a rect around the player.
     */
    private fun randomSpawnPosition(px: Float, py: Float): Pair<Float, Float> {
        val margin  = 80f
        val halfW   = viewportWidth  * 0.5f + margin
        val halfH   = viewportHeight * 0.5f + margin

        // Pick a random edge (0=top, 1=right, 2=bottom, 3=left)
        val edge = Random.nextInt(4)
        return when (edge) {
            0 -> Pair(px + Random.nextFloat() * halfW * 2f - halfW, py - halfH)
            1 -> Pair(px + halfW, py + Random.nextFloat() * halfH * 2f - halfH)
            2 -> Pair(px + Random.nextFloat() * halfW * 2f - halfW, py + halfH)
            else -> Pair(px - halfW, py + Random.nextFloat() * halfH * 2f - halfH)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeIn(t: Float) = t * t

    fun reset() {
        spawnTimer    = 0f
        bossTimer     = 0f
        waveTimer     = 0f
        currentWave   = 0
        spawnInterval = 1.2f
        maxEnemies    = 60
    }

    data class SpawnResult(
        var enemiesSpawned: Int = 0,
        var bossSpawned: Boolean = false,
        var newWave: Int = -1
    )
}
