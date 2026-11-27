package com.kotlinsurvivors

import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level2
import com.kotlinsurvivors.engine.map.LevelFlow
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.engine.map.LevelSeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Level 1 ends where Level 2 begins: the two pictures and grids must agree exactly there. */
class LevelSeamTest {
    private val dir = File("src/main/assets/sprites")
    private val one = ImageIO.read(File(dir, "level1.png"))
    private val two = ImageIO.read(File(dir, "level2.png"))
    private fun mapOne() = LevelMap(Level1.CELLS, Level1.CELL_SIZE, File(dir, "level1_solid.bin").readBytes())
    private fun mapTwo() = LevelMap(Level2.CELLS, Level2.CELL_SIZE, File(dir, "level2_solid.bin").readBytes())

    // image pixels = world pixels / imageScale (2)
    private val px = (-LevelSeam.DX / 2f).toInt()      // level 1 x = level 2 x + px
    private val py = (-LevelSeam.DY / 2f).toInt()

    @Test fun theSeamLandsOnWholeCellsAndOnTheBottomOfLevelOne() {
        assertEquals(0f, LevelSeam.DX % Level1.CELL_SIZE, 0f)
        assertEquals(0f, LevelSeam.DY % Level1.CELL_SIZE, 0f)
        assertEquals(one.height, py + 360)
        assertEquals(LevelSeam.LEVEL1_FROM_Y, -LevelSeam.DY, 0f)
    }

    @Test fun thePicturesAreIdenticalWhereTheyOverlap() {
        // the whole road, both sidewalks and the shops beside it: Level 2 columns 48..991, rows 0..359
        var different = 0
        for (y in 0 until 360) for (x in 48 until 992) {
            if (one.getRGB(x + px, y + py) != two.getRGB(x, y)) different++
        }
        assertEquals("pixels that differ across the seam", 0, different)
    }

    @Test fun theCollisionGridsAgreeWhereTheyOverlap() {
        val a = mapOne(); val b = mapTwo()
        var different = 0
        var y = 4f
        while (y < 720f) {
            var x = 48f * 2f + 4f
            while (x < 992f * 2f) {
                if (a.isBlockedAt(x - LevelSeam.DX, y - LevelSeam.DY) != b.isBlockedAt(x, y)) different++
                x += Level1.CELL_SIZE
            }
            y += Level1.CELL_SIZE
        }
        assertEquals("cells that differ across the seam", 0, different)
    }

    @Test fun theTriggerIsInsideTheOverlapAndTheRoadContinuesThroughIt() {
        assertTrue(Level1.EXIT_TRIGGER_Y > LevelSeam.LEVEL1_FROM_Y + 400f)
        val a = mapOne(); val b = mapTwo()
        val x = (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f
        // the middle of the road is open on both sides of the swap
        assertFalse(a.isBlockedAt(x, Level1.EXIT_TRIGGER_Y + 20f))
        assertFalse(b.isBlockedAt(x + LevelSeam.DX, Level1.EXIT_TRIGGER_Y + 20f + LevelSeam.DY))
    }

    @Test fun walkingOutOfTheOpenExitChangesLevelAtOnceWithNoFade() {
        val flow = LevelFlow(); flow.openExit()
        val swapped = flow.update(1f / 60f, (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f, Level1.EXIT_TRIGGER_Y + 4f)
        assertTrue(swapped)
        assertEquals(2, flow.level); assertEquals(1, flow.previous)
        assertTrue(flow.lastSwapSeamless)
        assertEquals(0f, flow.fade, 0f)
        assertFalse(flow.isTravelling)
        // and it happens only once
        assertFalse(flow.update(1f / 60f, 1400f, 400f))
    }

    @Test fun theShutExitDoesNotChangeLevel() {
        val flow = LevelFlow()
        assertFalse(flow.update(1f / 60f, (Level1.EXIT_X0 + Level1.EXIT_X1) / 2f, Level1.EXIT_TRIGGER_Y + 4f))
        assertEquals(1, flow.level)
    }

    @Test fun theLaboratoryTripStillFades() {
        val flow = LevelFlow(); flow.startTrip()
        var swapped = false
        repeat(200) { if (flow.update(1f / 60f, 100f, 100f)) swapped = true }
        assertTrue(swapped); assertFalse(flow.lastSwapSeamless)
    }
}
