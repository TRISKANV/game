package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.components.*
import com.kotlinsurvivors.engine.spatial.SpatialGrid
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorldTest {

    private lateinit var world: World

    @Before
    fun setup() {
        world = World()
    }

    @Test
    fun `createEntity returns unique IDs`() {
        val id1 = world.createEntity()
        val id2 = world.createEntity()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `destroyEntity removes entity after flush`() {
        val id = world.createEntity()
        world.transforms[id] = TransformComponent(10f, 10f)
        assertTrue(world.isAlive(id))

        world.destroyEntity(id)
        world.flushDestroyed()

        assertFalse(world.isAlive(id))
        assertNull(world.transforms[id])
    }

    @Test
    fun `recycled entity IDs are reused`() {
        val id1 = world.createEntity()
        world.destroyEntity(id1)
        world.flushDestroyed()

        val id2 = world.createEntity()
        assertEquals(id1, id2) // ID should be recycled
    }

    @Test
    fun `player entity is accessible via getPlayerEntity`() {
        assertEquals(-1, world.getPlayerEntity())
        val pid = EntityFactory.createPlayer(world, 100f, 100f)
        assertEquals(pid, world.getPlayerEntity())
    }

    @Test
    fun `clear removes all entities and components`() {
        EntityFactory.createPlayer(world, 0f, 0f)
        EntityFactory.createEnemy(world, EnemyType.BASIC, 200f, 200f)
        world.clear()

        assertEquals(0, world.getLiveEntityCount())
        assertTrue(world.transforms.isEmpty())
        assertTrue(world.players.isEmpty())
        assertTrue(world.enemies.isEmpty())
    }

    @Test
    fun `enemy entities are tracked correctly`() {
        val e1 = EntityFactory.createEnemy(world, EnemyType.BASIC, 100f, 100f)
        val e2 = EntityFactory.createEnemy(world, EnemyType.FAST,  200f, 200f)
        assertEquals(2, world.getEnemyEntities().size)
        assertTrue(world.getEnemyEntities().contains(e1))
        assertTrue(world.getEnemyEntities().contains(e2))
    }
}

class SpatialGridTest {

    private lateinit var grid: SpatialGrid

    @Before
    fun setup() {
        grid = SpatialGrid(cellSize = 100f)
    }

    @Test
    fun `insert and query single entity`() {
        grid.insert(1, 50f, 50f, 10f)
        val result = mutableListOf<Int>()
        grid.query(50f, 50f, 20f, result)
        assertTrue(result.contains(1))
    }

    @Test
    fun `query returns empty when no entities`() {
        val result = mutableListOf<Int>()
        grid.query(50f, 50f, 10f, result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `entities in different cells not returned for distant query`() {
        grid.insert(1, 50f,  50f, 5f)  // cell (0,0)
        grid.insert(2, 500f, 500f, 5f) // cell (5,5)
        val result = mutableListOf<Int>()
        grid.query(50f, 50f, 10f, result)
        assertTrue(result.contains(1))
        assertFalse(result.contains(2))
    }

    @Test
    fun `clear removes all entries`() {
        grid.insert(1, 50f, 50f, 10f)
        grid.clear()
        val result = mutableListOf<Int>()
        grid.query(50f, 50f, 20f, result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `large entity spans multiple cells`() {
        // Entity with radius 150 at position (100, 100) spans cells 0-2 in each axis
        grid.insert(1, 100f, 100f, 150f)
        val result = mutableListOf<Int>()
        grid.query(250f, 250f, 20f, result)
        // Should find entity because it spans into that area
        assertTrue(result.contains(1))
    }
}

class EntityFactoryTest {

    private lateinit var world: World

    @Before
    fun setup() {
        world = World()
    }

    @Test
    fun `createPlayer initializes all required components`() {
        val pid = EntityFactory.createPlayer(world, 500f, 500f)
        assertNotNull(world.transforms[pid])
        assertNotNull(world.velocities[pid])
        assertNotNull(world.healths[pid])
        assertNotNull(world.colliders[pid])
        assertNotNull(world.renders[pid])
        assertNotNull(world.players[pid])
        assertNotNull(world.weapons[pid])
        assertTrue(world.playerTags.contains(pid))
    }

    @Test
    fun `createPlayer sets correct initial health`() {
        val pid = EntityFactory.createPlayer(world, 0f, 0f)
        val health = world.healths[pid]!!
        assertEquals(100, health.current)
        assertEquals(100, health.max)
        assertFalse(health.isDead)
    }

    @Test
    fun `createPlayer starts with magic wand`() {
        val pid = EntityFactory.createPlayer(world, 0f, 0f)
        val weapons = world.weapons[pid]!!
        assertEquals(1, weapons.size)
        assertEquals(WeaponType.MAGIC_WAND, weapons[0].type)
    }

    @Test
    fun `createEnemy respects type configuration`() {
        val tankId = EntityFactory.createEnemy(world, EnemyType.TANK, 200f, 200f)
        val swarmId = EntityFactory.createEnemy(world, EnemyType.SWARM, 300f, 300f)

        val tankHealth  = world.healths[tankId]!!
        val swarmHealth = world.healths[swarmId]!!

        // Tank should have more HP than swarm
        assertTrue(tankHealth.max > swarmHealth.max)
    }

    @Test
    fun `createPickup sets correct component values`() {
        val pkId = EntityFactory.createPickup(world, PickupType.EXPERIENCE_SMALL, 100f, 100f)
        val pickup = world.pickups[pkId]!!
        assertEquals(PickupType.EXPERIENCE_SMALL, pickup.type)
        assertEquals(5, pickup.value)
        assertTrue(pickup.lifetime > 0f)
    }

    @Test
    fun `createProjectile initializes velocity correctly`() {
        val projId = EntityFactory.createProjectile(
            world, 0f, 0f, 100f, 200f,
            WeaponType.MAGIC_WAND, 10, 12f, 1, 2f
        )
        val vel = world.velocities[projId]!!
        assertEquals(100f, vel.vx, 0.001f)
        assertEquals(200f, vel.vy, 0.001f)
    }
}
