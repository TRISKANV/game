package com.kotlinsurvivors.engine.rendering

import com.kotlinsurvivors.engine.map.Level1Npc
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Who is close to the neighbour of Level 1 and which line he is saying. It is plain logic with
 * no drawing, so it can be tested on its own; [NeighbourRenderer] only reads it to draw.
 *
 * While the player stays near he says his [lines] one after another, in random order and without
 * repeating any until all have been said.
 */
class NeighbourTalk(private val random: Random = Random.Default) {

    /** What he can say, already translated. Index i is string npc_leek_(i+1). */
    var lines: Array<String> = emptyArray()

    var near = false
        private set
    /** When the current line started to be typed. */
    var lineStart = 0L
        private set
    /** Index in [lines] of the line being said, -1 before the first one. */
    var lineIndex = -1
        private set
    /** Distance from the player's feet to his boots at the last update, for the debug line; -1 before any. */
    var distance = -1f
        private set

    private var order     = IntArray(0)
    private var orderPos  = 0
    private var lastShown = -1

    /** Call once per frame with the player's feet. */
    fun update(playerX: Float, playerFeetY: Float, nowMs: Long) {
        if (lines.isEmpty()) return
        val dist = hypot(playerX - Level1Npc.X, playerFeetY - Level1Npc.FEET_Y)
        distance = dist
        // Two radii so that standing on the edge does not make him chatter: he is "near" from
        // TALK_RADIUS in and "away" only beyond LEAVE_RADIUS.
        val nowNear = dist <= (if (near) Level1Npc.LEAVE_RADIUS else Level1Npc.TALK_RADIUS)
        if (nowNear != near) {
            near = nowNear
            startNextLine(nowMs)
        }
    }

    /** True while the current line is on screen. */
    fun isSpeaking(nowMs: Long): Boolean =
        lineIndex in lines.indices && nowMs - lineStart < lineDuration(lineIndex)

    /** How long line [index] stays on screen: longer for longer lines, within limits. */
    fun lineDuration(index: Int): Long {
        if (index < 0 || index >= lines.size) return MIN_LINE_MS
        return (BASE_LINE_MS + lines[index].length * PER_CHAR_MS).coerceIn(MIN_LINE_MS, MAX_LINE_MS)
    }

    /** Next line from a shuffled deck, so nothing repeats until everything has been said. */
    private fun startNextLine(nowMs: Long) {
        if (order.size != lines.size || orderPos >= order.size) {
            order = IntArray(lines.size) { it }
            for (i in order.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            if (order.size > 1 && order[0] == lastShown) {       // do not open the new deck with the line just said
                val t = order[0]; order[0] = order[order.size - 1]; order[order.size - 1] = t
            }
            orderPos = 0
        }
        lineIndex = order[orderPos++]
        lastShown = lineIndex
        lineStart = nowMs
    }

    companion object {
        const val BASE_LINE_MS = 1500L
        const val PER_CHAR_MS  = 45L
        const val MIN_LINE_MS  = 2600L
        const val MAX_LINE_MS  = 5200L
    }
}
