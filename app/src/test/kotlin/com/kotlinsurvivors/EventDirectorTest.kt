package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.systems.EventDirector
import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ecs.systems.SpawnSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDirectorTest {

    private val world    = World()
    private val pid      = EntityFactory.createPlayer(world, 2000f, 2000f)
    private val director = EventDirector(SpawnSystem(1280f, 720f), 4096f, 1280f, 720f)
    private val events   = mutableListOf<GameEvent>()
    private var clock    = 0f

    /** Runs the director for [seconds] of game time in 1/20 s steps, collecting what it announces. */
    private fun run(seconds: Float) {
        val steps = (seconds * 20).toInt()
        repeat(steps) { clock += 0.05f; director.update(world, 0.05f, clock, events) }
    }

    private fun started() = events.filterIsInstance<GameEvent.EventStarted>().map { it.kind }
    private fun tagged()  = world.enemies.values.filter { it.waveTag != 0 }

    private fun killAll(selector: (com.kotlinsurvivors.engine.ecs.components.EnemyComponent) -> Boolean) {
        for ((id, e) in world.enemies.entries.toList()) if (selector(e)) world.destroyEntity(id)
        world.flushDestroyed()
    }

    @Test fun nothingHappensBeforeTheFirstWave() {
        run(EventDirector.FIRST_WAVE_AT - 1f)
        assertTrue(started().isEmpty())
    }

    @Test fun aWaveStartsOnTimeAndPutsTaggedEnemiesAroundThePlayer() {
        run(EventDirector.FIRST_WAVE_AT + 10f)
        val first = started().first { it != EventKind.MINI_BOSS }
        assertEquals(EventKind.K9_PATROL, first)   // the first special wave of a run is always the K9 patrol
        assertTrue("wave enemies: ${tagged().size}", tagged().isNotEmpty())
    }

    @Test fun aWaveWaitsWhileABossIsAlive() {
        EntityFactory.createEnemy(world, EnemyType.BOSS_GOLEM, 2400f, 2000f)
        run(EventDirector.FIRST_WAVE_AT + 30f)
        assertTrue(started().none { it != EventKind.MINI_BOSS })
        assertTrue(tagged().isEmpty())
    }

    @Test fun enemiesNeverGoAboveTheCeiling() {
        repeat(EventDirector.ENEMY_CEILING - 5) { EntityFactory.createEnemy(world, EnemyType.BASIC, 100f + it, 100f) }
        run(EventDirector.FIRST_WAVE_AT + 20f)
        assertTrue("enemies: ${world.enemies.size}", world.enemies.size <= EventDirector.ENEMY_CEILING + 1)  // +1: a mini-boss may still come
    }

    @Test fun wipingOutAWavePaysAReward() {
        run(EventDirector.FIRST_WAVE_AT + 30f)
        val pickupsBefore = world.pickups.size
        killAll { it.waveTag != 0 }
        run(2f)
        assertTrue(events.any { it is GameEvent.WaveCleared })
        val orbs = world.pickups.values.count { it.type == PickupType.EXPERIENCE_LARGE }
        assertTrue("reward orbs: $orbs", orbs >= EventDirector.REWARD_ORBS)
        assertTrue(world.pickups.size > pickupsBefore)
    }

    @Test fun aWaveThatDragsOnPaysNothing() {
        run(EventDirector.FIRST_WAVE_AT + 15f)               // clock 75: the wave is running
        assertTrue(director.waveActive)
        run(60f)                                             // clock 135: still inside its time limit
        assertTrue(director.waveActive)
        run(11f)                                             // clock 146: expired, and the next wave is not due until 150
        assertFalse(director.waveActive)
        assertTrue(events.none { it is GameEvent.WaveCleared })
    }

    @Test fun theSameKindNeverComesTwiceInARow() {
        var last: EventKind? = null
        repeat(12) {
            run(EventDirector.WAVE_EVERY)
            killAll { true }
            val kinds = started().filter { it != EventKind.MINI_BOSS }
            if (kinds.size >= 2) assertNotEquals(kinds[kinds.size - 2], kinds.last())
            last = kinds.lastOrNull()
        }
        assertTrue(last != null)
    }

    @Test fun aMiniBossComesHalfwayToTheFirstBossAndIsTough() {
        run(EventDirector.FIRST_MINI_BOSS_AT + 2f)
        assertTrue(EventKind.MINI_BOSS in started())
        val mini = world.enemies.entries.single { it.value.isMiniBoss }
        val base = EntityFactory.createEnemy(world, mini.value.type, 0f, 0f)
        assertTrue(world.healths[mini.key]!!.max >= world.healths[base]!!.max * EntityFactory.MINI_BOSS_HP_FACTOR / 2)
        assertTrue(world.colliders[mini.key]!!.radius > world.colliders[base]!!.radius * 1.5f)
        assertEquals(1f, mini.value.coinDropChance, 0f)
    }

    @Test fun aMiniBossSometimesLeavesAChestButOrdinaryEnemiesNever() {
        val xp = ExperienceSystem()
        repeat(400) {
            val id = EntityFactory.createMiniBoss(world, EnemyType.TANK, 100f, 100f)
            world.healths[id]!!.isDead = true
            xp.processEvents(world, mutableListOf(GameEvent.EnemyKilled(id, EnemyType.TANK, 40, 1f, true)))
            world.flushDestroyed()
        }
        val chests = world.pickups.values.count { it.type == PickupType.CHEST }
        assertTrue("chests from 400 mini-bosses: $chests", chests in 90..150)
    }
}
