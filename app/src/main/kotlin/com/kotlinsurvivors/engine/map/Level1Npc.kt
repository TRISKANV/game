package com.kotlinsurvivors.engine.map

import kotlin.math.floor

/**
 * The neighbour of Level 1: he stands in the street in front of the parked car, looking at his
 * phone, and comments on whoever comes close. His position is in world pixels; [FEET_Y] is where
 * his boots touch the ground.
 *
 * He is solid: [stampSolid] marks the ground under his feet as blocked in the level's collision
 * grid, so the player walks around him and the enemies' paths do too.
 */
object Level1Npc {
    const val X       = 1048f
    const val FEET_Y  = 846f

    /** His sprite sheet (npc_leek.png): [FRAMES] cells of [FRAME_W] x [FRAME_H] pixels side by side. */
    const val FRAME_W = 42
    const val FRAME_H = 60
    const val FRAMES  = 7

    /** Row of the cell where the soles end; the boots are drawn so this row sits on [FEET_Y]. */
    const val FEET_ROW = 58

    /** World pixels per sprite pixel: the same as the player, so both look drawn with the same pen. */
    const val SCALE = 1.8f

    /** Distance from his boots to the top of his leaves in world pixels; the bubble floats above it. */
    const val SPRITE_HEIGHT = 100f

    /** He starts talking when the player's feet come this close, and stops when they go beyond [LEAVE_RADIUS]. */
    const val TALK_RADIUS  = 280f
    const val LEAVE_RADIUS = 330f

    /** How many different things he can say (strings npc_leek_1 .. npc_leek_N). */
    const val LINE_COUNT = 14

    private const val HALF_WIDTH = 22f
    private const val DEPTH_BACK = 10f
    private const val DEPTH_FRONT = 6f

    /** Marks the cells under his boots as blocked in [blocked], a grid of [cells] x [cells] cells of [cellSize]. */
    fun stampSolid(blocked: ByteArray, cells: Int, cellSize: Float) {
        val x0 = floor((X - HALF_WIDTH) / cellSize).toInt()
        val x1 = floor((X + HALF_WIDTH - 0.01f) / cellSize).toInt()
        val y0 = floor((FEET_Y - DEPTH_BACK) / cellSize).toInt()
        val y1 = floor((FEET_Y + DEPTH_FRONT - 0.01f) / cellSize).toInt()
        for (cy in y0..y1) for (cx in x0..x1) {
            if (cx in 0 until cells && cy in 0 until cells) blocked[cy * cells + cx] = 1
        }
    }
}
