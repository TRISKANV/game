package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ecs.systems.SpawnSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExperienceSystemTest {

    private lateinit var world: World
    private lateinit var experienceSystem: ExperienceSystem
    private var playerId: Int = -1

    @Before
    fun setup() {
        world            = World()
        experienceSystem = ExperienceSystem()
        playerId         = EntityFactory.createPlayer(world, 500f, 500f)
    }

    @Test
    fun `collecting XP orb increases player experience`() {
        val events = mutableListOf<GameEvent>(
            GameEvent.PickupCollected(99, PickupType.EXPERIENCE_SMALL, 5)
        )
        experienceSystem.processEvents(world, events, dt = 0.016f)
        val player = world.players[playerId]!!
        assertEquals(5, player.experience)
    }

    @Test
    fun `level up triggers when XP threshold reached`() {
        val player = world.players[playerId]!!
        player.experience         = 0
        player.experienceToNextLevel = 10

        val events = mutableListOf<GameEvent>(
            GameEvent.PickupCollected(99, PickupType.EXPERIENCE_MEDIUM, 15)
        )
        val output = experienceSystem.processEvents(world, events, dt = 0.016f)

        assertTrue(output.any { it is GameEvent.LevelUp })
        assertEquals(2, player.level)
    }

    @Test
    fun `collecting coin increases player coins`() {
        val events = mutableListOf<GameEvent>(
            GameEvent.PickupCollected(99, PickupType.COIN, 1)
        )
        experienceSystem.processEvents(world, events, dt = 0.016f)
        val player = world.players[playerId]!!
        assertEquals(1, player.coins)
    }

    @Test
    fun `collecting health pickup heals player`() {
        val health = world.healths[playerId]!!
        health.current = 50

        val events = mutableListOf<GameEvent>(
            GameEvent.PickupCollected(99, PickupType.HEALTH_SMALL, 10)
        )
        experienceSystem.processEvents(world, events, dt = 0.016f)
        assertEquals(60, health.current)
    }

    @Test
    fun `health is capped at max when healed`() {
        val health = world.healths[playerId]!!
        health.current = 95

        val events = mutableListOf<GameEvent>(
            GameEvent.PickupCollected(99, PickupType.HEALTH_LARGE, 40)
        )
        experienceSystem.processEvents(world, events, dt = 0.016f)
        assertEquals(100, health.current)
    }

    @Test
    fun `enemy killed event drops pickup and increments kill count`() {
        val enemyId = EntityFactory.createEnemy(world, EnemyType.BASIC, 100f, 100f)
        val events  = mutableListOf<GameEvent>(
            GameEvent.EnemyKilled(enemyId, EnemyType.BASIC, 5, 0f)
        )

        val beforePickups = world.getPickupEntities().size
        experienceSystem.processEvents(world, events, dt = 0.016f)
        world.flushDestroyed()

        val player = world.players[playerId]!!
        assertEquals(1, player.killCount)
        assertTrue(world.getPickupEntities().size > beforePickups)
    }

    @Test
    fun `enemy killed by non projectile damage is reaped and rewarded`() {
        // Whip, aura, lightning, orbitals, holy water and bombs only flag the
        // enemy as dead and emit no event of their own.
        val enemyId = EntityFactory.createEnemy(world, EnemyType.BASIC, 100f, 100f)
        world.healths[enemyId]!!.isDead = true

        val beforePickups = world.getPickupEntities().size
        val output = experienceSystem.processEvents(world, mutableListOf(), dt = 0.016f)

        assertTrue(output.any { it is GameEvent.EnemyKilled && it.entityId == enemyId })
        assertTrue(world.destroyedTags.contains(enemyId))
        assertEquals(1, world.players[playerId]!!.killCount)
        assertTrue(world.getPickupEntities().size > beforePickups)
    }

    @Test
    fun `enemy already reported as killed is not rewarded twice`() {
        val enemyId = EntityFactory.createEnemy(world, EnemyType.BASIC, 100f, 100f)
        world.healths[enemyId]!!.isDead = true
        val events = mutableListOf<GameEvent>(
            GameEvent.EnemyKilled(enemyId, EnemyType.BASIC, 5, 0f)
        )

        val output = experienceSystem.processEvents(world, events, dt = 0.016f)

        assertEquals(1, output.count { it is GameEvent.EnemyKilled })
        assertEquals(1, world.players[playerId]!!.killCount)
    }

    @Test
    fun `living enemies are left alone`() {
        val enemyId = EntityFactory.createEnemy(world, EnemyType.BASIC, 100f, 100f)

        val output = experienceSystem.processEvents(world, mutableListOf(), dt = 0.016f)

        assertTrue(output.none { it is GameEvent.EnemyKilled })
        assertFalse(world.destroyedTags.contains(enemyId))
        assertEquals(0, world.players[playerId]!!.killCount)
    }
}

class SpawnSystemTest {

    private lateinit var world: World
    private lateinit var spawnSystem: SpawnSystem

    @Before
    fun setup() {
        world       = World()
        spawnSystem = SpawnSystem(1280f, 720f)
        EntityFactory.createPlayer(world, 500f, 400f)
    }

    @Test
    fun `no enemies spawned before first interval`() {
        val result = spawnSystem.update(world, 0.1f, 0.1f)
        assertEquals(0, result.enemiesSpawned)
    }

    @Test
    fun `enemies spawn after interval elapses`() {
        var totalSpawned = 0
        repeat(20) {
            val r = spawnSystem.update(world, 0.1f, it * 0.1f)
            totalSpawned += r.enemiesSpawned
        }
        assertTrue(totalSpawned > 0)
    }

    @Test
    fun `reset clears spawn timers`() {
        repeat(15) { spawnSystem.update(world, 0.1f, it * 0.1f) }
        spawnSystem.reset()
        val result = spawnSystem.update(world, 0.01f, 0f)
        assertEquals(0, result.enemiesSpawned)
    }
}
