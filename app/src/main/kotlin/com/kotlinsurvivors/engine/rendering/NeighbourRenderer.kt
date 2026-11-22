package com.kotlinsurvivors.engine.rendering

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.map.Level1Npc
import kotlin.math.min
import kotlin.math.sin

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
    var lines: Array<String>
        get() = talk.lines
        set(value) { talk.lines = value }

    /** Who is close and what he is saying; the drawing below only reads it. */
    private val talk = NeighbourTalk()

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
    private val src  = Rect()
    private val tail = Path()

    /** Decides whether he is talking, from where the player's feet are. Call once per frame. */
    fun update(playerX: Float, playerFeetY: Float, nowMs: Long) = talk.update(playerX, playerFeetY, nowMs)

    /** True when the player's feet are lower on the screen than his, so the player is drawn over him. */
    fun isBehindPlayer(playerFeetY: Float) = playerFeetY >= Level1Npc.FEET_Y

    /**
     * Frame of the sheet for this moment. On his phone he taps away (frames 0..3, a thumb after
     * the other) and now and then looks up (4). When somebody is close he lowers the phone and
     * raises a hand (5), and moves his mouth (5/6) while the bubble is being typed.
     */
    private fun frameAt(nowMs: Long): Int {
        if (talk.near) {
            val typing = talk.lineIndex in lines.indices &&
                nowMs - talk.lineStart < lines[talk.lineIndex].length * 1000L / TYPE_CHARS_PER_SECOND
            return if (typing && (nowMs / TALK_FLAP_MS) % 2L == 1L) 6 else 5
        }
        val t = nowMs % PHONE_CYCLE_MS
        if (t > PHONE_CYCLE_MS - GLANCE_MS) return 4
        return ((t / TAP_MS) % 4L).toInt()
    }

    fun DrawScope.drawSprite(art: LevelArt, cameraX: Float, cameraY: Float, nowMs: Long) {
        val sheet = art.npc ?: return
        val fw = Level1Npc.FRAME_W
        val fh = Level1Npc.FRAME_H
        val frame = frameAt(nowMs).coerceIn(0, Level1Npc.FRAMES - 1)
        if ((frame + 1) * fw > sheet.width || fh > sheet.height) return
        val k  = Level1Npc.SCALE
        val w  = fw * k
        val h  = fh * k
        val sx = Level1Npc.X - cameraX
        val sy = Level1Npc.FEET_Y - cameraY
        if (sx < -w || sx > size.width + w || sy < -h || sy > size.height + h) return

        // Contact shadow: a dark oval under the boots and a wider, fainter one, so he sits on the ground.
        drawOval(Color(0x30000000), topLeft = Offset(sx - 34f, sy - 9f), size = Size(68f, 18f))
        drawOval(Color(0x66000000), topLeft = Offset(sx - 24f, sy - 6f), size = Size(48f, 11f))

        val breathe = if (talk.near) 0f else sin(nowMs * 0.0025f) * 0.6f
        val left = sx - w / 2f
        val top  = sy - Level1Npc.FEET_ROW * k + breathe
        src.set(frame * fw, 0, (frame + 1) * fw, fh)
        box.set(left, top, left + w, top + h)
        drawContext.canvas.nativeCanvas.drawBitmap(sheet, src, box, spritePaint)

        // The screen of the phone lights his belly and the ground in front of him (not when he has lowered it).
        if (!talk.near || frame == 5 || frame == 6) {
            val flicker = 0.85f + 0.15f * sin(nowMs * 0.02f)
            val px = if (talk.near) left + 5.5f * k else left + 20.5f * k
            val py = if (talk.near) top + 52f * k else top + 41f * k
            val r  = (if (talk.near) 26f else 40f) * flicker
            drawCircle(
                brush  = Brush.radialGradient(
                    listOf(Color(0x5534DCF2), Color(0x2234DCF2), Color(0x0034DCF2)),
                    center = Offset(px, py), radius = r,
                ),
                radius = r, center = Offset(px, py),
            )
        }
    }

    fun DrawScope.drawBubble(cameraX: Float, cameraY: Float, nowMs: Long) {
        if (talk.lineIndex < 0 || talk.lineIndex >= lines.size) return
        val elapsed = nowMs - talk.lineStart
        val alpha = when {
            talk.near -> {
                val total = talk.lineDuration(talk.lineIndex)
                if (elapsed >= total) 0f
                else min(1f, min(elapsed / FADE_IN_MS.toFloat(), (total - elapsed) / FADE_OUT_MS.toFloat()))
            }
            talk.leftAt > 0L -> 1f - (nowMs - talk.leftAt) / FADE_OUT_MS.toFloat()
            else -> 0f
        }
        if (alpha <= 0f) return

        val textSize = 13.sp.toPx()
        val pad      = 9.dp.toPx()
        val maxWidth = 220.dp.toPx()
        textPaint.textSize = textSize
        layoutLine(talk.lineIndex, maxWidth, textSize)

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
        var shown = if (talk.near) ((elapsed * TYPE_CHARS_PER_SECOND) / 1000L).toInt() + 1 else Int.MAX_VALUE
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

    private companion object {
        const val TAP_MS        = 260L      // each tapping frame
        const val PHONE_CYCLE_MS = 5200L    // after this long on the phone he looks up for a moment
        const val GLANCE_MS     = 700L
        const val TALK_FLAP_MS  = 140L
        const val FADE_IN_MS   = 150L
        const val FADE_OUT_MS  = 250L
        const val TYPE_CHARS_PER_SECOND = 45L

        const val BUBBLE_FILL = 0xFFF4F1E8.toInt()
        const val BUBBLE_EDGE = 0xFF1A1020.toInt()
        const val TEXT_COLOR  = 0xFF1A1020.toInt()
    }
}
