package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level1Npc
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.engine.ecs.components.staysPut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The neighbour of Level 1: his picture, his place on the pavement, his solid body and his lines. */
class NeighbourTest {

    private val assets = File("src/main/assets/sprites")
    private val res    = File("src/main/res")

    private fun grid() = File(assets, "level1_solid.bin").readBytes()

    @Test fun hisPictureIsThere() {
        val picture = ImageIO.read(File(assets, "npc_leek.png"))
        assertEquals(Level1Npc.FRAME_H, picture.height)
        assertEquals(Level1Npc.FRAME_W * Level1Npc.FRAMES, picture.width)
        for (f in 0 until Level1Npc.FRAMES) {
            var painted = 0
            for (y in 0 until picture.height) for (x in 0 until Level1Npc.FRAME_W)
                if ((picture.getRGB(f * Level1Npc.FRAME_W + x, y) ushr 24) > 0) painted++
            assertTrue("frame $f has a drawing", painted > 400)
        }
        assertTrue("transparent corner", (picture.getRGB(0, 0) ushr 24) == 0)
    }

    @Test fun heStandsOnThePavementInFrontOfTheCar() {
        // the car's body is x 896..1032, y 744..810; he is on the pavement (y 544..704) facing it
        assertTrue("x ${Level1Npc.X}", Level1Npc.X in 900f..1030f)
        assertTrue("y ${Level1Npc.FEET_Y}", Level1Npc.FEET_Y in 600f..704f)
        val free = LevelMap(Level1.CELLS, Level1.CELL_SIZE, grid())
        assertFalse("the ground under him is free in the original grid", free.isBlockedAt(Level1Npc.X, Level1Npc.FEET_Y))
        assertTrue(free.isBoxFree(Level1Npc.X, Level1Npc.FEET_Y, 30f, 12f))
    }

    @Test fun heIsSolidAndTheStreetStillLetsYouPass() {
        val bytes = grid()
        Level1Npc.stampSolid(bytes, Level1.CELLS, Level1.CELL_SIZE)
        val map = LevelMap(Level1.CELLS, Level1.CELL_SIZE, bytes)
        assertTrue(map.isBlockedAt(Level1Npc.X, Level1Npc.FEET_Y))
        assertTrue(map.isBlockedAt(Level1Npc.X - 20f, Level1Npc.FEET_Y))
        assertTrue(map.isBlockedAt(Level1Npc.X + 20f, Level1Npc.FEET_Y))
        // the player can still go round him, above and below, and the spawn point is untouched
        val half = MovementSystem.PLAYER_HALF
        val feet = MovementSystem.PLAYER_FEET_OFFSET
        assertTrue("east of him", map.isBoxFree(Level1Npc.X + 70f, Level1Npc.FEET_Y, half, half))
        assertTrue("above him", map.isBoxFree(Level1Npc.X, Level1Npc.FEET_Y - 40f, half, half))
        assertTrue("spawn", map.isBoxFree(Level1.SPAWN_X, Level1.SPAWN_Y + feet, half, half))
        // only a handful of cells changed
        val before = grid()
        val changed = bytes.indices.count { bytes[it] != before[it] }
        assertTrue("cells changed: $changed", changed in 1..12)
    }

    @Test fun everyLineExistsInBothLanguagesAndNoneIsLeftOver() {
        for (dir in listOf("values", "values-es")) {
            val text = File(res, "$dir/strings.xml").readText()
            val found = Regex("name=\"npc_leek_(\\d+)\"").findAll(text).map { it.groupValues[1].toInt() }.toList().sorted()
            assertEquals("$dir lines", (1..Level1Npc.LINE_COUNT).toList(), found)
        }
    }
}

/** The talking logic of the neighbour: when he starts, which line, when he is quiet. */
class NeighbourTalkTest {

    private val talk = com.kotlinsurvivors.engine.rendering.NeighbourTalk(kotlin.random.Random(7)).apply {
        lines = Array(6) { "line $it is a sentence of some length" }
    }
    private val nearX = Level1Npc.X + 100f
    private val nearY = Level1Npc.FEET_Y
    private val farX  = Level1Npc.X + 900f

    @Test fun quietUntilSomebodyComesClose() {
        talk.update(farX, nearY, 1000L)
        assertFalse(talk.near)
        assertEquals(-1, talk.lineIndex)
    }

    @Test fun saysOneLineOnArrivingAndStaysQuietWhileThePlayerStandsThere() {
        talk.update(farX, nearY, 1000L)
        talk.update(nearX, nearY, 2000L)
        assertTrue(talk.near)
        assertEquals(2000L, talk.lineStart)
        val first = talk.lineIndex
        // a minute next to him: no second line
        var t = 2000L
        while (t < 62_000L) { t += 100L; talk.update(nearX, nearY, t) }
        assertEquals(first, talk.lineIndex)
        assertEquals(2000L, talk.lineStart)
        assertFalse("the line is long over", talk.isSpeaking(t))
    }

