package com.kotlinsurvivors.engine.rendering

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.ecs.components.RenderShape
import kotlin.math.*

/**
 * GameRenderer — reads from RenderBuffer (zero-allocation path).
 *
 * The RenderBuffer contains parallel primitive arrays (FloatArray, IntArray).
 * Reading primitives from arrays causes zero GC pressure — no object
 * creation, no boxing. This renderer never allocates anything in the
 * hot path except the pre-allocated trianglePath which is reset in-place.
 */
class GameRenderer(
    // Kept so GameScreen does not change; damage numbers no longer use it
    // (they are drawn with a pre-allocated native Paint, see renderDamageNumber).
    @Suppress("unused") private val textMeasurer: TextMeasurer
) {
    // Smoothed camera position, carried from frame to frame.
    private var smoothX = 0f
    private var smoothY = 0f

    // Camera actually used for drawing this frame: smoothed position plus shake.
    private var cameraX = 0f
    private var cameraY = 0f

    // Size of the area being drawn, refreshed every frame, for culling.
    private var screenW = 0f
    private var screenH = 0f

    // RenderShape.values() allocates a new array on every call; keep one copy.
    private val shapes = RenderShape.values()

    /** Display options, set by the screen from the player's settings. */
    var showDamageNumbers = true
    var showFps           = false

    private var shakeTimer = 0f

    // Frames-per-second counter for the optional overlay.
    private var fpsWindowStart = 0L
    private var fpsFrames      = 0
    private var fpsText       = "FPS --"
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        color     = 0xFFB0FFB0.toInt()
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
    }

    /** Shakes the camera briefly. Called when the player is hit. */
    fun triggerShake() {
        shakeTimer = SHAKE_DURATION
    }

    // Pre-allocated — reset() instead of new Path() each frame
    private val trianglePath = Path()

    // Pre-allocated paint for damage numbers: no text layout, no allocation per frame.
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
    }

    // Cached strings for the common damage values (avoids a String per number per frame).
    private val plainTexts = arrayOfNulls<String>(NUMBER_CACHE_SIZE)
    private val critTexts  = arrayOfNulls<String>(NUMBER_CACHE_SIZE)

    fun render(
        drawScope     : DrawScope,
        snapshot      : RenderSnapshot,
        viewportWidth : Float,
        viewportHeight: Float,
        dt            : Float
    ) {
        val buf = snapshot.buffer
        with(drawScope) {
            screenW = size.width
            screenH = size.height

            // Smooth camera follow
            val targetCX = buf.cameraTargetX - viewportWidth  * 0.5f
            val targetCY = buf.cameraTargetY - viewportHeight * 0.5f
            smoothX = lerp(smoothX, targetCX, (dt * 8f).coerceAtMost(1f))
            smoothY = lerp(smoothY, targetCY, (dt * 8f).coerceAtMost(1f))

            var shakeX = 0f
            var shakeY = 0f
            if (shakeTimer > 0f) {
                val strength = SHAKE_STRENGTH * (shakeTimer / SHAKE_DURATION)
                val t = System.currentTimeMillis()
                shakeX = sin(t * 0.09f) * strength
                shakeY = cos(t * 0.11f) * strength
                shakeTimer = (shakeTimer - dt).coerceAtLeast(0f)
            }
            cameraX = smoothX + shakeX
            cameraY = smoothY + shakeY

            drawBackground(viewportWidth, viewportHeight)

            // Render in layers back→front using a single pass over the buffer
            // Pass 1: Pickup glow + body
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PICKUP) renderPickup(buf, i)
            }
            // Pass 2: Enemy glow
            for (i in 0 until buf.count) {
                val k = buf.kind[i]
                if ((k == RenderKind.ENEMY || k == RenderKind.BOSS) && buf.glowRadius[i] > 0f) {
                    val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
                    if (!inViewport(sx, sy, buf.width[i] + buf.glowRadius[i] * 2f)) continue
                    drawCircle(Color(buf.getGlowColor(i)), buf.width[i] * 0.5f + buf.glowRadius[i], Offset(sx, sy))
                }
            }
            // Pass 3: Enemies
            for (i in 0 until buf.count) {
                val k = buf.kind[i]
                if (k == RenderKind.ENEMY || k == RenderKind.BOSS) renderEnemy(buf, i)
            }
            // Pass 4: Orbitals
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.ORBITAL) renderOrbital(buf, i)
            }
            // Pass 5: Player
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PLAYER) renderPlayer(buf, i)
            }
            // Pass 6: Projectiles
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PROJECTILE) renderProjectile(buf, i)
            }
            // Pass 7: Particles
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PARTICLE) renderParticle(buf, i)
            }
            // Pass 8: Damage numbers
            if (showDamageNumbers) {
                for (i in 0 until buf.count) {
                    if (buf.kind[i] == RenderKind.DAMAGE_NUMBER) renderDamageNumber(buf, i)
                }
            }

            if (showFps) drawFps()
        }
    }

    private fun DrawScope.drawFps() {
        val now = System.nanoTime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= FPS_WINDOW_NANOS) {
            fpsText        = "FPS " + (fpsFrames * 1_000_000_000L / elapsed)
            fpsFrames      = 0
            fpsWindowStart = now
        }
        fpsPaint.textSize = 14.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText(fpsText, size.width - 16f, size.height - 16f, fpsPaint)
    }

    // ── Background ──────────────────────────────────────────────────────────

    private fun DrawScope.drawBackground(vw: Float, vh: Float) {
        drawRect(color = Color(0xFF0A0A14), size = Size(vw, vh))
        val gridColor = Color(0xFF161622)
        var x = -(cameraX % 80f)
        while (x < vw) { drawLine(gridColor, Offset(x, 0f), Offset(x, vh), strokeWidth = 1f); x += 80f }
        var y = -(cameraY % 80f)
        while (y < vh) { drawLine(gridColor, Offset(0f, y), Offset(vw, y), strokeWidth = 1f); y += 80f }
        val vig = Color(0x55000000)
        drawRect(vig, topLeft = Offset(0f,      0f),    size = Size(120f, vh))
        drawRect(vig, topLeft = Offset(vw-120f, 0f),    size = Size(120f, vh))
        drawRect(vig, topLeft = Offset(0f,      0f),    size = Size(vw, 120f))
        drawRect(vig, topLeft = Offset(0f,      vh-120f), size = Size(vw, 120f))
    }

    // ── Pickup ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPickup(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i])) return
        val pulse = (sin(System.currentTimeMillis() * 0.004f) * 0.15f + 0.85f)
        val sz    = buf.width[i] * pulse
        if (buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), sz * 0.5f + buf.glowRadius[i] * pulse, Offset(sx, sy))
        drawCircle(Color(buf.getColor(i)), sz * 0.5f, Offset(sx, sy))
        drawCircle(Color(0x33FFFFFF), sz * 0.25f, Offset(sx - sz * 0.12f, sy - sz * 0.12f))
    }

    // ── Enemy ───────────────────────────────────────────────────────────────

    private fun DrawScope.renderEnemy(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i])) return
        val isFlashing = buf.hasFlag(i, RenderBuffer.FLAG_FLASHING)
        val drawColor  = if (isFlashing) Color.White else Color(buf.getColor(i))
        val shape      = shapes[buf.shapeOrdinal[i]]
        when (shape) {
            RenderShape.CIRCLE -> {
                drawCircle(drawColor, buf.width[i] * 0.5f, Offset(sx, sy))
                if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
                    drawHealthBar(sx, sy - buf.width[i] * 0.5f - 10f, buf.width[i], buf.hpPercent[i])
            }
            RenderShape.RECT -> {
                drawRect(drawColor,
                    topLeft = Offset(sx - buf.width[i] * 0.5f, sy - buf.height[i] * 0.5f),
                    size    = Size(buf.width[i], buf.height[i]))
                if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
                    drawHealthBar(sx, sy - buf.height[i] * 0.5f - 10f, buf.width[i], buf.hpPercent[i])
            }
            RenderShape.TRIANGLE -> {
                drawTriangle(sx, sy, buf.width[i] * 0.5f, drawColor, buf.rotation[i])
                if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
                    drawHealthBar(sx, sy - buf.width[i] * 0.5f - 14f, buf.width[i], buf.hpPercent[i])
            }
        }
        if (buf.hasFlag(i, RenderBuffer.FLAG_IS_BOSS)) {
            drawCircle(Color(0x88FF6B6B), buf.width[i] * 0.5f + 6f, Offset(sx, sy),
                style = Stroke(width = 3f))
        }
    }

    private fun DrawScope.drawHealthBar(cx: Float, topY: Float, width: Float, pct: Float) {
        if (pct >= 1f) return
        val barW = width * 1.2f; val left = cx - barW * 0.5f
        drawRect(Color(0xFF333333), topLeft = Offset(left, topY), size = Size(barW, 5f))
        val fill = when { pct > 0.6f -> Color(0xFF4CAF50); pct > 0.3f -> Color(0xFFFFC107); else -> Color(0xFFF44336) }
        drawRect(fill, topLeft = Offset(left, topY), size = Size(barW * pct, 5f))
    }

    private fun DrawScope.drawTriangle(cx: Float, cy: Float, r: Float, color: Color, rotation: Float) {
        trianglePath.reset()
        for (i in 0..2) {
            val angle = rotation + i * 2f * PI.toFloat() / 3f - PI.toFloat() / 2f
            val px = cx + cos(angle) * r; val py = cy + sin(angle) * r
            if (i == 0) trianglePath.moveTo(px, py) else trianglePath.lineTo(px, py)
        }
        trianglePath.close()
        drawPath(trianglePath, color)
    }

    // ── Orbital ─────────────────────────────────────────────────────────────

    private fun DrawScope.renderOrbital(buf: RenderBuffer, i: Int) {
        val ox = buf.x[i] + cos(buf.orbitAngle[i]) * buf.orbitRadius[i] - cameraX
        val oy = buf.y[i] + sin(buf.orbitAngle[i]) * buf.orbitRadius[i] - cameraY
        val sz = buf.orbitSize[i]
        drawCircle(Color(0x44F9A825), sz * 1.8f, Offset(ox, oy))
        drawCircle(Color(0xFFF9A825), sz,          Offset(ox, oy))
        drawCircle(Color(0x55FFFFFF), sz * 0.4f,   Offset(ox - sz * 0.2f, oy - sz * 0.2f))
    }

    // ── Player ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPlayer(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        // Invincibility flicker
        if (buf.invincTimer[i] > 0f && ((buf.invincTimer[i] * 20f).toInt() % 2 == 0)) return
        if (buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), buf.width[i] * 0.5f + buf.glowRadius[i], Offset(sx, sy))
        if (buf.hasFlag(i, RenderBuffer.FLAG_HAS_AURA)) {
            val pulse = sin(System.currentTimeMillis() * 0.003f) * 0.08f + 0.92f
            drawCircle(Color(0x2289BA5B), buf.auraRadius[i] * pulse, Offset(sx, sy), style = Stroke(2f))
        }
        val bodyColor = if (buf.hasFlag(i, RenderBuffer.FLAG_FLASHING)) Color.White else Color(buf.getColor(i))
        drawCircle(bodyColor, buf.width[i] * 0.5f, Offset(sx, sy))
        drawCircle(Color(buf.getSecColor(i)), buf.width[i] * 0.28f, Offset(sx, sy))
        drawCircle(Color(0x55FFFFFF), buf.width[i] * 0.18f,
            Offset(sx - buf.width[i] * 0.14f, sy - buf.width[i] * 0.14f))
    }

    // ── Projectile ──────────────────────────────────────────────────────────

    private fun DrawScope.renderProjectile(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i])) return
        if (buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), buf.width[i] * 0.5f + buf.glowRadius[i], Offset(sx, sy))
        val isCrit = buf.hasFlag(i, RenderBuffer.FLAG_IS_CRITICAL)
        val alpha  = if (isCrit) 1f else 0.88f + sin(System.currentTimeMillis() * 0.01f).toFloat() * 0.12f
        drawCircle(Color(buf.getColor(i)).copy(alpha = alpha), buf.width[i] * 0.5f, Offset(sx, sy))
        if (isCrit) drawCircle(Color(0xFFFFD740), buf.width[i] * 0.28f, Offset(sx, sy))
    }

    // ── Particle ────────────────────────────────────────────────────────────

    private fun DrawScope.renderParticle(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i] * 2f)) return
        val lifeRatio = if (buf.maxLifetime[i] > 0f)
            (buf.lifetime[i] / buf.maxLifetime[i]).coerceIn(0f, 1f) else 0f
        drawCircle(Color(buf.getColor(i)).copy(alpha = lifeRatio), buf.width[i] * lifeRatio, Offset(sx, sy))
    }

    // ── Damage number ───────────────────────────────────────────────────────

    private fun DrawScope.renderDamageNumber(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY

        // Off-screen numbers are skipped. This is what prevented the crash:
        // Compose's drawText() throws IllegalArgumentException when the text
        // starts below/right of the canvas (negative remaining height/width).
        val margin = 48f
        if (sx < -margin || sx > size.width + margin || sy < -margin || sy > size.height + margin) return

        val lifeRatio = if (buf.maxLifetime[i] > 0f)
            (buf.lifetime[i] / buf.maxLifetime[i]).coerceIn(0f, 1f) else 0f
        val isCrit = buf.hasFlag(i, RenderBuffer.FLAG_IS_CRITICAL)
        val color  = if (isCrit) Color(0xFFFFD740) else Color.White

        val textSize = (if (isCrit) 18.sp else 13.sp).toPx()
        numberPaint.textSize       = textSize
        numberPaint.isFakeBoldText = isCrit
        numberPaint.color          = color.copy(alpha = lifeRatio).toArgb()

        drawContext.canvas.nativeCanvas.drawText(
            numberText(buf.damageValue[i], isCrit), sx, sy + textSize, numberPaint
        )
    }

    private fun numberText(value: Int, crit: Boolean): String {
        if (value < 0 || value >= NUMBER_CACHE_SIZE) return if (crit) "★$value" else value.toString()
        val cache = if (crit) critTexts else plainTexts
        var text = cache[value]
        if (text == null) {
            text = if (crit) "★$value" else value.toString()
            cache[value] = text
        }
        return text
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun inViewport(sx: Float, sy: Float, size: Float) =
        sx > -size && sx < screenW + size && sy > -size && sy < screenH + size

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val NUMBER_CACHE_SIZE = 1000
        const val SHAKE_DURATION    = 0.25f
        const val SHAKE_STRENGTH    = 10f
        const val FPS_WINDOW_NANOS  = 500_000_000L
    }
}
