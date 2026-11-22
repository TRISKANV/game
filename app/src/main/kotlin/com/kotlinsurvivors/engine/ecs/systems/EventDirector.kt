package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the on-screen announcement is about. */
enum class EventKind { SWARM_RUSH, DRONE_RAID, SIEGE, MINI_BOSS, WAVE_CLEARED }

/**
 * Decides when something out of the ordinary happens during a run:
 *
 *  - Every [WAVE_EVERY] seconds a special wave comes (a swarm, a raid of drones or a ring of
 *    enemies closing in). Wipe it out before [WAVE_TIME_LIMIT] and it pays a bonus.
 *  - Every [MINI_BOSS_EVERY] seconds, halfway between two bosses, a mini-boss shows up.
 *
 * Nothing special starts while a boss is alive, and no event pushes the number of enemies
 * above [ENEMY_CEILING], so the game keeps its frame rate.
 */
class EventDirector(
    private val spawner  : SpawnSystem,
    private val worldSize: Float,
    private val viewportWidth : Float,
    private val viewportHeight: Float
) {
    companion object {
        const val FIRST_WAVE_AT      = 60f
        const val WAVE_EVERY         = 90f
        const val FIRST_MINI_BOSS_AT = 90f
        const val MINI_BOSS_EVERY    = 180f
        /** Enemies alive at once that no event goes over. */
        const val ENEMY_CEILING      = 160
        /** Seconds, after the last enemy of a wave appeared, to wipe it out for the reward. */
        const val WAVE_TIME_LIMIT    = 75f
        const val RING_COUNT         = 20
        const val RING_TANKS         = 3
        const val SWARM_COUNT        = 36
        const val SWARM_PER_SECOND   = 6f
        const val DRONE_COUNT        = 14
        const val DRONE_PER_SECOND   = 3f
        const val REWARD_ORBS        = 2
        private const val CLEAR_CHECK_EVERY = 0.5f
        private const val SPAWN_CLEARANCE   = 20f
    }

    private var nextWaveAt     = FIRST_WAVE_AT
    private var nextMiniBossAt = FIRST_MINI_BOSS_AT
    private var lastKind: EventKind? = null

    private var tagCounter = 0
    private var activeTag  = 0           // 0 = no wave running
    private var activeKind = EventKind.SWARM_RUSH
    private var toSpawn    = 0           // enemies of the active wave still to appear
    private var spawnTimer = 0f
    private var spawnGap   = 0.2f
    private var waveClock  = 0f          // seconds since the last enemy of the wave appeared
    private var checkTimer = 0f

    /** False in the laboratory: nothing starts by itself there, only what the tester asks for. */
    var autoStart = true

    /** True while a special wave is running. */
    val waveActive: Boolean get() = activeTag != 0

    fun reset() {
        nextWaveAt = FIRST_WAVE_AT; nextMiniBossAt = FIRST_MINI_BOSS_AT; lastKind = null
        endWave()
    }

    fun update(world: World, dt: Float, elapsed: Float, events: MutableList<GameEvent>) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return
        val pt = world.transforms[pid] ?: return

        if (autoStart && activeTag == 0 && elapsed >= nextWaveAt && world.bosses.isEmpty()) {
            startWave(world, pt.x, pt.y, elapsed, events)
        } else if (activeTag != 0) {
            updateWave(world, dt, pt.x, pt.y, elapsed, events)
        }

        if (autoStart && elapsed >= nextMiniBossAt && world.bosses.isEmpty() && world.enemies.size < ENEMY_CEILING &&
            spawner.findSpawnPoint(pt.x, pt.y)) {
            val type = if (Random.nextBoolean()) EnemyType.TANK else EnemyType.BASIC
            EntityFactory.createMiniBoss(world, type, spawner.spawnPointX, spawner.spawnPointY, spawner.difficultyAt(elapsed))
            nextMiniBossAt += MINI_BOSS_EVERY
            events.add(GameEvent.EventStarted(EventKind.MINI_BOSS))
        }
    }

    // ── Special waves ──────────────────────────────────────────────────────

    private fun startWave(world: World, px: Float, py: Float, elapsed: Float, events: MutableList<GameEvent>) {
        val kinds = ArrayList<EventKind>(3)
        kinds.add(EventKind.SWARM_RUSH)
        if (elapsed >= 60f)  kinds.add(EventKind.DRONE_RAID)
        if (elapsed >= 120f) kinds.add(EventKind.SIEGE)
        if (kinds.size > 1) lastKind?.let { kinds.remove(it) }   // never the same twice in a row
        nextWaveAt += WAVE_EVERY
        launchWave(world, kinds[Random.nextInt(kinds.size)], px, py, elapsed, events)
    }

    /**
     * Starts [kind] right now, whatever the clock says (laboratory). A running wave is dropped first.
     * A mini-boss is not a wave: it just appears. Returns false when there was no room for it.
     */
    fun force(world: World, kind: EventKind, px: Float, py: Float, elapsed: Float, events: MutableList<GameEvent>): Boolean {
        when (kind) {
            EventKind.MINI_BOSS -> {
                if (!spawner.findSpawnPoint(px, py)) return false
                EntityFactory.createMiniBoss(world, EnemyType.TANK, spawner.spawnPointX, spawner.spawnPointY, spawner.difficultyAt(elapsed))
                events.add(GameEvent.EventStarted(kind))
            }
            EventKind.WAVE_CLEARED -> {
                giveReward(world, px, py)
                events.add(GameEvent.WaveCleared)
            }
            else -> { endWave(); launchWave(world, kind, px, py, elapsed, events) }
        }
        return true
    }

    private fun launchWave(world: World, kind: EventKind, px: Float, py: Float, elapsed: Float, events: MutableList<GameEvent>) {
        lastKind   = kind
        activeKind = kind
        activeTag  = ++tagCounter
        waveClock  = 0f
        checkTimer = 0f
        when (kind) {
            EventKind.SWARM_RUSH -> { toSpawn = SWARM_COUNT; spawnGap = 1f / SWARM_PER_SECOND }
            EventKind.DRONE_RAID -> { toSpawn = DRONE_COUNT; spawnGap = 1f / DRONE_PER_SECOND }
            else                 -> { toSpawn = 0; spawnRing(world, px, py, elapsed) }
        }
        spawnTimer = 0f
        events.add(GameEvent.EventStarted(kind))
    }

    private fun updateWave(world: World, dt: Float, px: Float, py: Float, elapsed: Float, events: MutableList<GameEvent>) {
        if (toSpawn > 0) {
            spawnTimer -= dt
            while (spawnTimer <= 0f && toSpawn > 0) {
                spawnTimer += spawnGap
                if (world.enemies.size >= ENEMY_CEILING) { toSpawn = 0; break }   // no more room: the wave stays smaller
                if (!spawner.findSpawnPoint(px, py)) { spawnTimer = 0.1f; break }  // try again shortly
                val type = if (activeKind == EventKind.DRONE_RAID) EnemyType.FAST else EnemyType.SWARM
                spawnTagged(world, type, spawner.spawnPointX, spawner.spawnPointY, elapsed)
                toSpawn--
            }
            return
        }

        waveClock += dt
        checkTimer -= dt
        if (checkTimer > 0f) return
        checkTimer = CLEAR_CHECK_EVERY
        var left = 0
        for (enemy in world.enemies.values) if (enemy.waveTag == activeTag) left++
        if (left == 0) {
            giveReward(world, px, py)
            events.add(GameEvent.WaveCleared)
            endWave()
        } else if (waveClock >= WAVE_TIME_LIMIT) {
            endWave()                                          // too slow: no reward, the rest stay as normal enemies
        }
    }

    /** The ring: enemies all around, just outside the screen, all coming in at once. */
    private fun spawnRing(world: World, px: Float, py: Float, elapsed: Float) {
        val radius = sqrt(viewportWidth * viewportWidth + viewportHeight * viewportHeight) * 0.5f + 60f
        val room   = (ENEMY_CEILING - world.enemies.size).coerceAtLeast(0)
        val count  = RING_COUNT.coerceAtMost(room)
        val map    = spawner.levelMap
        val limit  = map?.worldSize ?: worldSize
        val start  = Random.nextFloat() * 2f * PI.toFloat()
        var placed = 0
        for (k in 0 until count) {
            val angle = start + k * 2f * PI.toFloat() / count
            val x = px + cos(angle) * radius
            val y = py + sin(angle) * radius
            if (x < SPAWN_CLEARANCE || y < SPAWN_CLEARANCE || x > limit - SPAWN_CLEARANCE || y > limit - SPAWN_CLEARANCE) continue
            if (map != null && !map.isBoxFree(x, y, SPAWN_CLEARANCE, SPAWN_CLEARANCE)) continue
            val type = if (placed < RING_TANKS) EnemyType.TANK else EnemyType.BASIC
            spawnTagged(world, type, x, y, elapsed)
            placed++
        }
    }

    private fun spawnTagged(world: World, type: EnemyType, x: Float, y: Float, elapsed: Float) {
        val id = EntityFactory.createEnemy(world, type, x, y, spawner.difficultyAt(elapsed))
        world.enemies[id]?.waveTag = activeTag
    }

    private fun giveReward(world: World, px: Float, py: Float) {
        repeat(REWARD_ORBS) { EntityFactory.createPickup(world, PickupType.EXPERIENCE_LARGE, px + Random.nextFloat() * 120f - 60f, py + Random.nextFloat() * 120f - 60f) }
        EntityFactory.createPickup(world, PickupType.COIN_BAG, px + Random.nextFloat() * 120f - 60f, py + Random.nextFloat() * 120f - 60f)
    }

    private fun endWave() {
        activeTag = 0; toSpawn = 0; waveClock = 0f
    }
}
