package com.kotlinsurvivors

import com.kotlinsurvivors.engine.rendering.Facing
import com.kotlinsurvivors.features.room.domain.RoomLayout
import com.kotlinsurvivors.features.room.domain.RoomWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWorldTest {

    private val step = 1f / 60f

    private fun walk(world: RoomWorld, seconds: Float, ix: Float, iy: Float) {
        repeat((seconds / step).toInt()) { world.update(step, ix, iy) }
    }

    @Test
    fun startsOnTheRugAndFree() {
        val world = RoomWorld()
        assertTrue(world.canStandAt(world.x, world.y))
        assertEquals(RoomLayout.START_X, world.x, 0.001f)
        assertFalse(world.hasReachedDoor)
    }

    @Test
    fun standingStillChangesNothing() {
        val world = RoomWorld()
        walk(world, 1f, 0f, 0f)
        assertEquals(RoomLayout.START_X, world.x, 0.001f)
        assertEquals(RoomLayout.START_Y, world.y, 0.001f)
        assertFalse(world.isMoving)
    }

    @Test
    fun walksAtTheConfiguredSpeed() {
        val world = RoomWorld()
        walk(world, 1f, 1f, 0f)
        assertEquals(RoomLayout.START_X + RoomWorld.WALK_SPEED, world.x, 2f)
        assertEquals(Facing.RIGHT, world.facing)
        assertTrue(world.isMoving)
    }

    @Test
    fun deskStopsThePlayerFromWalkingNorth() {
        val world = RoomWorld()
        walk(world, 1f, 0f, -1f)                       // straight up from the rug, towards the chair and desk
        walk(world, 3f, 0f, -1f)
        val chair = RoomLayout.furniture.first { it.name == "desk_chair" }.box
        assertTrue("stopped below the chair", world.y - RoomWorld.FEET_HALF_HEIGHT >= chair.bottom - 0.01f)
        assertTrue(world.canStandAt(world.x, world.y))
    }

    @Test
    fun cannotLeaveTheRoom() {
        val world = RoomWorld()
        walk(world, 10f, -1f, 0f)
        walk(world, 10f, 0f, 1f)
        walk(world, 10f, 1f, 0f)
        walk(world, 10f, 0f, -1f)
        val area = RoomLayout.walkable
        assertTrue(world.x >= area.x && world.x <= area.right)
        assertTrue(world.y >= area.y && world.y <= area.bottom)
    }

    @Test
    fun slidesAlongAWallWhenMovingDiagonally() {
        val world = RoomWorld()
        walk(world, 0.35f, 0f, 1f)                     // step down to a row with nothing in the way
        walk(world, 10f, 1f, 0f)                       // reach the right wall
        val xAtWall = world.x
        val yBefore = world.y
        walk(world, 0.5f, 1f, 1f)                      // push into the wall and down
        assertEquals(xAtWall, world.x, 0.01f)
        assertTrue("still moved along the wall", world.y > yBefore)
    }

    @Test
    fun furnitureNeverOverlapsTheStartPosition() {
        val world = RoomWorld()
        assertTrue(world.canStandAt(RoomLayout.START_X, RoomLayout.START_Y))
        for (item in RoomLayout.furniture) {
            val b = item.box
            assertTrue("${item.name} is inside the image", b.x >= 0f && b.y >= 0f && b.right <= RoomLayout.WIDTH && b.bottom <= RoomLayout.HEIGHT)
        }
    }

    @Test
    fun theDoorCanBeReachedFromTheStart() {
        // Breadth-first search over a 2 px grid: proves furniture does not wall the door off.
        val world = RoomWorld()
        val grid = 2
        val cols = (RoomLayout.WIDTH / grid).toInt()
        val rows = (RoomLayout.HEIGHT / grid).toInt()
        fun free(cx: Int, cy: Int) = cx in 0 until cols && cy in 0 until rows && world.canStandAt(cx * grid.toFloat(), cy * grid.toFloat())
        val startX = (RoomLayout.START_X / grid).toInt()
        val startY = (RoomLayout.START_Y / grid).toInt()
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(startY * cols + startX)
        seen.add(startY * cols + startX)
        var reached = false
        val door = RoomLayout.door
        while (queue.isNotEmpty() && !reached) {
            val cur = queue.removeFirst()
            val cx = cur % cols
            val cy = cur / cols
            val px = cx * grid.toFloat()
            val py = cy * grid.toFloat()
            if (px >= door.x && px <= door.right && py + RoomWorld.FEET_HALF_HEIGHT >= door.y) { reached = true; break }
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = cx + dx
                val ny = cy + dy
                val key = ny * cols + nx
                if (free(nx, ny) && seen.add(key)) queue.add(key)
            }
        }
        assertTrue("the door is reachable", reached)
    }

    @Test
    fun steppingOnTheDoorMatMarksTheExit() {
        val world = RoomWorld()
        // Walk to the door column, then down onto the mat.
        val door = RoomLayout.door
        val targetX = door.x + door.w / 2f
        while (world.x < targetX - 1f) world.update(step, 1f, 0f)
        while (world.x > targetX + 1f) world.update(step, -1f, 0f)
        walk(world, 6f, 0f, 1f)
        assertTrue(world.hasReachedDoor)
    }

    @Test
    fun walkClockOnlyAdvancesWhileWalking() {
        val world = RoomWorld()
        walk(world, 0.5f, 0f, 0f)
        assertEquals(0f, world.walkClock, 0f)
        walk(world, 0.5f, 1f, 0f)
        assertTrue(world.walkClock > 0.4f)
    }
}