    @Test fun saysAnotherLineOnWalkingAwayAndAnotherOnComingBack() {
        talk.update(nearX, nearY, 0L)
        val arrival = talk.lineIndex
        talk.update(farX, nearY, 20_000L)
        assertFalse(talk.near)
        assertEquals(20_000L, talk.lineStart)
        assertTrue(talk.isSpeaking(20_100L))
        val parting = talk.lineIndex
        assertTrue("not the same line twice in a row", parting != arrival)
        talk.update(nearX, nearY, 40_000L)
        assertTrue(talk.near)
        assertEquals(40_000L, talk.lineStart)
        assertTrue(talk.lineIndex != parting)
    }

    @Test fun nothingIsSaidAtTheStartOfTheRun() {
        talk.update(farX, nearY, 0L)
        talk.update(farX, nearY, 5000L)
        assertFalse(talk.isSpeaking(5000L))
    }

    @Test fun theBandBetweenTheRadiiDoesNotMakeHimChatter() {
        talk.update(nearX, nearY, 0L)
        val start = talk.lineStart
        // wobble between the two radii: still near, no new line
        for (i in 1..40) {
            val x = Level1Npc.X + if (i % 2 == 0) Level1Npc.TALK_RADIUS + 20f else Level1Npc.LEAVE_RADIUS - 20f
            talk.update(x, nearY, i * 50L)
        }
        assertTrue(talk.near)
        assertEquals(start, talk.lineStart)
        talk.update(Level1Npc.X + Level1Npc.LEAVE_RADIUS + 20f, nearY, 5000L)
        assertFalse(talk.near)
    }

    @Test fun sixLinesAreSaidOnceEachBeforeAnyRepeats() {
        val said = ArrayList<Int>()
        var t = 0L
        talk.update(nearX, nearY, t); said.add(talk.lineIndex)
        while (said.size < 6) {
            t += 10_000L; talk.update(farX, nearY, t); said.add(talk.lineIndex)
            if (said.size < 6) { t += 10_000L; talk.update(nearX, nearY, t); said.add(talk.lineIndex) }
        }
        assertEquals((0..5).toList(), said.sorted())
    }

    @Test fun theDebugDistanceFollowsThePlayer() {
        talk.update(nearX, nearY, 0L)
        assertEquals(100f, talk.distance, 0.5f)
    }

}

/** The energy can waits where it fell: no magnet moves it and it outlasts ordinary pickups. */
class EnergyCanTest {
    @Test fun cansAndChestsStayPutOthersDoNot() {
        for (t in com.kotlinsurvivors.engine.ecs.components.PickupType.values()) {
            val expected = t == com.kotlinsurvivors.engine.ecs.components.PickupType.CHEST ||
                t == com.kotlinsurvivors.engine.ecs.components.PickupType.HEALTH_SMALL ||
                t == com.kotlinsurvivors.engine.ecs.components.PickupType.HEALTH_LARGE
            assertEquals(t.name, expected, t.staysPut)
        }
    }

    @Test fun aCanWaitsLongerThanAnOrbAndTheDropChanceIsThirtyPercent() {
        assertTrue(com.kotlinsurvivors.engine.ecs.EntityFactory.CAN_WAIT_SECONDS > 30f)
        assertEquals(0.30f, com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem.HEAL_DROP_CHANCE, 0f)
    }
}

/** Energy cans: about three in ten kills drop one, but never more than three lie on the floor. */
class EnergyCanDropTest {
    private val world = com.kotlinsurvivors.engine.ecs.World()
    private val xp    = com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem()

    private fun kill() {
        val id = com.kotlinsurvivors.engine.ecs.EntityFactory.createEnemy(world, com.kotlinsurvivors.engine.ecs.components.EnemyType.TANK, 100f, 100f)
        world.healths[id]!!.isDead = true
        xp.processEvents(world, mutableListOf(com.kotlinsurvivors.engine.ecs.systems.GameEvent.EnemyKilled(id, com.kotlinsurvivors.engine.ecs.components.EnemyType.TANK, 5, 0f)))
        world.flushDestroyed()
    }

    private fun cans() = world.pickups.values.count {
        it.type == com.kotlinsurvivors.engine.ecs.components.PickupType.HEALTH_SMALL
    }

    @Test fun neverMoreThanThreeOnTheFloor() {
        repeat(500) { kill(); assertTrue("cans: ${cans()}", cans() <= 3) }
        assertEquals(3, cans())
    }

    @Test fun oncePickedUpMoreCanFall() {
        repeat(300) { kill() }
        assertEquals(3, cans())
        world.pickups.entries.removeAll { it.value.type == com.kotlinsurvivors.engine.ecs.components.PickupType.HEALTH_SMALL }
        repeat(300) { kill() }
        assertEquals(3, cans())
    }
}
