package com.kotlinsurvivors.engine.spatial

import com.kotlinsurvivors.engine.ecs.components.TransformComponent
import kotlin.math.floor

/**
 * Uniform spatial grid for broad-phase collision detection.
 *
 * Instead of O(n²) pair checks, each entity is inserted into one or more cells.
 * Queries only check entities in the same or adjacent cells → typically O(k)
 * where k is the local entity density, far smaller than n.
 *
 * Cell size should be roughly 2× the largest collider radius for best performance.
 */
class SpatialGrid(
    private val cellSize: Float = 128f
) {
    // cell key → list of entity IDs in that cell
    private val cells = HashMap<Long, MutableList<Int>>(512)

    // entity → set of cells it occupies (for removal)
    private val entityCells = HashMap<Int, MutableList<Long>>(1024)

    fun clear() {
        cells.clear()
        entityCells.clear()
    }

    /**
     * Insert entity at world position (tx, ty) with a circular extent of [radius].
     * An entity may span multiple cells if its radius is large.
     */
    fun insert(id: Int, tx: Float, ty: Float, radius: Float) {
        val minCX = cellX(tx - radius)
        val maxCX = cellX(tx + radius)
        val minCY = cellY(ty - radius)
        val maxCY = cellY(ty + radius)

        val occupied = entityCells.getOrPut(id) { mutableListOf() }

        for (cx in minCX..maxCX) {
            for (cy in minCY..maxCY) {
                val key = packKey(cx, cy)
                cells.getOrPut(key) { mutableListOf() }.add(id)
                occupied.add(key)
            }
        }
    }

    /**
     * Query all entity IDs in cells overlapping the given circle.
     * Result may include the querying entity itself — callers must filter.
     */
    fun query(tx: Float, ty: Float, radius: Float, result: MutableList<Int>) {
        val minCX = cellX(tx - radius)
        val maxCX = cellX(tx + radius)
        val minCY = cellY(ty - radius)
        val maxCY = cellY(ty + radius)

        val seen = HashSet<Int>()
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

    /** Pack two ints into a single Long key — avoids allocating Pair objects. */
    private fun packKey(cx: Int, cy: Int): Long =
        (cx.toLong() and 0xFFFFFFFFL) or (cy.toLong() shl 32)
}
