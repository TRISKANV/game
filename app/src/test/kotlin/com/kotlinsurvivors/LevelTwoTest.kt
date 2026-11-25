package com.kotlinsurvivors

import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.systems.EventDirector
import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level2
import com.kotlinsurvivors.engine.map.LevelFlow
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.engine.map.Levels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The way out of Level 1, the trip to Level 2, and the files and rules of Level 2 itself. */
class LevelTwoTest {

    private val dir = File("src/main/assets/sprites")
    private fun levelOne() = LevelMap(Level1.CELLS, Level1.CELL_SIZE, File(dir, "level1_solid.bin").readBytes())
    private fun levelTwo() = LevelMap(Level2.CELLS, Level2.CELL_SIZE, File(dir, "level2_solid.bin").readBytes())

    // ── LevelFlow ────────────────────────────────────────────────────────────

    private val inExitX = (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f
    private val inExitY = Level1.EXIT_TRIGGER_Y + 10f

    @Test fun theExitDoesNothingWhileItIsShut() {
        val flow = LevelFlow()
        repeat(120) { assertFalse(flow.update(1f / 60f, inExitX, inExitY)) }
        assertEquals(1, flow.level)
        assertFalse(flow.isTravelling)
    }

    @Test fun theExitOpensOnlyOnce() {
        val flow = LevelFlow()
        assertTrue(flow.openExit())
        assertFalse(flow.openExit())
        assertTrue(flow.exitOpen)
    }

    @Test fun walkingIntoTheOpenExitFadesOutSwapsOnceAndFadesIn() {
        val flow = LevelFlow()
        flow.openExit()
        var swaps = 0
        var peak = 0f
        var steps = 0
        while ((flow.level == 1 || flow.isTravelling || steps == 0) && steps < 600) {
            if (flow.update(1f / 60f, inExitX, inExitY)) swaps++
            peak = maxOf(peak, flow.fade)
            steps++
        }
        assertEquals(1, swaps)
        assertEquals(2, flow.level)
        assertEquals(1f, peak, 0f)
        assertEquals(0f, flow.fade, 0f)
        assertFalse("the new level has no exit of its own yet", flow.exitOpen)
    }

    @Test fun standingOutsideTheExitAreaDoesNotStartTheTrip() {
        val flow = LevelFlow()
        flow.openExit()
        assertFalse(flow.update(1f / 60f, Level1.EXIT_X0 - 50f, inExitY))
        assertFalse(flow.update(1f / 60f, inExitX, Level1.EXIT_TRIGGER_Y - 50f))
        assertFalse(flow.isTravelling)
    }

    @Test fun resetPutsTheRunBackInLevelOne() {
        val flow = LevelFlow()
        flow.openExit(); flow.startTrip()
        repeat(200) { flow.update(1f / 60f, 0f, 0f) }
        assertEquals(2, flow.level)
        flow.reset()
        assertEquals(1, flow.level)
        assertFalse(flow.exitOpen)
        assertEquals(0f, flow.fade, 0f)
    }

    // ── The gate in the collision grid ───────────────────────────────────────

    @Test fun theGateAtTheEndOfTheStreetCanBeShutAndOpened() {
        val map = levelOne()
        val x = (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f
        val y = (Level1.BARRIER_Y0 + Level1.BARRIER_Y1) / 2f
        assertFalse("the picture has no barrier in the grid by itself", map.isBlockedAt(x, y))
        map.setRect(Level1.EXIT_X0, Level1.BARRIER_Y0, Level1.EXIT_X1, Level1.BARRIER_Y1, true)
        for (px in listOf(Level1.EXIT_X0 + 4f, x, Level1.EXIT_X1 - 4f)) assertTrue(map.isBlockedAt(px, y))
        assertTrue("no way round the barrier along the street",
            !map.isBoxFree(x, y, MovementSystem.PLAYER_HALF, MovementSystem.PLAYER_HALF))
        map.setRect(Level1.EXIT_X0, Level1.BARRIER_Y0, Level1.EXIT_X1, Level1.BARRIER_Y1, false)
        assertFalse(map.isBlockedAt(x, y))
    }

    @Test fun theStreetReachesTheExitFromTheStart() {
        val map = levelOne()
        val steps = 24
        // walk the street: go down the road column from the start region to the exit trigger line on free cells
        val x = (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f
        var y = Level1.EXIT_TRIGGER_Y - 200f
        val half = MovementSystem.PLAYER_HALF
        repeat(steps) {
            assertTrue("blocked at y=$y", map.isBoxFree(x, y, half, half))
            y += 200f / steps
        }
    }

    // ── Level 2 files ────────────────────────────────────────────────────────

    @Test fun levelTwoFilesMatchTheMetadata() {
        assertEquals(Level2.WORLD_SIZE, Level2.CELLS * Level2.CELL_SIZE, 0f)
        assertEquals(Level2.CELLS * Level2.CELLS, File(dir, "level2_solid.bin").length().toInt())
        val picture = ImageIO.read(File(dir, "level2.png"))
        assertEquals((Level2.WORLD_SIZE / Level2.IMAGE_SCALE).toInt(), picture.width)
        assertEquals(picture.width, picture.height)
        assertEquals(0, Level2.LAMPS.size % 2)
        assertTrue(Level2.LAMPS.isNotEmpty())
        val barrier = ImageIO.read(File(dir, "level1_exit_barrier.png"))
        assertEquals(((Level1.EXIT_X1 - Level1.EXIT_X0) / Level1.IMAGE_SCALE).toInt(), barrier.width)
    }

    @Test fun levelTwoBorderIsSolidAndTheStartIsFree() {
        val map = levelTwo()
        val n = Level2.CELLS
        for (i in 0 until n) {
            assertTrue(map.isBlockedCell(i, 0)); assertTrue(map.isBlockedCell(i, n - 1))
            assertTrue(map.isBlockedCell(0, i)); assertTrue(map.isBlockedCell(n - 1, i))
        }
        val half = MovementSystem.PLAYER_HALF
        assertTrue(map.isBoxFree(Level2.SPAWN_X, Level2.SPAWN_Y + MovementSystem.PLAYER_FEET_OFFSET, half, half))
    }

    @Test fun thePoliceStationAndTheFountainAreSolidButTheForecourtIsNot() {
        val map = levelTwo()
        val doorX = (Level2.STATION_DOOR_LEFT + Level2.STATION_DOOR_RIGHT) / 2f
        assertTrue("facade", map.isBlockedAt(doorX, Level2.STATION_DOOR_Y - 40f))
        assertTrue("roof", map.isBlockedAt(doorX, 300f))
        assertTrue("fountain", map.isBlockedAt(40f * 32f, 58f * 32f))
        assertFalse("in front of the door", map.isBlockedAt(doorX, Level2.STATION_DOOR_Y + 40f))
    }

    @Test fun everyFreeCellOfLevelTwoIsReachableFromTheStart() {
        val map = levelTwo()
        val n = Level2.CELLS
        val seen = BooleanArray(n * n)
        val stack = ArrayDeque<Int>()
        val start = (Level2.SPAWN_Y / Level2.CELL_SIZE).toInt() * n + (Level2.SPAWN_X / Level2.CELL_SIZE).toInt()
        stack.addLast(start); seen[start] = true
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            val cx = c % n; val cy = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = cx + dx; val ny = cy + dy
                if (map.isBlockedCell(nx, ny)) continue
                val i = ny * n + nx
                if (!seen[i]) { seen[i] = true; stack.addLast(i) }
            }
        }
        var free = 0; var reached = 0
        for (cy in 0 until n) for (cx in 0 until n) if (!map.isBlockedCell(cx, cy)) { free++; if (seen[cy * n + cx]) reached++ }
        assertEquals("walled-off pockets", free, reached)
    }

    @Test fun theLevelTableHasBothLevels() {
        assertEquals(1, Levels.of(1).number)
        assertEquals(2, Levels.of(2).number)
        assertEquals(Level2.SPAWN_X, Levels.TWO.spawnX, 0f)
        assertNotEquals(Levels.ONE.artFile, Levels.TWO.artFile)
    }

    @Test fun restartingTheScheduleStartsTheClockFromNow() {
        // the director cannot be inspected, but dropping the schedule must not throw on a bare world
        val spawn = com.kotlinsurvivors.engine.ecs.systems.SpawnSystem(1280f, 720f)
        val director = EventDirector(spawn, Level1.WORLD_SIZE, 1280f, 720f)
        director.restartSchedule(500f)
        assertFalse(director.waveActive)
    }

    // ── The engine ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: GameEngine? = null

    @After fun stopEngine() { engine?.release() }

    private fun waitFor(what: String, millis: Long = 6000L, test: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) { if (test()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    private fun startLab(first: LevelMap, second: LevelMap?): GameEngine {
        val e = GameEngine(1280f, 720f, loadLevelMap = { first }, laboratory = true, loadNextLevelMap = { second })
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        return e
    }

    @Test fun theRunStartsWithTheGateShutAndTheTesterCanOpenIt() {
        val first = levelOne()
        val e = startLab(first, levelTwo())
        val x = (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f
        val y = (Level1.BARRIER_Y0 + Level1.BARRIER_Y1) / 2f
        assertTrue(first.isBlockedAt(x, y))
        assertFalse(e.gameState.value.exitOpen)
        e.sendLabCommand(LabCommand.OpenExit)
        waitFor("exit open") { e.gameState.value.exitOpen }
        assertFalse(first.isBlockedAt(x, y))
    }

    @Test fun theTripToLevelTwoClearsLevelOneAndKeepsThePlayerAndHisThings() {
        val e = startLab(levelOne(), levelTwo())
        val pid = e.world.getPlayerEntity()
        e.sendLabCommand(LabCommand.GiveWeapon(com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS))
        waitFor("weapon") { e.world.weapons[pid]!!.any { it.type == com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS } }
        e.sendLabCommand(LabCommand.SpawnEnemy(EnemyType.BASIC, 5))
        waitFor("enemies") { e.world.enemies.size >= 5 }
        val weapons = e.world.weapons[pid]!!.size
        e.sendLabCommand(LabCommand.GoToLevel2)
        waitFor("level 2") { e.gameState.value.levelNumber == 2 }
        waitFor("clear screen") { e.gameState.value.levelFade == 0f }
        assertEquals(pid, e.world.getPlayerEntity())
        assertEquals(weapons, e.world.weapons[pid]!!.size)
        assertTrue("level 1 enemies are gone", e.world.enemies.values.none { it.waveTag != 0 })
        val t = e.world.transforms[pid]!!
        assertEquals(Level2.SPAWN_X, t.x, 2f)
        assertEquals(Level2.SPAWN_Y, t.y, 2f)
        assertFalse(e.gameState.value.exitOpen)
    }

    @Test fun withoutALevelTwoMapTheTripStillWorks() {
        val e = startLab(levelOne(), null)
        e.sendLabCommand(LabCommand.GoToLevel2)
        waitFor("level 2") { e.gameState.value.levelNumber == 2 }
        assertTrue(e.world.getPlayerEntity() != -1)
    }
}
