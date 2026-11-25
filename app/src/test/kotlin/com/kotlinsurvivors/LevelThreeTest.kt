package com.kotlinsurvivors

import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level2
import com.kotlinsurvivors.engine.map.Level3
import com.kotlinsurvivors.engine.map.LevelFlow
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.engine.map.Levels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The inside of the police station: its files, the way in and out, the officers' beats and the wanted poster. */
class LevelThreeTest {

    private val dir = File("src/main/assets/sprites")
    private fun levelOne() = LevelMap(Level1.CELLS, Level1.CELL_SIZE, File(dir, "level1_solid.bin").readBytes())
    private fun levelTwo() = LevelMap(Level2.CELLS, Level2.CELL_SIZE, File(dir, "level2_solid.bin").readBytes())
    private fun station()  = LevelMap(Level3.CELLS, Level3.CELL_SIZE, File(dir, "station_solid.bin").readBytes())

    private val doorX = (Level2.STATION_DOOR_LEFT + Level2.STATION_DOOR_RIGHT) / 2f

    // ── files ────────────────────────────────────────────────────────────────

    @Test fun theStationFilesMatchTheMetadata() {
        val info = Levels.STATION
        assertEquals(3, info.number)
        assertEquals(Level3.CELLS * Level3.CELLS, File(dir, info.solidFile).length().toInt())
        val picture = ImageIO.read(File(dir, info.artFile))
        assertEquals((Level3.WORLD_SIZE / Level3.IMAGE_SCALE).toInt(), picture.width)
        assertEquals(picture.width, picture.height)
        assertEquals(info, Levels.of(3))
    }

    @Test fun theOfficersSheetHasTheGuardsLayout() {
        val sheet = ImageIO.read(File(dir, "enemy_cop.png"))
        val guard = ImageIO.read(File(dir, "enemy_basic.png"))
        assertEquals(guard.width, sheet.width)
        assertEquals(guard.height, sheet.height)
    }

    @Test fun theSpawnTheExitAndThePosterAreOnFreeGround() {
        val m = station()
        assertFalse(m.isBlockedAt(Level3.SPAWN_X, Level3.SPAWN_Y))
        assertFalse(m.isBlockedAt((Level3.EXIT_X0 + Level3.EXIT_X1) / 2f, Level3.EXIT_Y + 20f))
        assertFalse(m.isBlockedAt(Level3.POSTER_X, Level3.POSTER_Y))
        assertTrue(m.isBlockedAt(5f, 5f))
    }

    @Test fun everyOfficerStartsOnFreeGroundAndWalksOnlyOverFreeGround() {
        val m = station()
        assertTrue(Level3.COP_ROUTES.size >= 6)
        for (route in Level3.COP_ROUTES) {
            assertTrue(route.size >= 2 && route.size % 2 == 0)
            val n = route.size / 2
            for (k in 0 until n) assertFalse(m.isBlockedAt(route[k * 2], route[k * 2 + 1]))
            if (n > 1) for (k in 0 until n) {
                val a = (k) * 2; val b = ((k + 1) % n) * 2
                assertTrue("a beat crosses a wall", m.hasLineOfSight(route[a], route[a + 1], route[b], route[b + 1]))
            }
        }
    }

    // ── the way in and out ───────────────────────────────────────────────────

    @Test fun theDoorOfTheStationLeadsInAndTheStreetDoorLeadsOut() {
        val flow = LevelFlow()
        flow.openExit(); flow.startTrip()
        var guard = 0
        while (flow.level == 1 && guard++ < 600) flow.update(1f / 60f, 0f, 0f)
        while (flow.isTravelling && guard++ < 1200) flow.update(1f / 60f, 0f, 0f)
        assertEquals(2, flow.level)

        // standing away from the door does nothing
        assertFalse(flow.update(1f / 60f, doorX, Level2.STATION_DOOR_Y + 300f))
        assertFalse(flow.isTravelling)

        var swaps = 0
        var steps = 0
        while ((flow.level == 2 || flow.isTravelling || steps == 0) && steps++ < 600)
            if (flow.update(1f / 60f, doorX, Level2.STATION_DOOR_Y + 10f)) swaps++
        assertEquals(1, swaps)
        assertEquals(3, flow.level)
        assertEquals(2, flow.previous)

        // inside, walking out through the street door goes back to the plaza
        val exitX = (Level3.EXIT_X0 + Level3.EXIT_X1) / 2f
        steps = 0; swaps = 0
        while ((flow.level == 3 || flow.isTravelling || steps == 0) && steps++ < 600)
            if (flow.update(1f / 60f, exitX, Level3.EXIT_Y + 10f)) swaps++
        assertEquals(1, swaps)
        assertEquals(2, flow.level)
        assertEquals(3, flow.previous)
    }

