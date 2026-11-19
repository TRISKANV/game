package com.kotlinsurvivors.engine.map

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Walking distance from a target (the player) to every nearby free cell of a [LevelMap],
 * so an enemy stuck behind a building can just step to the neighbouring cell with the
 * smallest distance and end up going round it.
 *
 * The distances are found with a breadth-first search over cells that have a free cell
 * all around them (about 48 px of clearance), so anything the size of an enemy fits where
 * the field sends it. The search stops [MAX_STEPS] cells from the target: farther
 * enemies are not stuck behind anything yet and simply walk straight at the player.
 *
 * All the arrays are allocated once; [update] and [directionAt] do not allocate.
 */
class FlowField(private val map: LevelMap) {

    private val n = map.cells
    private val walkable = BooleanArray(n * n)
    private val steps    = IntArray(n * n)
    private val stamp    = IntArray(n * n)     // == generation when steps[] is valid for this search
    private val queue    = IntArray(n * n)
    private var generation = 0

    private var targetCell  = -1
    private var sinceUpdate = 0f

    /** Unit direction written by [directionAt]. */
    var dirX = 0f; private set
    var dirY = 0f; private set

    init {
        for (cy in 0 until n) for (cx in 0 until n) {
            var clear = true
            for (oy in -1..1) for (ox in -1..1) if (map.isBlockedCell(cx + ox, cy + oy)) clear = false
            walkable[cy * n + cx] = clear
        }
    }

    /**
     * Keeps the field centred on (x, y). The search runs again when the target enters
     * another cell, at most every [MIN_INTERVAL] seconds (a full search is a few
     * thousand cells), and in any case every [MAX_INTERVAL] seconds.
     */
    fun update(x: Float, y: Float, dt: Float) {
        sinceUpdate += dt
        val cx = floor(x / map.cellSize).toInt().coerceIn(0, n - 1)
        val cy = floor(y / map.cellSize).toInt().coerceIn(0, n - 1)
        val cell = cy * n + cx
        val moved = cell != targetCell
        if ((moved && sinceUpdate >= MIN_INTERVAL) || sinceUpdate >= MAX_INTERVAL || targetCell < 0) {
            search(cx, cy)
            targetCell  = cell
            sinceUpdate = 0f
        }
    }

    private fun search(tx: Int, ty: Int) {
        generation++
        var head = 0
        var tail = 0
        // The target may stand next to a wall, on a cell that is not "walkable": it still
        // starts the search, together with the walkable cells around it.
        val start = ty * n + tx
        stamp[start] = generation; steps[start] = 0; queue[tail++] = start
        for (oy in -1..1) for (ox in -1..1) {
            val cx = tx + ox; val cy = ty + oy
            if (cx < 0 || cy < 0 || cx >= n || cy >= n) continue
            val i = cy * n + cx
            if (stamp[i] == generation || !walkable[i]) continue
            stamp[i] = generation; steps[i] = 1; queue[tail++] = i
        }
        tail = flood(0, tail, clearanceOnly = true)
        // A target in a narrow spot (a gap between a barrier and the border, say) is not
        // reached by the clearance search at all. Then let the search squeeze through
        // every free cell, starting again from what it has found, so enemies can still
        // find the way in.
        if (tail < NARROW_SPOT_CELLS) flood(0, tail, clearanceOnly = false)
    }

    /** Breadth-first expansion of the queue from [from] on; returns the new end of the queue. */
    private fun flood(from: Int, end: Int, clearanceOnly: Boolean): Int {
        var head = from
        var tail = end
        while (head < tail) {
            val cur = queue[head++]
            val d = steps[cur]
            if (d >= MAX_STEPS) continue
            val cx = cur % n; val cy = cur / n
            for (k in 0 until 4) {
                val nx = cx + DX4[k]; val ny = cy + DY4[k]
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                val i = ny * n + nx
                if (stamp[i] == generation) continue
                if (if (clearanceOnly) !walkable[i] else map.isBlockedCell(nx, ny)) continue
                stamp[i] = generation; steps[i] = d + 1; queue[tail++] = i
            }
        }
        return tail
    }

    /**
     * Sets [dirX]/[dirY] to the direction an enemy standing at (x, y) should walk to get
     * closer to the target. False when the field knows nothing about that spot (too far
     * from the target, or walled in): the caller then walks straight at the player.
     */
    fun directionAt(x: Float, y: Float): Boolean {
        val cx = floor(x / map.cellSize).toInt()
        val cy = floor(y / map.cellSize).toInt()
        if (cx < 0 || cy < 0 || cx >= n || cy >= n) return false
        var best = Int.MAX_VALUE
        var bx = 0; var by = 0
        for (k in 0 until 8) {
            val nx = cx + DX8[k]; val ny = cy + DY8[k]
            if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
            val i = ny * n + nx
            if (stamp[i] != generation || steps[i] >= best) continue
            // No cutting corners: a diagonal step needs both cells beside it to be free.
            if (DX8[k] != 0 && DY8[k] != 0 &&
                (map.isBlockedCell(cx + DX8[k], cy) || map.isBlockedCell(cx, cy + DY8[k]))) continue
            best = steps[i]; bx = nx; by = ny
        }
        if (best == Int.MAX_VALUE) return false
        val tx = (bx + 0.5f) * map.cellSize - x
        val ty = (by + 0.5f) * map.cellSize - y
        val len = sqrt(tx * tx + ty * ty)
        if (len < 0.001f) return false
        dirX = tx / len; dirY = ty / len
        return true
    }

    private companion object {
        const val MAX_STEPS    = 110
        const val NARROW_SPOT_CELLS = 400   // fewer cells than this found: the target is boxed in
        const val MIN_INTERVAL = 0.12f
        const val MAX_INTERVAL = 0.5f
        val DX4 = intArrayOf(1, -1, 0, 0)
        val DY4 = intArrayOf(0, 0, 1, -1)
        val DX8 = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DY8 = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
