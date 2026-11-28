package com.kotlinsurvivors

import com.kotlinsurvivors.engine.KeySearch
import com.kotlinsurvivors.engine.SearchResult
import com.kotlinsurvivors.engine.SpotKind
import com.kotlinsurvivors.engine.map.Level3
import com.kotlinsurvivors.engine.map.LevelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The two keys of the station hidden in places where a key could be, and the search that finds them. */
class KeySearchTest {
    private val kinds = Level3.SPOT_NAMES.map { SpotKind.valueOf(it) }
    private fun search() = KeySearch(kinds, Level3.SPOT_X, Level3.SPOT_Y).also { it.reset(Random(1)) }
    private fun station() = LevelMap(Level3.CELLS, Level3.CELL_SIZE, File("src/main/assets/sprites/station_solid.bin").readBytes())

    @Test fun theLevelListsEveryKindOfPlaceExactlyOnceOrTwiceForTheLockers() {
        assertEquals(SpotKind.entries.toSet(), kinds.toSet())
        assertEquals(SpotKind.entries.size, kinds.size)
    }

    @Test fun everyPlaceIsOnFreeFloorTheSpawnCanReach() {
        val map = station()
        for (i in kinds.indices) {
            val x = Level3.SPOT_X[i]; val y = Level3.SPOT_Y[i]
            assertFalse("${kinds[i]} stands on a wall", map.isBlockedAt(x, y))
            assertTrue("${kinds[i]} cannot be reached", reachable(map, Level3.SPAWN_X, Level3.SPAWN_Y, x, y))
        }
    }

    private fun reachable(map: LevelMap, fx: Float, fy: Float, tx: Float, ty: Float): Boolean {
        val n = map.cells; val cs = map.cellSize
        val seen = BooleanArray(n * n)
        val stack = ArrayDeque<Int>()
        val s = (fy / cs).toInt() * n + (fx / cs).toInt()
        stack.addLast(s); seen[s] = true
        val goal = (ty / cs).toInt() * n + (tx / cs).toInt()
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (c == goal) return true
            val cx = c % n; val cy = c / n
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                if (map.isBlockedCell(cx + dx, cy + dy)) continue
                val i = (cy + dy) * n + cx + dx
                if (!seen[i]) { seen[i] = true; stack.addLast(i) }
            }
        }
        return false
    }

    @Test fun theKeysAlwaysHideWhereTheirKindOfPlaceAllowsAndNeverTogether() {
        val ks = KeySearch(kinds, Level3.SPOT_X, Level3.SPOT_Y)
        val cellSpots = HashSet<Int>(); val exitSpots = HashSet<Int>()
        for (seed in 0 until 300) {
            ks.reset(Random(seed))
            assertTrue(kinds[ks.cellKeySpot].hidesCellKey)
            assertTrue(kinds[ks.exitKeySpot].hidesExitKey)
            assertNotEquals(ks.cellKeySpot, ks.exitKeySpot)
            cellSpots += ks.cellKeySpot; exitSpots += ks.exitKeySpot
        }
        // over many runs the keys really do move about
        assertTrue("cell key places: ${cellSpots.size}", cellSpots.size >= 3)
        assertTrue("street key places: ${exitSpots.size}", exitSpots.size >= 3)
    }

    private fun holdAt(ks: KeySearch, spot: Int, seconds: Float = KeySearch.SEARCH_SECONDS + 0.2f): SearchResult? {
        var result: SearchResult? = null
        var t = 0f
        var first = true
        while (t < seconds && result == null) {
            result = ks.update(1f / 60f, Level3.SPOT_X[spot], Level3.SPOT_Y[spot], first, true, true)
            first = false
            t += 1f / 60f
        }
        return result
    }

    @Test fun holdingTheButtonAtThePlaceFindsWhatIsThere() {
        val ks = search()
        assertEquals(SearchResult.CellKey(ks.cellKeySpot), holdAt(ks, ks.cellKeySpot))
        assertEquals(SearchResult.ExitKey(ks.exitKeySpot), holdAt(ks, ks.exitKeySpot))
        val empty = kinds.indices.first { it != ks.cellKeySpot && it != ks.exitKeySpot }
        val r = holdAt(ks, empty)
        assertTrue(r is SearchResult.Nothing)
        assertEquals(kinds[empty], (r as SearchResult.Nothing).kind)
    }

    @Test fun aPlaceIsSearchedOnlyOnce() {
        val ks = search()
        val spot = ks.cellKeySpot
        assertNotNull(holdAt(ks, spot))
        assertTrue(ks.searched(spot))
        assertNull(holdAt(ks, spot))
        assertEquals(-1, ks.target)
    }

    @Test fun walkingAwayLosesTheProgress() {
        val ks = search()
        val spot = 0
        repeat(40) { ks.update(1f / 60f, Level3.SPOT_X[spot], Level3.SPOT_Y[spot], it == 0, true, true) }
        assertTrue(ks.progress > 0f)
        ks.update(1f / 60f, Level3.SPOT_X[spot] + 600f, Level3.SPOT_Y[spot], false, true, true)
        assertEquals(0f, ks.progress, 0f)
        assertEquals(-1, ks.target)
    }

    @Test fun aButtonAlreadyDownWhenTheGuyWalksUpSearchesNothing() {
        val ks = search()
        repeat(300) { assertNull(ks.update(1f / 60f, Level3.SPOT_X[0], Level3.SPOT_Y[0], false, true, true)) }
        assertEquals(0f, ks.progress, 0f)
        assertFalse(ks.searched(0))
    }

    @Test fun nothingIsSearchedWhileThePlayerCannot() {
        val ks = search()
        repeat(200) { assertNull(ks.update(1f / 60f, Level3.SPOT_X[0], Level3.SPOT_Y[0], it == 0, true, false)) }
        assertEquals(-1, ks.target)
    }

    @Test fun resetHidesTheKeysAgainAndForgetsTheSearches() {
        val ks = search()
        holdAt(ks, 0)
        ks.reset(Random(99))
        assertFalse(ks.searched(0))
    }
}
