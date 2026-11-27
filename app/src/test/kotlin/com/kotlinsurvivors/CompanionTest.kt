package com.kotlinsurvivors

import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.CompanionState
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.systems.CompanionSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level2
import com.kotlinsurvivors.engine.map.Level3
import com.kotlinsurvivors.engine.map.LevelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** The police dog: the system that moves him and the cell, the key and the engine that let him out. */
class CompanionTest {
    private val dir = File("src/main/assets/sprites")
    private fun station() = LevelMap(Level3.CELLS, Level3.CELL_SIZE, File(dir, "station_solid.bin").readBytes())
    private fun levelOne() = LevelMap(Level1.CELLS, Level1.CELL_SIZE, File(dir, "level1_solid.bin").readBytes())
    private fun levelTwo() = LevelMap(Level2.CELLS, Level2.CELL_SIZE, File(dir, "level2_solid.bin").readBytes())

    private val system = CompanionSystem()
    private val events = ArrayList<GameEvent>()

    private fun worldWith(dogAt: Pair<Float, Float>, caged: Boolean = false): Triple<World, Int, Int> {
        val w = World()
        val pid = EntityFactory.createPlayer(w, 1000f, 1000f)
        val dog = EntityFactory.createCompanion(w, dogAt.first, dogAt.second, caged)
        return Triple(w, pid, dog)
    }

    private fun run(w: World, seconds: Float, map: LevelMap? = null) {
        repeat((seconds * 60).toInt()) {
            for (h in w.healths.values) if (h.invincibleTimer > 0f) h.invincibleTimer = (h.invincibleTimer - 1f / 60f).coerceAtLeast(0f)
            system.update(w, 1f / 60f, map, events); w.flushDestroyed()
        }
    }

    private fun dist(w: World, a: Int, b: Int): Float {
        val p = w.transforms[a]!!; val q = w.transforms[b]!!
        return hypot(p.x - q.x, p.y - q.y)
    }

    @Test fun aCagedDogStaysWhereHeIs() {
        val (w, pid, dog) = worldWith(1400f to 1000f, caged = true)
        run(w, 3f)
        assertEquals(1400f, w.transforms[dog]!!.x, 0f)
        assertEquals(CompanionState.CAGED, w.companions[dog]!!.state)
    }

    @Test fun aFreeDogCatchesUpAndStaysClose() {
        val (w, pid, dog) = worldWith(1300f to 1000f)
        run(w, 4f)
        assertTrue("distance ${dist(w, pid, dog)}", dist(w, pid, dog) < 110f)
        // the player walks away; the dog keeps up
        repeat(300) { w.transforms[pid]!!.x += 2f; system.update(w, 1f / 60f, null, events) }
        assertTrue("distance ${dist(w, pid, dog)}", dist(w, pid, dog) < 200f)
    }

    @Test fun aDogLeftFarBehindIsBroughtBack() {
        val (w, pid, dog) = worldWith(3000f to 3000f)
        run(w, 0.1f)
        assertTrue(dist(w, pid, dog) < 150f)
    }

    @Test fun theDogBitesTheEnemyThatComesNearAndTheKillIsReported() {
        val (w, pid, dog) = worldWith(1040f to 1000f)
        val enemy = EntityFactory.createEnemy(w, EnemyType.BASIC, 1100f, 1000f)
        w.healths[enemy]!!.let { it.current = 20; it.max = 20 }
        run(w, 3f)
        assertTrue("the enemy lost health or died", w.healths[enemy]!!.current < 20)
        assertTrue("died", w.healths[enemy]!!.isDead)
        assertEquals(1, events.count { it is GameEvent.EnemyKilled && it.entityId == enemy })
    }

