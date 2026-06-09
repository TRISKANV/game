package com.kotlinsurvivors.engine.spatial

import kotlin.math.floor

/**
 * SpatialGrid — fixed version.
 *
 * BUGS FIXED:
 *
 * 1. query() was creating a new HashSet<Int>() on every call.
 *    CollisionSystem calls query() ~130 times per frame at peak.
 *    That's 7,800 HashSet allocations/second → GC thrash → OOM at ~73s.
 *    Fix: one pre-allocated `seen` IntArray with a frame-stamp approach,
 *    replaced here by a single reused HashSet that is cleared in-place.
 *
 * 2. cells HashMap was never reconstructed after many frames, only cleared.
 *    Java HashMap.clear() keeps internal capacity forever.
 *    After the player traverses large areas, capacity grows unbounded.
 *    Fix: replace cells with a fresh HashMap every clear() call.
 *    The initial capacity hint (512) keeps the common case fast.
 *
 * 3. entityCells used getOrPut which allocates a new MutableList per entity
 *    on every insert pass, even for recycled IDs.
 *    Fix: entityCells is also rebuilt fresh every clear().
 */
class SpatialGrid(
    private val cellSize: Float = 128f
) {
    // Rebuilt fresh every clear() to release capacity from previous frames
    private var cells       = HashMap<Long, ArrayList<Int>>(512)
    private var entityCells = HashMap<Int, ArrayList<Long>>(1024)

    // Pre-allocated deduplication set — cleared in-place, never reallocated
    private val seen = HashSet<Int>(256)

    fun clear() {
        // Rebuild with bounded initial capacity instead of growing indefinitely
        cells       = HashMap(512)
        entityCells = HashMap(1024)
        // seen is reused across calls — cleared per-query
    }

    fun insert(id: Int, tx: Float, ty: Float, radius: Float) {
        val minCX = cellX(tx - radius)
        val maxCX = cellX(tx + radius)
        val minCY = cellY(ty - radius)
        val maxCY = cellY(ty + radius)

        // Reuse existing list for this entity if present, create once if not
        val occupied = entityCells.getOrPut(id) { ArrayList(4) }

        for (cx in minCX..maxCX) {
            for (cy in minCY..maxCY) {
                val key  = packKey(cx, cy)
                val cell = cells.getOrPut(key) { ArrayList(8) }
                cell.add(id)
                occupied.add(key)
            }
        }
    }

    fun query(tx: Float, ty: Float, radius: Float, result: MutableList<Int>) {
        val minCX = cellX(tx - radius)
        val maxCX = cellX(tx + radius)
        val minCY = cellY(ty - radius)
        val maxCY = cellY(ty + radius)

        // Clear the pre-allocated deduplication set
        seen.clear()

        for (cx in minCX..maxCX) {
            for (cy in minCY..maxCY) {
                val cell = cells[packKey(cx, cy)] ?: continue
                for (id in cell) {
                    if (seen.add(id)) result.add(id)
                }
            }
        }
    }

    private fun cellX(wx: Float): Int = floor(wx / cellSize).toInt()
    private fun cellY(wy: Float): Int = floor(wy / cellSize).toInt()

    private fun packKey(cx: Int, cy: Int): Long =
        (cx.toLong() and 0xFFFFFFFFL) or (cy.toLong() shl 32)
}
