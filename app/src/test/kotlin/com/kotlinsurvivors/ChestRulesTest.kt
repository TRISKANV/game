package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ChestReward
import com.kotlinsurvivors.engine.ChestRules
import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.LevelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** What a boss chest holds, and that the game waits for the player to see it before handing it over. */
class ChestRulesTest {

    private fun weapon(type: WeaponType, level: Int = 1) =
        WeaponComponent(type = type, damage = 10, cooldown = 1f, level = level)

    private val max = 8

    @Test fun anUnownedExclusiveWeaponComesFirst() {
        repeat(50) {
            val r = ChestRules.roll(listOf(weapon(WeaponType.WHIP)), max, Random(it))
            assertEquals(ChestReward.Kind.NEW_WEAPON, r.kind)
            assertTrue(r.weapon!!.isChestOnly)
            assertEquals(1, r.level)
        }
    }

    @Test fun neverOffersOneThePlayerAlreadyHas() {
        val have = listOf(weapon(WeaponType.DDOS), weapon(WeaponType.ZERO_DAY))
        repeat(50) {
            val r = ChestRules.roll(have, max, Random(it))
            assertEquals(WeaponType.LOGIC_BOMB, r.weapon)
        }
    }

    @Test fun withAllThreeOneGetsALevel() {
        val have = listOf(weapon(WeaponType.DDOS, 3), weapon(WeaponType.ZERO_DAY, 1), weapon(WeaponType.LOGIC_BOMB, max))
        repeat(50) {
            val r = ChestRules.roll(have, max, Random(it))
            assertEquals(ChestReward.Kind.LEVEL_UP, r.kind)
            assertTrue(r.weapon == WeaponType.DDOS || r.weapon == WeaponType.ZERO_DAY)   // the maxed one is skipped
            assertEquals(have.first { w -> w.type == r.weapon }.level + 1, r.level)
        }
    }

    @Test fun withEverythingAtTheTopItHeals() {
        val have = listOf(weapon(WeaponType.DDOS, max), weapon(WeaponType.ZERO_DAY, max), weapon(WeaponType.LOGIC_BOMB, max))
        val r = ChestRules.roll(have, max)
        assertEquals(ChestReward.Kind.HEAL, r.kind)
        assertNull(r.weapon)
    }

    // ── The engine: frozen while the chest is shown, reward handed over on confirmation ──

    private val map: LevelMap by lazy {
        LevelMap(Level1.CELLS, Level1.CELL_SIZE, File("src/main/assets/sprites/level1_solid.bin").readBytes())
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: GameEngine? = null

    @After fun stop() { engine?.release() }

    private fun waitFor(what: String, millis: Long = 4000L, test: () -> Boolean) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) { if (test()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun theGameFreezesUntilTheRewardIsConfirmed() {
        val e = GameEngine(1280f, 720f, loadLevelMap = { map }, laboratory = true)
        engine = e
        e.start(scope)
        waitFor("engine running") { e.world.getPlayerEntity() != -1 }
        val pid = e.world.getPlayerEntity()

        e.sendLabCommand(LabCommand.DropPickup(PickupType.CHEST))
        waitFor("the chest on the floor") { e.world.pickups.values.any { it.type == PickupType.CHEST } }
        // walk onto it: the chest opens when the player touches it
        val chestId = e.world.pickups.entries.first { it.value.type == PickupType.CHEST }.key
        val at = e.world.transforms[chestId]!!
        e.world.transforms[pid]!!.let { it.x = at.x; it.y = at.y }
        waitFor("the chest screen") { e.gameState.value.chestReward != null }
        val reward = e.gameState.value.chestReward!!
        assertEquals(ChestReward.Kind.NEW_WEAPON, reward.kind)

        // frozen: the clock does not move and nothing is handed over yet
        val frozenAt = e.gameState.value.elapsedTime
        Thread.sleep(500)
        assertEquals(frozenAt, e.gameState.value.elapsedTime, 0.001f)
        assertFalse(e.world.weapons[pid]!!.any { it.type == reward.weapon })

        e.confirmChest()
        waitFor("the reward") { e.world.weapons[pid]!!.any { it.type == reward.weapon } }
        waitFor("the game running again") { e.gameState.value.chestReward == null }
        val resumedAt = e.gameState.value.elapsedTime
        Thread.sleep(300)
        assertTrue("time runs again", e.gameState.value.elapsedTime > resumedAt)
        assertNotNull(e.world.weapons[pid])
    }
}
