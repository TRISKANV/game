package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.ecs.systems.SpawnSystem
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.LevelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Level 1: the files match what the code expects, the grid is playable, and bodies obey it. */
class Level1MapTest {

    private val dir = File("src/main/assets/sprites")
    private val map: LevelMap by lazy {
        val file = File(dir, "level1_solid.bin")
        assertTrue("Missing level1_solid.bin", file.isFile)
        LevelMap(Level1.CELLS, Level1.CELL_SIZE, file.readBytes())
    }

    private val playerHalf = MovementSystem.PLAYER_HALF
    private val feet = MovementSystem.PLAYER_FEET_OFFSET

    @Test
    fun levelFilesMatchTheMetadata() {
        assertEquals(Level1.WORLD_SIZE, Level1.CELLS * Level1.CELL_SIZE, 0f)
        assertEquals(Level1.CELLS * Level1.CELLS, File(dir, "level1_solid.bin").length().toInt())
        val picture = ImageIO.read(File(dir, "level1.png"))
        assertEquals((Level1.WORLD_SIZE / Level1.IMAGE_SCALE).toInt(), picture.width)
        assertEquals(picture.width, picture.height)
        val glow = ImageIO.read(File(dir, "glow.png"))
        assertEquals(glow.width, glow.height)
        assertEquals("lamps are x,y pairs", 0, Level1.LAMPS.size % 2)
        assertTrue(Level1.LAMPS.isNotEmpty())
    }

    @Test
    fun theWholeBorderIsSolid() {
        val n = Level1.CELLS
        for (i in 0 until n) {
            assertTrue(map.isBlockedCell(i, 0)); assertTrue(map.isBlockedCell(i, n - 1))
            assertTrue(map.isBlockedCell(0, i)); assertTrue(map.isBlockedCell(n - 1, i))
        }
        assertTrue("outside the grid is blocked", map.isBlockedCell(-1, 5))
        assertTrue(map.isBlockedAt(-10f, 100f))
    }

    @Test
    fun playerStartsOnFreeGroundJustBelowTheDoor() {
        assertTrue(map.isBoxFree(Level1.SPAWN_X, Level1.SPAWN_Y + feet, playerHalf, playerHalf))
        assertTrue("below the door", Level1.SPAWN_Y > Level1.DOOR_Y)
        assertTrue(Level1.SPAWN_X in Level1.DOOR_LEFT..Level1.DOOR_RIGHT)
        assertTrue("near the north border", Level1.SPAWN_Y < Level1.WORLD_SIZE * 0.25f)
    }

