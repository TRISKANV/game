package com.kotlinsurvivors.engine.rendering

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.map.Level1Npc
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the neighbour of Level 1 and the speech bubble that appears over his head when the
 * player comes close. It is only a picture and a little state: it reads the player's position
 * from the render buffer and does not touch the game world (his solid body is part of the
 * level's collision grid, see [Level1Npc.stampSolid]).
 *
 * While the player stays near, he says his lines one after another, in random order and
 * without repeating any until all have been said. The text of each line comes from [lines],
 * which the screen fills in with the current language.
 */
class NeighbourRenderer {

    /** What he can say, already translated. Index i is string npc_leek_(i+1). */
    var lines: Array<String> = emptyArray()

    private var near       = false
    private var lineStart  = 0L          // when the current line started to be typed
    private var lineIndex  = -1
    private var leftAt     = 0L          // when the player walked away, for the fade out
    private var order      = IntArray(0)
    private var orderPos   = 0
    private var lastShown  = -1

    // Wrapped text of the current line, kept so it is only laid out once per line and width.
    private var wrapped: List<String> = emptyList()
    private var wrappedFor   = -1
    private var wrappedWidth = 0f
    private var wrappedSize  = 0f

    private val spritePaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val fillPaint   = Paint().apply { isAntiAlias = false; color = BUBBLE_FILL }
    private val edgePaint   = Paint().apply { isAntiAlias = false; color = BUBBLE_EDGE }
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = TEXT_COLOR
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val box  = RectF()
    private val tail = Path()

    /** Decides whether he is talking, from where the player's feet are. Call once per frame. */
    fun update(playerX: Float, playerFeetY: Float, nowMs: Long) {
        if (lines.isEmpty()) return
        val dist = hypot(playerX - Level1Npc.X, playerFeetY - Level1Npc.FEET_Y)
        val nowNear = dist <= (if (near) Level1Npc.LEAVE_RADIUS else Level1Npc.TALK_RADIUS)
        if (nowNear && !near) startNextLine(nowMs)
        if (!nowNear && near) leftAt = nowMs
        near = nowNear
        if (near && nowMs - lineStart > lineDuration(lineIndex) + GAP_MS) startNextLine(nowMs)
    }

    /** True when the player's feet are lower on the screen than his, so the player is drawn over him. */
    fun isBehindPlayer(playerFeetY: Float) = playerFeetY >= Level1Npc.FEET_Y

    fun DrawScope.drawSprite(art: LevelArt, cameraX: Float, cameraY: Float, nowMs: Long) {
        val picture = art.npc ?: return
        val h = Level1Npc.SPRITE_HEIGHT
        val w = h * picture.width / picture.height
        val sx = Level1Npc.X - cameraX
        val sy = Level1Npc.FEET_Y - cameraY
        if (sx < -w || sx > size.width + w || sy < -h || sy > size.height + h) return
        val bob = sin(nowMs * 0.003f) * 1.5f                       // he breathes
        drawOval(Color(0x55000000), topLeft = Offset(sx - w * 0.4f, sy - 5f), size = Size(w * 0.8f, 11f))
        box.set(sx - w * 0.5f, sy - h + bob, sx + w * 0.5f, sy + bob)
        drawContext.canvas.nativeCanvas.drawBitmap(picture, null, box, spritePaint)
    }

    fun DrawScope.drawBubble(cameraX: Float, cameraY: Float, nowMs: Long) {
        if (lineIndex < 0 || lineIndex >= lines.size) return
        val elapsed = nowMs - lineStart
        val alpha = when {
            near -> {
                val total = lineDuration(lineIndex)
                if (elapsed >= total) 0f
                else min(1f, min(elapsed / FADE_IN_MS.toFloat(), (total - elapsed) / FADE_OUT_MS.toFloat()))
            }
            leftAt > 0L -> 1f - (nowMs - leftAt) / FADE_OUT_MS.toFloat()
            else -> 0f
        }
        if (alpha <= 0f) return

        val textSize = 13.sp.toPx()
        val pad      = 9.dp.toPx()
        val maxWidth = 220.dp.toPx()
        textPaint.textSize = textSize
        layoutLine(lineIndex, maxWidth, textSize)

        val lineH = textSize * 1.25f
        var widest = 0f
        for (l in wrapped) widest = maxOf(widest, textPaint.measureText(l))
        val bw = widest + pad * 2f
        val bh = wrapped.size * lineH + pad * 2f - (lineH - textSize)

        val headX = Level1Npc.X - cameraX
        val headY = Level1Npc.FEET_Y - Level1Npc.SPRITE_HEIGHT - cameraY - 4f
        val tailH = 12.dp.toPx()
        val margin = 8.dp.toPx()
        val left   = (headX - bw / 2f).coerceIn(margin, (size.width - bw - margin).coerceAtLeast(margin))
        val bottom = headY - tailH
        box.set(left, bottom - bh, left + bw, bottom)
        val tailX = headX.coerceIn(box.left + pad, box.right - pad)

        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val canvas = drawContext.canvas.nativeCanvas
        val edge = 2.dp.toPx()

        // Outline first (slightly bigger), then the fill on top: gives a border that also runs round the tail.
        edgePaint.alpha = a
        box.inset(-edge, -edge)
        canvas.drawRoundRect(box, 4f, 4f, edgePaint)
        box.inset(edge, edge)
        buildTail(tailX, box.bottom, tailH, edge)
        canvas.drawPath(tail, edgePaint)
        fillPaint.alpha = a
        canvas.drawRoundRect(box, 3f, 3f, fillPaint)
        buildTail(tailX, box.bottom, tailH, 0f)
        canvas.drawPath(tail, fillPaint)

        // The text appears letter by letter, like somebody talking.
        var shown = if (near) ((elapsed * TYPE_CHARS_PER_SECOND) / 1000L).toInt() + 1 else Int.MAX_VALUE
        textPaint.alpha = a
        var y = box.top + pad + textSize
        for (l in wrapped) {
            if (shown <= 0) break
            val part = if (shown >= l.length) l else l.substring(0, shown)
            canvas.drawText(part, box.left + pad, y, textPaint)
            shown -= l.length
            y += lineH
        }
    }

    private fun buildTail(x: Float, top: Float, height: Float, grow: Float) {
        tail.reset()
        val half = height * 0.45f + grow
        tail.moveTo(x - half, top - 1f)
        tail.lineTo(x + half, top - 1f)
        tail.lineTo(x, top + height + grow)
        tail.close()
    }

    /** Breaks the line into rows no wider than [maxWidth]; done once per line, not per frame. */
    private fun layoutLine(index: Int, maxWidth: Float, textSize: Float) {
        if (index == wrappedFor && maxWidth == wrappedWidth && textSize == wrappedSize) return
        val rows = ArrayList<String>(3)
        val row  = StringBuilder()
        for (word in lines[index].split(' ')) {
            if (row.isNotEmpty() && textPaint.measureText(row.toString() + " " + word) > maxWidth) {
                rows.add(row.toString()); row.setLength(0)
            }
            if (row.isNotEmpty()) row.append(' ')
            row.append(word)
        }
        if (row.isNotEmpty()) rows.add(row.toString())
        wrapped = rows; wrappedFor = index; wrappedWidth = maxWidth; wrappedSize = textSize
    }

    private fun lineDuration(index: Int): Long {
        if (index < 0 || index >= lines.size) return MIN_LINE_MS
        return (BASE_LINE_MS + lines[index].length * PER_CHAR_MS).coerceIn(MIN_LINE_MS, MAX_LINE_MS)
    }

    /** Next line from a shuffled deck, so nothing repeats until everything has been said. */
    private fun startNextLine(nowMs: Long) {
        if (order.size != lines.size || orderPos >= order.size) {
            order = IntArray(lines.size) { it }
            for (i in order.size - 1 downTo 1) {
                val j = Random.nextInt(i + 1)
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
        leftAt    = 0L
    }

    private companion object {
        const val BASE_LINE_MS = 1500L
        const val PER_CHAR_MS  = 45L
        const val MIN_LINE_MS  = 2600L
        const val MAX_LINE_MS  = 5200L
        const val GAP_MS       = 600L
        const val FADE_IN_MS   = 150L
        const val FADE_OUT_MS  = 250L
        const val TYPE_CHARS_PER_SECOND = 45L

        const val BUBBLE_FILL = 0xFFF4F1E8.toInt()
        const val BUBBLE_EDGE = 0xFF1A1020.toInt()
        const val TEXT_COLOR  = 0xFF1A1020.toInt()
    }
}
