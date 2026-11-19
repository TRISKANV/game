package com.kotlinsurvivors.engine.map

import kotlin.math.floor

/**
 * Collision grid of a level: a square of [cells] x [cells] cells, each [cellSize] world
 * pixels wide, where a non-zero byte means "blocked". Everything outside the grid counts
 * as blocked too, so nothing can leave the map.
 *
 * Pure Kotlin on purpose (no Android types) so it can be unit-tested on the JVM.
 */
class LevelMap(
    val cells: Int,
    val cellSize: Float,
    private val blocked: ByteArray
) {
    init {
        require(cells > 0 && blocked.size == cells * cells) {
            "Collision grid has ${blocked.size} bytes, expected ${cells * cells}"
        }
    }

    val worldSize: Float = cells * cellSize

    fun isBlockedCell(cx: Int, cy: Int): Boolean {
        if (cx < 0 || cy < 0 || cx >= cells || cy >= cells) return true
        return blocked[cy * cells + cx].toInt() != 0
    }

    fun isBlockedAt(x: Float, y: Float): Boolean =
        isBlockedCell(floor(x / cellSize).toInt(), floor(y / cellSize).toInt())

    /** True when the box centred on (x, y) with the given half sizes touches no blocked cell. */
    fun isBoxFree(x: Float, y: Float, halfW: Float, halfH: Float): Boolean {
        val x0 = floor((x - halfW) / cellSize).toInt()
        val x1 = floor((x + halfW - EDGE_EPSILON) / cellSize).toInt()
        val y0 = floor((y - halfH) / cellSize).toInt()
        val y1 = floor((y + halfH - EDGE_EPSILON) / cellSize).toInt()
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                if (isBlockedCell(cx, cy)) return false
            }
        }
        return true
    }

    /**
     * Whether a body at (x, y) may move to (nx, ny). A body that is already stuck inside
     * a wall (pushed there by a knock-back, say) may move as long as its centre does not
     * go deeper into a blocked cell, so it can always walk back out.
     */
    fun canMove(x: Float, y: Float, nx: Float, ny: Float, halfW: Float, halfH: Float): Boolean {
        if (isBoxFree(nx, ny, halfW, halfH)) return true
        return !isBoxFree(x, y, halfW, halfH) && !isBlockedAt(nx, ny)
    }

    private companion object {
        const val EDGE_EPSILON = 0.01f
    }
}
