package com.kotlinsurvivors

import com.kotlinsurvivors.engine.spatial.IntList
import com.kotlinsurvivors.engine.spatial.SpatialGrid
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.random.Random

class SpatialGridPrimitiveTest {

    private val cellSize = 100f

    private fun cellOf(v: Float) = floor(v / cellSize).toInt()

    @Test
    fun `entity spanning several cells is reported once`() {
        val grid = SpatialGrid(cellSize)
        grid.insert(7, 100f, 100f, 150f)
        val result = IntList()
        grid.query(100f, 100f, 300f, result)
        assertEquals(1, result.size)
        assertEquals(7, result[0])
    }

    @Test
    fun `negative coordinates are handled`() {
        val grid = SpatialGrid(cellSize)
        grid.insert(1, -250f, -480f, 10f)
        grid.insert(2, 250f, 480f, 10f)
        val result = IntList()
        grid.query(-250f, -480f, 20f, result)
        assertTrue(result.contains(1))
        assertFalse(result.contains(2))
    }

    @Test
    fun `grid keeps working after clear and reuse`() {
        val grid = SpatialGrid(cellSize)
        repeat(5) { round ->
            grid.clear()
            grid.insert(round + 1, 50f, 50f, 5f)
            val result = IntList()
            grid.query(50f, 50f, 5f, result)
            assertEquals(1, result.size)
            assertEquals(round + 1, result[0])
        }
    }

    @Test
    fun `pools grow when many entities are inserted`() {
        val grid = SpatialGrid(cellSize)
        for (id in 1..5000) grid.insert(id, (id % 70) * 100f, (id / 70) * 100f, 5f)
        val result = IntList()
        grid.query(0f, 0f, 20000f, result)
        assertEquals(5000, result.size)
    }

    @Test
    fun `results match a brute force search on random data`() {
        val rnd  = Random(7)
        val grid = SpatialGrid(cellSize)
        repeat(20) {
            grid.clear()
            val entities = List(rnd.nextInt(1, 500)) {
                floatArrayOf(rnd.nextFloat() * 4000 - 2000, rnd.nextFloat() * 4000 - 2000, rnd.nextFloat() * 200)
            }
            entities.forEachIndexed { i, e -> grid.insert(i + 1, e[0], e[1], e[2]) }

            repeat(40) {
                val qx = rnd.nextFloat() * 4000 - 2000
                val qy = rnd.nextFloat() * 4000 - 2000
                val qr = rnd.nextFloat() * 400

                val expected = HashSet<Int>()
                entities.forEachIndexed { i, e ->
                    val overlapsX = cellOf(e[0] - e[2]) <= cellOf(qx + qr) && cellOf(qx - qr) <= cellOf(e[0] + e[2])
                    val overlapsY = cellOf(e[1] - e[2]) <= cellOf(qy + qr) && cellOf(qy - qr) <= cellOf(e[1] + e[2])
                    if (overlapsX && overlapsY) expected.add(i + 1)
                }

                val primitive = IntList()
                grid.query(qx, qy, qr, primitive)
                val boxed = ArrayList<Int>()
                grid.query(qx, qy, qr, boxed)

                val found = HashSet<Int>()
                for (i in 0 until primitive.size) found.add(primitive[i])
                assertEquals("duplicates in result", primitive.size, found.size)
                assertEquals(expected, found)
                assertEquals(expected, boxed.toSet())
            }
        }
    }
}

class GameStateTest {

    @Test
    fun `clock is formatted as mm ss`() {
        assertEquals("00:00", formatClock(0))
        assertEquals("00:09", formatClock(9))
        assertEquals("01:05", formatClock(65))
        assertEquals("10:00", formatClock(600))
        assertEquals("125:03", formatClock(125 * 60 + 3))
    }

    @Test
    fun `formatted time follows elapsed seconds`() {
        assertEquals("02:03", GameState(elapsedTime = 123.9f).formattedTime)
        assertEquals(123, GameState(elapsedTime = 123.9f).elapsedSeconds)
    }

    @Test
    fun `a new hit makes the state different`() {
        assertNotEquals(GameState(hitCount = 1), GameState(hitCount = 2))
        assertEquals(GameState(hitCount = 3), GameState(hitCount = 3))
    }
}
