package com.kotlinsurvivors.engine.spatial

import kotlin.math.floor

/**
 * Growable list of primitive ints. Used for query results so the collision
 * code can walk them without boxing every entity id into an Integer.
 */
class IntList(initialCapacity: Int = 64) {

    private var data = IntArray(initialCapacity.coerceAtLeast(4))

    var size = 0
        private set

    operator fun get(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index=$index size=$size")
        return data[index]
    }

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun clear() {
        size = 0
    }

    fun contains(value: Int): Boolean {
        for (i in 0 until size) if (data[i] == value) return true
        return false
    }

    fun isEmpty(): Boolean = size == 0
}

/**
 * Uniform spatial hash grid rebuilt every frame.
 *
 * Everything lives in primitive arrays: a fixed table of bucket heads and a
 * pool of nodes chained through [nodeNext]. Inserting, clearing and querying
 * never allocate once the pools have grown to the size the game needs, so
 * the collision pass adds no garbage-collector pressure.
 *
 * An entity that overlaps several cells gets one node per cell. Queries
 * deduplicate with a stamp per entity id, so each id is reported once per
 * query no matter how many cells it covers.
 *
 * The grid is not thread-safe: the engine thread owns it.
 */
class SpatialGrid(
    private val cellSize: Float = 128f
) {

    private companion object {
        const val TABLE_SIZE = 4096                 // must be a power of two
        const val TABLE_MASK = TABLE_SIZE - 1
        const val INITIAL_NODES = 2048
        const val NO_NODE = -1
    }

    /** First node of each bucket, or [NO_NODE]. */
    private val heads = IntArray(TABLE_SIZE).also { it.fill(NO_NODE) }

    private var nodeId   = IntArray(INITIAL_NODES)
    private var nodeCx   = IntArray(INITIAL_NODES)
    private var nodeCy   = IntArray(INITIAL_NODES)
    private var nodeNext = IntArray(INITIAL_NODES)
    private var nodeCount = 0

    /** lastSeen[id] == queryStamp means id was already reported by the current query. */
    private var lastSeen = IntArray(1024)
    private var queryStamp = 0

    fun clear() {
        if (nodeCount > 0) heads.fill(NO_NODE)
        nodeCount = 0
    }

    fun insert(id: Int, tx: Float, ty: Float, radius: Float) {
        if (id < 0) return
        if (id >= lastSeen.size) lastSeen = lastSeen.copyOf(maxOf(id + 1, lastSeen.size * 2))

        val minCX = cellOf(tx - radius)
        val maxCX = cellOf(tx + radius)
        val minCY = cellOf(ty - radius)
        val maxCY = cellOf(ty + radius)

        for (cx in minCX..maxCX) {
            for (cy in minCY..maxCY) {
                if (nodeCount == nodeId.size) growNodes()
                val node   = nodeCount++
                val bucket = bucketOf(cx, cy)
                nodeId[node]   = id
                nodeCx[node]   = cx
                nodeCy[node]   = cy
                nodeNext[node] = heads[bucket]
                heads[bucket]  = node
            }
        }
    }

    /** Appends the ids found around ([tx], [ty]) to [result] without duplicates. */
    fun query(tx: Float, ty: Float, radius: Float, result: IntList) {
        scan(tx, ty, radius) { result.add(it) }
    }

    /** Same as the [IntList] overload, for callers that work with boxed lists. */
    fun query(tx: Float, ty: Float, radius: Float, result: MutableList<Int>) {
        scan(tx, ty, radius) { result.add(it) }
    }

    private inline fun scan(tx: Float, ty: Float, radius: Float, onFound: (Int) -> Unit) {
        val minCX = cellOf(tx - radius)
        val maxCX = cellOf(tx + radius)
        val minCY = cellOf(ty - radius)
        val maxCY = cellOf(ty + radius)

        val stamp = nextStamp()

        for (cx in minCX..maxCX) {
            for (cy in minCY..maxCY) {
                var node = heads[bucketOf(cx, cy)]
                while (node != NO_NODE) {
                    // Different cells can share a bucket; only take nodes of this cell.
                    if (nodeCx[node] == cx && nodeCy[node] == cy) {
                        val id = nodeId[node]
                        if (lastSeen[id] != stamp) {
                            lastSeen[id] = stamp
                            onFound(id)
                        }
                    }
                    node = nodeNext[node]
                }
            }
        }
    }

    private fun nextStamp(): Int {
        if (queryStamp == Int.MAX_VALUE) {
            lastSeen.fill(0)
            queryStamp = 0
        }
        return ++queryStamp
    }

    private fun growNodes() {
        val newSize = nodeId.size * 2
        nodeId   = nodeId.copyOf(newSize)
        nodeCx   = nodeCx.copyOf(newSize)
        nodeCy   = nodeCy.copyOf(newSize)
        nodeNext = nodeNext.copyOf(newSize)
    }

    private fun cellOf(world: Float): Int = floor(world / cellSize).toInt()

    private fun bucketOf(cx: Int, cy: Int): Int =
        ((cx * 73856093) xor (cy * 19349663)) and TABLE_MASK
}