    @Test fun theDogLeavesSleepersAndUnawarePatrolsAlone() {
        val (w, pid, dog) = worldWith(1040f to 1000f)
        val sleeper = EntityFactory.createEnemy(w, EnemyType.BASIC, 1080f, 1000f)
        w.enemies[sleeper]!!.asleep = true
        val patrol = EntityFactory.createEnemy(w, EnemyType.COP, 1000f, 1060f)
        w.enemies[patrol]!!.patrol = PatrolState.WALK
        val hp1 = w.healths[sleeper]!!.current; val hp2 = w.healths[patrol]!!.current
        run(w, 3f)
        assertEquals(hp1, w.healths[sleeper]!!.current)
        assertEquals(hp2, w.healths[patrol]!!.current)
    }

    @Test fun enemiesHurtTheDogAndWhenHisHealthRunsOutHeLiesDownThenGetsUp() {
        val (w, pid, dog) = worldWith(1040f to 1000f)
        w.companions[dog]!!.hp = 5
        val enemy = EntityFactory.createEnemy(w, EnemyType.TANK, 1060f, 1000f)
        w.healths[enemy]!!.let { it.current = 100000; it.max = 100000 }
        run(w, 1.5f)
        val c = w.companions[dog]!!
        assertEquals(CompanionState.DOWNED, c.state)
        w.destroyEntity(enemy); w.flushDestroyed()
        run(w, CompanionSystem.DOWN_SECONDS + 0.5f)
        assertEquals(CompanionState.FOLLOW, c.state)
        assertTrue("hp ${c.hp}", c.hp in (c.maxHp / 2)..(c.maxHp / 2 + 3))
    }

    @Test fun aDogWithoutAnyHealthComponentCannotBeTargetedLikeAnEnemy() {
        val (w, _, dog) = worldWith(1040f to 1000f)
        assertFalse(w.enemies.containsKey(dog)); assertFalse(w.healths.containsKey(dog)); assertFalse(w.colliders.containsKey(dog))
    }

    @Test fun theDogRidesAlongWhileThePlayerDrives() {
        val (w, pid, dog) = worldWith(1040f to 1000f)
        w.players[pid]!!.drivingCar = 77
        w.transforms[pid]!!.x = 1600f
        run(w, 0.1f)
        assertEquals(0f, dist(w, pid, dog), 1f)
    }

    // ── the cell, the key and the level ──────────────────────────────────────

