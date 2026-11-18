package com.kotlinsurvivors.features.room.domain

import com.kotlinsurvivors.engine.rendering.Facing
import com.kotlinsurvivors.features.room.domain.RoomLayout.Box

/**
 * State and movement rules of the Level 0 room.
 *
 * There are no enemies, weapons or waves here: the protagonist just walks around.
 * The class has no Android dependencies, so the rules can be unit tested.
 *
 * Positions are the character's feet, in pixels of the room image (see [RoomLayout]).
 * Only a small box at the feet takes part in collisions, which lets the head and
 * shoulders pass in front of the walls the way a top-down game expects.
 */
class RoomWorld {

    var x = RoomLayout.START_X
        private set
    var y = RoomLayout.START_Y
        private set

    /** Row of the character sprite sheet to draw, see [Facing]. */
    var facing = Facing.DOWN
        private set

    var isMoving = false
        private set

    /** Seconds spent walking; drives the walk animation. */
    var walkClock = 0f
        private set

    /** True once the player has stepped on the door mat. Stays true. */
    var hasReachedDoor = false
        private set

    /**
     * Advances the room by [dt] seconds.
     * [inputX] and [inputY] are the joystick tilt, each in -1..1.
     */
    fun update(dt: Float, inputX: Float, inputY: Float) {
        val vx = inputX * WALK_SPEED
        val vy = inputY * WALK_SPEED
        isMoving = vx * vx + vy * vy > MIN_MOVING_SPEED_SQ

        if (isMoving) {
            facing = Facing.of(vx, vy)
            walkClock += dt

            // Each axis is moved on its own, so walking diagonally into a wall slides along it.
            val nextX = x + vx * dt
            if (canStandAt(nextX, y)) x = nextX
            val nextY = y + vy * dt
            if (canStandAt(x, nextY)) y = nextY
        }

        if (!hasReachedDoor && feetBox(x, y).intersects(RoomLayout.door)) hasReachedDoor = true
    }

    /** Whether the feet box fits at ([px], [py]): inside the walls and clear of furniture. */
    fun canStandAt(px: Float, py: Float): Boolean {
        val box = feetBox(px, py)
        val area = RoomLayout.walkable
        if (box.x < area.x || box.y < area.y || box.right > area.right || box.bottom > area.bottom) return false
        return RoomLayout.furniture.none { it.box.intersects(box) }
    }

    private fun feetBox(px: Float, py: Float) =
        Box(px - FEET_HALF_WIDTH, py - FEET_HALF_HEIGHT, FEET_HALF_WIDTH * 2f, FEET_HALF_HEIGHT * 2f)

    companion object {
        /** Walking speed in room-image pixels per second (the room is 640 pixels wide). */
        const val WALK_SPEED = 90f

        const val FEET_HALF_WIDTH  = 7f
        const val FEET_HALF_HEIGHT = 4f

        private const val MIN_MOVING_SPEED_SQ = 25f
    }
}
