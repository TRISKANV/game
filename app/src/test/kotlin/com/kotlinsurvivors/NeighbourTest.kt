package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level1Npc
import com.kotlinsurvivors.engine.map.LevelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

/** The neighbour of Level 1: his picture, his place on the pavement, his solid body and his lines. */
class NeighbourTest {

    private val assets = File("src/main/assets/sprites")
    private val res    = File("src/main/res")

    private fun grid() = File(assets, "level1_solid.bin").readBytes()

    @Test fun hisPictureIsThere() {
        val picture = ImageIO.read(File(assets, "npc_leek.png"))
        assertEquals(Level1Npc.SPRITE_HEIGHT.toInt(), picture.height)
        assertTrue("the picture is narrower than tall", picture.width in 30..70)
        assertTrue("transparent corner", (picture.getRGB(0, 0) ushr 24) == 0)
    }

    @Test fun heStandsAFewStepsFromTheDoorOnFreeGround() {
        val door = hypot(Level1Npc.X - Level1.SPAWN_X, Level1Npc.FEET_Y - Level1.SPAWN_Y)
        assertTrue("distance from the spawn point: $door", door in 150f..600f)
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
        assertTrue("above him", map.isBoxFree(Level1Npc.X, Level1Npc.FEET_Y - 40f, half, half))
        assertTrue("below him", map.isBoxFree(Level1Npc.X, Level1Npc.FEET_Y + 40f, half, half))
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