    private fun reachable(map: LevelMap, fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        val n = map.cells; val cs = map.cellSize
        val seen = BooleanArray(n * n)
        val stack = ArrayDeque<Int>()
        val s = (fromY / cs).toInt() * n + (fromX / cs).toInt()
        stack.addLast(s); seen[s] = true
        val goal = (toY / cs).toInt() * n + (toX / cs).toInt()
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c == goal) return true
            val cx = c % n; val cy = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                if (map.isBlockedCell(cx + dx, cy + dy)) continue
                val i = (cy + dy) * n + cx + dx
                if (!seen[i]) { seen[i] = true; stack.addLast(i) }
            }
        }
        return false
    }

    @Test fun theDogIsLockedInUntilTheCellOpensAndThenCanWalkOut() {
        val map = station()
        assertTrue("the dog stands inside the cell, behind solid cells", map.isBlockedAt(Level3.CELL_DOG_X, Level3.CELL_DOG_Y))
        assertFalse(reachable(map, Level3.CELL_DOOR_X, Level3.CELL_DOOR_Y, Level3.CELL_DOG_X, Level3.CELL_DOG_Y))
        map.setRect(Level3.CELL_FREE_X0, Level3.CELL_FREE_Y0, Level3.CELL_FREE_X1, Level3.CELL_FREE_Y1, false)
        assertFalse(map.isBlockedAt(Level3.CELL_DOG_X, Level3.CELL_DOG_Y))
        assertTrue(reachable(map, Level3.CELL_DOG_X, Level3.CELL_DOG_Y, Level3.CELL_DOOR_X, Level3.CELL_DOOR_Y))
    }

    @Test fun openingTheCellDoesNotOpenTheNeighbouringCells() {
        val map = station()
        map.setRect(Level3.CELL_FREE_X0, Level3.CELL_FREE_Y0, Level3.CELL_FREE_X1, Level3.CELL_FREE_Y1, false)
        val width = Level3.CELL_FREE_X1 - Level3.CELL_FREE_X0
        val y = Level3.CELL_DOG_Y
        // one cell width to either side is still solid
        assertTrue(map.isBlockedAt(Level3.CELL_FREE_X0 - 24f, y))
        assertTrue(map.isBlockedAt(Level3.CELL_FREE_X1 + 24f, y))
        assertTrue(width > 100f)
    }

    @Test fun theCellKeyLiesOnFreeFloorTheSpawnCanReach() {
        val map = station()
        assertFalse(map.isBlockedAt(Level3.CELL_KEY_X, Level3.CELL_KEY_Y))
        assertTrue(reachable(map, Level3.SPAWN_X, Level3.SPAWN_Y, Level3.CELL_KEY_X, Level3.CELL_KEY_Y))
        assertTrue(reachable(map, Level3.SPAWN_X, Level3.SPAWN_Y, Level3.CELL_DOOR_X, Level3.CELL_DOOR_Y))
        assertTrue(File(dir, "station_cell_open.png").exists())
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

    @Test fun theStationHasACagedDogAndTheCellKeyOnTheFloor() {
        val e = startInStation()
        waitFor("the dog") { e.world.companions.values.any { it.state == CompanionState.CAGED } }
        assertEquals(1, e.world.pickups.values.count { it.type == PickupType.CELL_KEY })
    }

    @Test fun takingTheKeyAndStandingAtTheBarsLetsTheDogOutAndHeFollowsToLevelTwo() {
        val e = startInStation()
        val pid = e.world.getPlayerEntity()
        e.sendLabCommand(LabCommand.ClearEnemies)           // the officers of the cell block would see him and shut the street door
        waitFor("the station is empty") { e.world.enemies.isEmpty() }
        waitFor("the key") { e.world.pickups.values.any { it.type == PickupType.CELL_KEY } }
        // the player walks over the key
        e.world.transforms[pid]!!.let { it.x = Level3.CELL_KEY_X; it.y = Level3.CELL_KEY_Y - 26f }
        waitFor("the key is taken") { e.world.pickups.values.none { it.type == PickupType.CELL_KEY } }
        // and goes to the cell
        e.world.transforms[pid]!!.let { it.x = Level3.CELL_DOOR_X; it.y = Level3.CELL_DOOR_Y }
        waitFor("the dog is free") { e.world.companions.values.any { it.state == CompanionState.FOLLOW } }
        waitFor("the dog walks out of the cell") {
            val d = e.world.companions.keys.first()
            val t = e.world.transforms[d]!!
            hypot(t.x - Level3.CELL_DOOR_X, t.y - Level3.CELL_DOOR_Y) < 160f
        }
        // out in the plaza he is still there, next to the player
        e.world.transforms[pid]!!.let { it.x = (Level3.EXIT_X0 + Level3.EXIT_X1) / 2f; it.y = Level3.EXIT_Y + 10f }
        waitFor("the street") { e.gameState.value.levelNumber == 2 && e.gameState.value.levelFade == 0f }
        waitFor("the dog is with the player") {
            val d = e.world.companions.keys.firstOrNull() ?: return@waitFor false
            val t = e.world.transforms[d]!!; val p = e.world.transforms[pid]!!
            hypot(t.x - p.x, t.y - p.y) < 250f
        }
        assertEquals(1, e.world.companions.size)
    }

    @Test fun theLaboratoryCanCallTheDogAnywhere() {
        val e = GameEngine(1280f, 720f, loadLevelMap = { levelOne() }, laboratory = true,
            loadNextLevelMap = { levelTwo() }, loadStationMap = { station() })
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        e.sendLabCommand(LabCommand.CallDog)
        waitFor("the dog") { e.world.companions.values.any { it.state == CompanionState.FOLLOW } }
        e.sendLabCommand(LabCommand.CallDog)
        Thread.sleep(300)
        assertEquals("calling twice does not make two dogs", 1, e.world.companions.size)
    }
}
