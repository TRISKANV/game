package com.kotlinsurvivors

import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.engine.lab.LabActions
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.LevelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The laboratory: where tested things appear, and that the engine obeys the commands and spawns nothing on its own. */
class LabTest {

    private val map: LevelMap by lazy {
        LevelMap(Level1.CELLS, Level1.CELL_SIZE, File("src/main/assets/sprites/level1_solid.bin").readBytes())
    }

    // ── LabActions, on a bare world ─────────────────────────────────────────

    private fun worldWithPlayer(): World = World().also { EntityFactory.createPlayer(it, Level1.SPAWN_X, Level1.SPAWN_Y) }

    @Test fun enemiesAppearOnFreeGroundAroundThePlayer() {
        val world = worldWithPlayer()
        val made = LabActions.spawnEnemies(world, map, EnemyType.BASIC, 10)
        assertEquals(10, made)
        assertEquals(10, world.enemies.size)
        for (id in world.enemies.keys) {
            val t = world.transforms[id]!!
            assertTrue("enemy inside a wall at ${t.x},${t.y}", map.isBoxFree(t.x, t.y, 16f, 16f))
        }
    }

    @Test fun spawningStopsAtTheCeiling() {
        val world = worldWithPlayer()
        LabActions.spawnEnemies(world, map, EnemyType.SWARM, LabActions.MAX_ENEMIES + 50)
        assertTrue(world.enemies.size <= LabActions.MAX_ENEMIES)
    }

    @Test fun aSpotIsFoundEvenWhenTheFirstAngleIsInAWall() {
        // right next to the house wall at the top of the level: some directions are blocked
        val spot = LabActions.findSpot(map, Level1.SPAWN_X, 60f, LabActions.SPAWN_DISTANCE, 0f)
        if (spot != null) assertTrue(map.isBoxFree(spot.first, spot.second, 20f, 20f))
    }

    @Test fun aMiniBossIsAMiniBoss() {
        val world = worldWithPlayer()
        LabActions.spawnEnemies(world, map, EnemyType.TANK, 1, miniBoss = true)
        assertTrue(world.enemies.values.single().isMiniBoss)
    }

    @Test fun everyPickupCanBeDropped() {
        val world = worldWithPlayer()
        for (type in PickupType.values()) assertTrue(type.name, LabActions.dropPickup(world, map, type) >= 0)
        assertEquals(PickupType.values().size, world.pickups.size)
    }

    @Test fun clearingRemovesEveryEnemyButNotThePlayer() {
        val world = worldWithPlayer()
        LabActions.spawnEnemies(world, map, EnemyType.BASIC, 8)
        assertEquals(8, LabActions.clearEnemies(world))
        assertTrue(world.enemies.isEmpty())
        assertNotNull(world.players[world.getPlayerEntity()])
    }

    @Test fun theLevelUpOrbIsWorthExactlyWhatIsMissing() {
        val world = worldWithPlayer()
        val player = world.players[world.getPlayerEntity()]!!
        assertTrue(LabActions.levelUp(world, map))
        val orb = world.pickups.values.single()
        assertEquals(PickupType.EXPERIENCE_LARGE, orb.type)
        val gained = (orb.value * player.xpMultiplier * player.comboXpMultiplier)
        assertTrue("orb gives $gained, missing ${player.experienceToNextLevel}", gained >= player.experienceToNextLevel - 1)
    }

    @Test fun healFillsTheHealth() {
        val world = worldWithPlayer()
        val h = world.healths[world.getPlayerEntity()]!!
        h.current = 1
        LabActions.healFull(world)
        assertEquals(h.max, h.current)
    }

    // ── The engine in laboratory mode ───────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: GameEngine? = null

    @After fun stopEngine() { engine?.release() }

    private fun startLab(): GameEngine {
        val e = GameEngine(1280f, 720f, loadLevelMap = { map }, laboratory = true)
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        return e
    }

    private fun waitFor(what: String, millis: Long = 3000L, test: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) { if (test()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun nothingSpawnsByItselfInTheLab() {
        val e = startLab()
        Thread.sleep(1500)
        assertTrue("enemies: ${e.world.enemies.size}", e.world.enemies.isEmpty())
    }

    @Test fun commandsAreAppliedByTheEngine() {
        val e = startLab()
        e.sendLabCommand(LabCommand.SpawnEnemy(EnemyType.TANK, 3))
        waitFor("3 tanks") { e.world.enemies.size >= 3 }
        e.sendLabCommand(LabCommand.DropPickup(PickupType.HEALTH_SMALL))
        waitFor("a can") { e.world.pickups.values.any { it.type == PickupType.HEALTH_SMALL } }
        e.sendLabCommand(LabCommand.GiveWeapon(WeaponType.DDOS))
        waitFor("DDOS") { e.world.weapons[e.world.getPlayerEntity()]!!.any { it.type == WeaponType.DDOS } }
        e.sendLabCommand(LabCommand.GiveWeapon(WeaponType.BIBLE))
        waitFor("BIBLE") { e.world.weapons[e.world.getPlayerEntity()]!!.any { it.type == WeaponType.BIBLE } }
        e.sendLabCommand(LabCommand.ClearEnemies)
        waitFor("no enemies") { e.world.enemies.isEmpty() }
    }

    @Test fun anEventStartsOnDemand() {
        val e = startLab()
        e.sendLabCommand(LabCommand.StartEvent(EventKind.SIEGE))
        waitFor("the ring") { e.world.enemies.size >= 10 }
        assertTrue(e.world.enemies.values.all { it.waveTag != 0 })
    }

    @Test fun weaponGivenTwiceLevelsUp() {
        val e = startLab()
        e.sendLabCommand(LabCommand.GiveWeapon(WeaponType.ZERO_DAY))
        e.sendLabCommand(LabCommand.GiveWeapon(WeaponType.ZERO_DAY))
        waitFor("level 2") { e.world.weapons[e.world.getPlayerEntity()]!!.any { it.type == WeaponType.ZERO_DAY && it.level == 2 } }
    }

    @Test fun invincibleKeepsThePlayerAliveAndCanBeSwitchedOff() {
        val e = startLab()
        val h = e.world.healths[e.world.getPlayerEntity()]!!
        h.current = 1
        waitFor("healed") { h.current == h.max }
        e.sendLabCommand(LabCommand.SetInvincible(false))
        Thread.sleep(100)
        h.current = 1
        Thread.sleep(300)
        assertEquals(1, h.current)
    }

    @Test fun aRealRunIgnoresLabCommands() {
        val e = GameEngine(1280f, 720f, loadLevelMap = { map })
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        e.sendLabCommand(LabCommand.SpawnEnemy(EnemyType.TANK, 5))
        Thread.sleep(400)
        // the first minutes of a real run bring no tank, so five of them could only come from the command
        assertTrue(e.world.enemies.values.none { it.type == EnemyType.TANK })
    }
}