    @Test
    fun mostOfTheFreeGroundCanBeReachedFromTheDoor() {
        // Flood fill over cells where the player's box (with margin) fits.
        val n = Level1.CELLS
        val c = Level1.CELL_SIZE
        fun fits(cx: Int, cy: Int) = map.isBoxFree((cx + 0.5f) * c, (cy + 0.5f) * c, playerHalf, playerHalf) // feet position
        var free = 0
        for (cy in 0 until n) for (cx in 0 until n) if (fits(cx, cy)) free++
        assertTrue("map has walkable space", free > n * n / 4)

        val seen = BooleanArray(n * n)
        val queue = ArrayDeque<Int>()
        val sx = (Level1.SPAWN_X / c).toInt(); val sy = (Level1.SPAWN_Y / c).toInt()
        assertTrue(fits(sx, sy))
        seen[sy * n + sx] = true; queue.add(sy * n + sx)
        var reached = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst(); reached++
            val cx = cur % n; val cy = cur / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = cx + dx; val ny = cy + dy
                if (nx !in 0 until n || ny !in 0 until n || seen[ny * n + nx] || !fits(nx, ny)) continue
                seen[ny * n + nx] = true; queue.add(ny * n + nx)
            }
        }
        val share = reached.toFloat() / free
        assertTrue("only ${(share * 100).toInt()}% of the walkable ground is reachable", share >= 0.95f)
    }

    @Test
    fun boxesRespectSolidCellsAndSlideAlongWalls() {
        val grid = ByteArray(8 * 8)
        for (x in 0 until 8) { grid[x] = 1; grid[7 * 8 + x] = 1 }
        for (y in 0 until 8) { grid[y * 8] = 1; grid[y * 8 + 7] = 1 }
        val small = LevelMap(8, 16f, grid)
        assertTrue(small.isBoxFree(64f, 64f, 10f, 10f))
        assertFalse("overlaps the left wall cell", small.isBoxFree(20f, 64f, 10f, 10f))
        assertTrue(small.canMove(64f, 64f, 70f, 64f, 10f, 10f))
        assertFalse(small.canMove(30f, 64f, 20f, 64f, 10f, 10f))
        // Stuck inside the wall: may move out, may not go deeper.
        assertTrue(small.canMove(8f, 64f, 40f, 64f, 10f, 10f))
        assertFalse(small.canMove(24f, 64f, 8f, 64f, 10f, 10f))
    }

    /** First free spot found scanning right and down from the given point, for tests that need open ground. */
    private fun freeSpotFrom(x: Float, y: Float, half: Float): Pair<Float, Float> {
        var py = y
        while (py < Level1.WORLD_SIZE) {
            var px = x
            while (px < Level1.WORLD_SIZE) {
                if (map.isBoxFree(px, py, half + 8f, half + 8f)) return px to py
                px += Level1.CELL_SIZE
            }
            py += Level1.CELL_SIZE
        }
        error("no free ground")
    }

    @Test
    fun playerCannotWalkIntoTheHouseWallAndSlidesAlongIt() {
        val world = World()
        val pid = EntityFactory.createPlayer(world, Level1.SPAWN_X, Level1.SPAWN_Y)
        val movement = MovementSystem(Level1.WORLD_SIZE, Level1.WORLD_SIZE).also { it.levelMap = map }
        // Up and to the left, hard: the house wall stops the player and the left part slides.
        repeat(300) { movement.update(world, 1f / 60f, JoystickState(true, -0.7f, -0.7f)) }
        val t = world.transforms[pid]!!
        assertTrue("player's feet ended in a wall at (${t.x}, ${t.y})", map.isBoxFree(t.x, t.y + feet, playerHalf, playerHalf))
        assertTrue("feet walked through the house", t.y + feet > Level1.DOOR_Y)
        assertTrue("did not slide along the wall", t.x < Level1.SPAWN_X - 40f)
    }

    @Test
    fun enemiesDoNotEnterBlockedCells() {
        val world = World()
        // The player stands inside the north buildings, where nothing can follow.
        EntityFactory.createPlayer(world, 200f, 200f)
        val movement = MovementSystem(Level1.WORLD_SIZE, Level1.WORLD_SIZE).also { it.levelMap = map }
        val (x, y) = freeSpotFrom(600f, 700f, 14f)
        val eid = EntityFactory.createEnemy(world, EnemyType.FAST, x, y)
        repeat(600) { movement.update(world, 1f / 60f, JoystickState()) }
        val t = world.transforms[eid]!!
        val feetY = t.y + world.colliders[eid]!!.radius * MovementSystem.ENEMY_FEET_FACTOR
        assertFalse("enemy's feet ended inside a wall at (${t.x}, $feetY)", map.isBlockedAt(t.x, feetY))
    }

    @Test
    fun spawnsOnlyHappenOnFreeGround() {
        val world = World()
        EntityFactory.createPlayer(world, Level1.SPAWN_X, Level1.SPAWN_Y)
        val spawn = SpawnSystem(2400f, 1080f).also { it.levelMap = map }
        var created = 0
        // 5 minutes of play at 60 fps; enemies are not moved, so every one is where it spawned.
        repeat(60 * 300) { frame ->
            created += spawn.update(world, 1f / 60f, frame / 60f).enemiesSpawned
        }
        assertTrue("nothing spawned next to the north border", created > 20)
        for (id in world.getEnemySnapshot()) {
            val t = world.transforms[id]!!
            assertTrue("enemy $id spawned in a wall at (${t.x}, ${t.y})", map.isBoxFree(t.x, t.y, 20f, 20f))
        }
    }

    // ── projectiles and path finding ─────────────────────────────────────────

    /** 64 x 64 cells: solid border, and a vertical wall at column 32 with a gap at the bottom. */
    private fun wallWithGap(): LevelMap {
        val n = 64
        val grid = ByteArray(n * n)
        for (i in 0 until n) { grid[i] = 1; grid[(n - 1) * n + i] = 1; grid[i * n] = 1; grid[i * n + n - 1] = 1 }
        for (y in 5..50) { grid[y * n + 32] = 1; grid[y * n + 33] = 1 }
        return LevelMap(n, 16f, grid)
    }

    @Test
    fun shotsAreLostAgainstWallsButFlyOverOpenGround() {
        val small = wallWithGap()
        val world = World()
        EntityFactory.createPlayer(world, 100f, 100f)
        val movement = MovementSystem(1024f, 1024f).also { it.levelMap = small }
        val wall = EntityFactory.createProjectile(
            world, 400f, 300f, 300f, 0f, com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND, 5, 6f, 1, 5f)
        val open = EntityFactory.createProjectile(
            world, 100f, 700f, 300f, 0f, com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND, 5, 6f, 1, 5f)
        repeat(60) {                                                       // one second: 300 px
            movement.update(world, 1f / 60f, JoystickState())
            world.flushDestroyed()
        }
        assertFalse("the shot went through the wall", world.projectiles.containsKey(wall))
        assertTrue(world.projectiles.containsKey(open))
        assertTrue(world.transforms[open]!!.x > 350f)
    }

    @Test
    fun enemyGoesRoundAWallToReachThePlayer() {
        val small = wallWithGap()
        val world = World()
        val pid = EntityFactory.createPlayer(world, 700f, 300f)
        val movement = MovementSystem(1024f, 1024f).also { it.levelMap = small }
        val eid = EntityFactory.createEnemy(world, EnemyType.FAST, 300f, 300f)
        repeat(60 * 40) { movement.update(world, 1f / 60f, JoystickState()) }
        val p = world.transforms[pid]!!; val e = world.transforms[eid]!!
        val dist = kotlin.math.hypot(p.x - e.x, p.y - e.y)
        assertTrue("enemy is still stuck at (${e.x}, ${e.y}), ${dist.toInt()} px away", dist < 50f)
    }

    @Test
    fun enemyFindsItsWayAcrossTheRealMap() {
        val world = World()
        val pid = EntityFactory.createPlayer(world, Level1.SPAWN_X, Level1.SPAWN_Y)
        val movement = MovementSystem(Level1.WORLD_SIZE, Level1.WORLD_SIZE).also { it.levelMap = map }
        // Start in the park, far to the south-west of the door, on the other side of buildings and the pond.
        val (x, y) = freeSpotFrom(500f, 2300f, 14f)
        val eid = EntityFactory.createEnemy(world, EnemyType.FAST, x, y)
        repeat(60 * 120) { movement.update(world, 1f / 60f, JoystickState()) }
        val p = world.transforms[pid]!!; val e = world.transforms[eid]!!
        val dist = kotlin.math.hypot(p.x - e.x, p.y - e.y)
        assertTrue("enemy from ($x, $y) is stuck at (${e.x}, ${e.y}), ${dist.toInt()} px away", dist < 60f)
    }

    @Test
    fun aShotIsUsedUpByTheFirstEnemyEvenWithPiercing() {
        val world = World()
        EntityFactory.createPlayer(world, 100f, 100f)
        val a = EntityFactory.createEnemy(world, EnemyType.TANK, 500f, 500f)
        val b = EntityFactory.createEnemy(world, EnemyType.TANK, 505f, 500f)
        val shot = EntityFactory.createProjectile(
            world, 500f, 500f, 0f, 0f, com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND, 5, 8f, 4, 5f)
        com.kotlinsurvivors.engine.ecs.systems.CollisionSystem().update(world, 1f / 60f, mutableListOf())
        world.flushDestroyed()
        assertFalse("the shot flew on after hitting", world.projectiles.containsKey(shot))
        val hurt = listOf(a, b).count { world.healths[it]!!.current < world.healths[it]!!.max }
        assertEquals("exactly one enemy takes the hit", 1, hurt)
    }

    @Test
    fun enemyPushedIntoAnObstacleIsPutBackOnFreeGround() {
        val small = wallWithGap()
        val world = World()
        EntityFactory.createPlayer(world, 700f, 300f)
        val movement = MovementSystem(1024f, 1024f).also { it.levelMap = small }
        val eid = EntityFactory.createEnemy(world, EnemyType.TANK, 520f, 300f)   // inside the wall
        movement.update(world, 1f / 60f, JoystickState())
        val t = world.transforms[eid]!!
        val feet = world.colliders[eid]!!.radius * MovementSystem.ENEMY_FEET_FACTOR
        assertFalse("still inside the wall at (${t.x}, ${t.y})", small.isBlockedAt(t.x, t.y + feet))
    }

    @Test
    fun enemiesNeverSpawnWhereThePlayerCanSeeThem() {
        val world = World()
        EntityFactory.createPlayer(world, Level1.SPAWN_X, Level1.SPAWN_Y)
        val vw = 2400f; val vh = 1080f
        val spawn = SpawnSystem(vw, vh).also { it.levelMap = map }
        // The camera stops at the north edge, so the player sits high on the screen.
        val camX = (Level1.SPAWN_X - vw / 2).coerceIn(0f, Level1.WORLD_SIZE - vw)
        val camY = (Level1.SPAWN_Y - vh / 2).coerceIn(0f, Level1.WORLD_SIZE - vh)
        repeat(60 * 300) { frame -> spawn.update(world, 1f / 60f, frame / 60f) }
        assertTrue(world.getEnemySnapshot().isNotEmpty())
        for (id in world.getEnemySnapshot()) {
            val t = world.transforms[id]!!
            val visible = t.x > camX && t.x < camX + vw && t.y > camY && t.y < camY + vh
            assertFalse("enemy $id spawned on screen at (${t.x}, ${t.y})", visible)
        }
    }
}
