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

    /**
     * Blocks or frees every cell the rectangle (in world pixels) touches. Used for the gate at the
     * end of the street, which stays shut until the first boss is down.
     */
    fun setRect(x0: Float, y0: Float, x1: Float, y1: Float, isBlocked: Boolean) {
        val value: Byte = if (isBlocked) 1 else 0
        val cx0 = floor(x0 / cellSize).toInt().coerceAtLeast(0)
        val cx1 = floor((x1 - EDGE_EPSILON) / cellSize).toInt().coerceAtMost(cells - 1)
        val cy0 = floor(y0 / cellSize).toInt().coerceAtLeast(0)
        val cy1 = floor((y1 - EDGE_EPSILON) / cellSize).toInt().coerceAtMost(cells - 1)
        for (cy in cy0..cy1) for (cx in cx0..cx1) blocked[cy * cells + cx] = value
    }

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

    /** True when no blocked cell lies on the straight line from (x0, y0) to (x1, y1). */
    fun hasLineOfSight(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) / (cellSize * 0.5f)).toInt() + 1
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            if (isBlockedAt(x0 + dx * f, y0 + dy * f)) return false
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