    // ── the engine ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: GameEngine? = null

    @After fun stopEngine() { engine?.release() }

    private fun waitFor(what: String, millis: Long = 9000L, test: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) { if (test()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    private fun startInStation(): GameEngine {
        val e = GameEngine(1280f, 720f, loadLevelMap = { levelOne() }, laboratory = true,
            loadNextLevelMap = { levelTwo() }, loadStationMap = { station() })
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        e.sendLabCommand(LabCommand.GoToLevel2)
        waitFor("level 2") { e.gameState.value.levelNumber == 2 && e.gameState.value.levelFade == 0f }
        e.sendLabCommand(LabCommand.GoToStation)
        waitFor("the station") { e.gameState.value.levelNumber == 3 && e.gameState.value.levelFade == 0f }
        return e
    }

    private fun cops(e: GameEngine) = e.world.enemies.filterValues { it.type == EnemyType.COP }

    @Test fun insideTheStationTheOfficersWalkTheirBeatsAndNothingElseAppears() {
        val e = startInStation()
        val pid = e.world.getPlayerEntity()
        val t = e.world.transforms[pid]!!
        assertEquals(Level3.SPAWN_X, t.x, 2f)
        assertEquals(Level3.SPAWN_Y, t.y, 2f)
        assertEquals(Level3.COP_ROUTES.size, cops(e).size)
        assertTrue(cops(e).values.all { it.patrol == PatrolState.WALK })
        val before = cops(e).keys.associateWith { e.world.transforms[it]!!.let { p -> p.x to p.y } }
        Thread.sleep(2500)
        assertEquals("nobody else comes in", Level3.COP_ROUTES.size, e.world.enemies.size)
        val moved = before.count { (id, p) -> e.world.transforms[id]?.let { q -> Math.hypot((q.x - p.first).toDouble(), (q.y - p.second).toDouble()) > 15.0 } == true }
        assertTrue("officers on a beat walk, $moved moved", moved >= 3)
        assertTrue("nobody noticed the player yet", cops(e).values.all { it.patrol == PatrolState.WALK })
    }

    @Test fun lookingAtThePosterMakesAnOfficerShoutAndThenEveryoneComes() {
        val e = startInStation()
        val pid = e.world.getPlayerEntity()
        e.world.transforms[pid]!!.let { it.x = Level3.POSTER_X; it.y = Level3.POSTER_Y }
        waitFor("the shout") { e.world.enemies.values.any { it.shouting } }
        assertEquals("only one shouts", 1, e.world.enemies.values.count { it.shouting })
        waitFor("everyone chases") { cops(e).values.all { it.patrol == PatrolState.NONE } }
        assertTrue("the player's own weapons may have dropped one", cops(e).size in 1..Level3.COP_ROUTES.size)
        assertTrue("the shouting is over", cops(e).values.none { it.shouting })
    }

    @Test fun walkingOutOfTheStationClearsItAndLeavesThePlayerAtItsDoor() {
        val e = startInStation()
        val pid = e.world.getPlayerEntity()
        e.world.transforms[pid]!!.let { it.x = (Level3.EXIT_X0 + Level3.EXIT_X1) / 2f; it.y = Level3.EXIT_Y + 20f }
        waitFor("the plaza") { e.gameState.value.levelNumber == 2 && e.gameState.value.levelFade == 0f }
        assertTrue(cops(e).isEmpty())
        val t = e.world.transforms[pid]!!
        assertEquals(doorX, t.x, 2f)
        assertTrue("he stands clear of the door so it does not take him back in", t.y > Level2.STATION_DOOR_Y + LevelFlow.DOOR_REACH_BELOW + 20f)
        Thread.sleep(800)
        assertEquals(2, e.gameState.value.levelNumber)
    }
}
