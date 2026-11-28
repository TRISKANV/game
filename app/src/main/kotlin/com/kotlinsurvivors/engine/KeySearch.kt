package com.kotlinsurvivors.engine

import kotlin.math.hypot
import kotlin.random.Random

/** The kinds of place in the station where something can be searched, in the order the level lists them. */
enum class SpotKind(val hidesCellKey: Boolean = false, val hidesExitKey: Boolean = false) {
    JAILER_DESK(hidesCellKey = true),
    LOCKERS_WEST(hidesCellKey = true),
    LOCKERS_EAST(hidesCellKey = true),
    LOCKER_BENCH(hidesCellKey = true),
    CHIEF_DESK(hidesExitKey = true),
    BOOKSHELF_WEST,
    BOOKSHELF_EAST,
    SOFA(hidesExitKey = true),
    INTERRO_TABLE,
    FILING_CABINET(hidesExitKey = true),
    EVIDENCE_CRATES(hidesExitKey = true),
    COUNTER(hidesExitKey = true),
    FRIDGE,
    VENDING
}

/** What a finished search turned up. */
sealed interface SearchResult {
    data class CellKey(val spot: Int) : SearchResult
    data class ExitKey(val spot: Int) : SearchResult
    data class Nothing(val spot: Int, val kind: SpotKind) : SearchResult
}

/**
 * The two keys of the station, hidden in places where a key could be, and the search that finds them.
 *
 * Each run the key of the cell goes into one of the places where a jailer or an officer would keep it, and the key
 * of the street door into one of the places where the commissioner or the front desk would; every other place is
 * empty. A place is searched by holding the button next to it for [SEARCH_SECONDS]; it can only be searched once.
 * Pure Kotlin, no Android types.
 */
class KeySearch(
    private val kinds: List<SpotKind>,
    private val xs: FloatArray,
    private val ys: FloatArray
) {
    init { require(kinds.size == xs.size && xs.size == ys.size) { "every place needs a kind and a position" } }

    val spotCount: Int get() = kinds.size
    private val done = BooleanArray(kinds.size)

    var cellKeySpot = -1; private set
    var exitKeySpot = -1; private set

    /** Which place the button would search now, or -1. */
    var target = -1; private set
    /** 0..1 while the button is held next to [target]. */
    var progress = 0f; private set
    private var armed = false

    /** Hides the keys again and forgets what has been searched. */
    fun reset(random: Random) {
        done.fill(false)
        cellKeySpot = pick(random) { it.hidesCellKey }
        exitKeySpot = pick(random) { it.hidesExitKey }
        target = -1; progress = 0f; armed = false
    }

    private fun pick(random: Random, fits: (SpotKind) -> Boolean): Int {
        val candidates = kinds.indices.filter { fits(kinds[it]) }
        return candidates[random.nextInt(candidates.size)]
    }

    fun searched(spot: Int): Boolean = done[spot]

    /**
     * Moves the search along. [pressed] is true on the frame the button went down, [held] while it stays down;
     * [canSearch] is false while something else has the player's hands (a blow, a trip between levels).
     * Returns what the search turned up on the frame it finishes, otherwise null.
     */
    fun update(dt: Float, px: Float, py: Float, pressed: Boolean, held: Boolean, canSearch: Boolean): SearchResult? {
        var best = -1
        var bestD = REACH
        if (canSearch) for (i in kinds.indices) {
            if (done[i]) continue
            val d = hypot(xs[i] - px, ys[i] - py)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best != target) { target = best; progress = 0f; armed = false }
        if (best == -1) return null
        // Only a finger that went down next to the place searches it; a button already down does nothing
        if (pressed) armed = true
        if (!held) armed = false
        progress = if (held && armed) (progress + dt / SEARCH_SECONDS).coerceAtMost(1f)
                   else (progress - dt / SEARCH_SECONDS * FALLBACK).coerceAtLeast(0f)
        if (progress < 1f) return null

        done[best] = true
        target = -1; progress = 0f; armed = false
        return when (best) {
            cellKeySpot -> SearchResult.CellKey(best)
            exitKeySpot -> SearchResult.ExitKey(best)
            else        -> SearchResult.Nothing(best, kinds[best])
        }
    }

    companion object {
        /** How close the player must be to the place he searches, from the point where he stands to do it. */
        const val REACH = 84f
        const val SEARCH_SECONDS = 1.4f
        private const val FALLBACK = 2f
    }
}
