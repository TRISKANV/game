package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import kotlin.math.*
import kotlin.random.Random

/**
 * SpawnSystem
 *
 * Memory fixes:
 *  1. buildEnemyPool() and pickBossType() no longer allocate new lists on
 *     every spawn check — they use pre-built fixed arrays indexed at construction.
 *  2. randomSpawnPosition() returns floats directly instead of Pair<Float,Float>.
 *  3. absoluteMaxEnemies kept at 300 (was 800) to bound memory usage.
 */
class SpawnSystem(
    private val viewportWidth : Float,
    private val viewportHeight: Float
) {

    private var spawnTimer       = 0f
    private var bossTimer        = 0f
    private var waveTimer        = 0f
    private var currentWave      = 0
    private var spawnInterval    = 1.2f
    private val minSpawnInterval = 0.25f  // raised from 0.18 to reduce peak entity count
    private var maxEnemies       = 50     // lowered starting cap
    private val absoluteMaxEnemies = 300  // lowered from 800 to prevent OOM
    private val bossInterval     = 180f

    // Pre-built pool arrays — allocated once, never recreated
    private val poolEarly  = arrayOf(EnemyType.BASIC, EnemyType.BASIC, EnemyType.BASIC)
    private val poolMid    = arrayOf(EnemyType.BASIC, EnemyType.BASIC, EnemyType.FAST,
                                     EnemyType.FAST,  EnemyType.SWARM, EnemyType.SWARM)
    private val poolLate   = arrayOf(EnemyType.BASIC, EnemyType.FAST,  EnemyType.FAST,
                                     EnemyType.SWARM,  EnemyType.TANK,  EnemyType.RANGED,
                                     EnemyType.RANGED, EnemyType.EXPLODER)
    private val bossList   = arrayOf(EnemyType.BOSS_SHADOW, EnemyType.BOSS_GOLEM, EnemyType.BOSS_NECROMANCER)

    // Reusable spawn position — avoids Pair allocation every spawn
    private var spawnX = 0f
    private var spawnY = 0f

    fun update(world: World, dt: Float, elapsedTime: Float): SpawnResult {
        val result       = SpawnResult()
        val playerEntity = world.getPlayerEntity()
        if (playerEntity == -1) return result

        val pt = world.transforms[playerEntity] ?: return result

        // Scale enemy cap with time, hard-capped at absoluteMaxEnemies
        maxEnemies = (50 + (elapsedTime / 60f) * 50f).toInt().coerceAtMost(absoluteMaxEnemies)

        // Scale spawn interval
        val progress  = (elapsedTime / 1200f).coerceIn(0f, 1f)
        spawnInterval = lerp(1.2f, minSpawnInterval, progress * progress)

        // Wave timer
        waveTimer += dt
        if (waveTimer >= 30f) { waveTimer = 0f; currentWave++; result.newWave = currentWave }

        // Regular spawns
        spawnTimer += dt
        if (spawnTimer >= spawnInterval) {
            spawnTimer = 0f
            val currentCount = world.getEnemyEntities().size
            if (currentCount < maxEnemies) {
                val burst = spawnBurstCount(elapsedTime)
                repeat(burst) {
                    if (world.getEnemyEntities().size < maxEnemies) {
                        calcSpawnPosition(pt.x, pt.y)
                        val type = pickEnemyType(elapsedTime)
                        EntityFactory.createEnemy(world, type, spawnX, spawnY, waveMultiplier(elapsedTime))
                        result.enemiesSpawned++
                    }
                }
            }
        }

        // Boss spawns
        bossTimer += dt
        if (bossTimer >= bossInterval) {
            bossTimer = 0f
            calcSpawnPosition(pt.x, pt.y)
            val bossType = pickBossType(elapsedTime)
            val mult = (1f + elapsedTime / 180f).coerceAtMost(4f)
            EntityFactory.createEnemy(world, bossType, spawnX, spawnY, mult)
            result.bossSpawned = true
        }

        return result
    }

    private fun spawnBurstCount(elapsed: Float) = when {
        elapsed < 60f  -> 1
        elapsed < 180f -> 2
        elapsed < 360f -> 3
        else           -> 4  // was up to 10 — reduced to control entity count
    }

    private fun pickEnemyType(elapsed: Float): EnemyType {
        val pool = when {
            elapsed < 30f  -> poolEarly
            elapsed < 120f -> poolMid
            else           -> poolLate
        }
        return pool[Random.nextInt(pool.size)]
    }

    private fun pickBossType(elapsed: Float): EnemyType {
        val count = when {
            elapsed < 180f -> 1
            elapsed < 360f -> 2
            else           -> 3
        }
        return bossList[Random.nextInt(count)]
    }

    private fun waveMultiplier(elapsed: Float) = 1f + (elapsed / 120f) * 0.5f

    /**
     * Writes spawn position into spawnX/spawnY instead of returning Pair.
     * Eliminates one Pair allocation per spawn event.
     */
    private fun calcSpawnPosition(px: Float, py: Float) {
        val margin = 80f
        val halfW  = viewportWidth  * 0.5f + margin
        val halfH  = viewportHeight * 0.5f + margin
        when (Random.nextInt(4)) {
            0 -> { spawnX = px + Random.nextFloat()*halfW*2f - halfW; spawnY = py - halfH }
            1 -> { spawnX = px + halfW; spawnY = py + Random.nextFloat()*halfH*2f - halfH }
            2 -> { spawnX = px + Random.nextFloat()*halfW*2f - halfW; spawnY = py + halfH }
            else -> { spawnX = px - halfW; spawnY = py + Random.nextFloat()*halfH*2f - halfH }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun reset() {
        spawnTimer = 0f; bossTimer = 0f; waveTimer = 0f
        currentWave = 0; spawnInterval = 1.2f; maxEnemies = 50
    }

    data class SpawnResult(
        var enemiesSpawned: Int = 0,
        var bossSpawned: Boolean = false,
        var newWave: Int = -1
    )
}
