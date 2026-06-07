package com.kotlinsurvivors.engine.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.ecs.components.RenderShape
import kotlin.math.*

/**
 * GameRenderer
 *
 * Renders a RenderSnapshot — an immutable list of value objects built by
 * the engine thread. This class only runs on the UI/Compose thread and
 * never touches any live World data, eliminating the data race that caused
 * ConcurrentModificationException crashes.
 */
class GameRenderer(
    private val textMeasurer: TextMeasurer
) {
    private var cameraX = 0f
    private var cameraY = 0f

    // Pre-allocated to avoid new Path() every frame per triangle enemy
    private val trianglePath = Path()

    fun render(
        drawScope     : DrawScope,
        snapshot      : RenderSnapshot,
        viewportWidth : Float,
        viewportHeight: Float,
        dt            : Float
    ) {
        with(drawScope) {
            // Smooth camera follow toward player position
            val targetCX = snapshot.cameraTargetX - viewportWidth  * 0.5f
            val targetCY = snapshot.cameraTargetY - viewportHeight * 0.5f
            cameraX = lerp(cameraX, targetCX, (dt * 8f).coerceAtMost(1f))
            cameraY = lerp(cameraY, targetCY, (dt * 8f).coerceAtMost(1f))

            drawBackground(viewportWidth, viewportHeight)

            // Sort entities into render layers (back to front)
            val entities = snapshot.entities
            for (e in entities) if (e.kind == RenderEntityKind.PICKUP)       renderPickup(e)
            for (e in entities) if (e.kind == RenderEntityKind.ENEMY ||
                                    e.kind == RenderEntityKind.BOSS)         renderEnemy(e)
            for (e in entities) if (e.kind == RenderEntityKind.ORBITAL)      renderOrbital(e)
            for (e in entities) if (e.kind == RenderEntityKind.PLAYER)       renderPlayer(e)
            for (e in entities) if (e.kind == RenderEntityKind.PROJECTILE)   renderProjectile(e)
            for (e in entities) if (e.kind == RenderEntityKind.PARTICLE)     renderParticle(e)
            for (e in entities) if (e.kind == RenderEntityKind.DAMAGE_NUMBER)renderDamageNumber(e)
        }
    }

    // ── Background ──────────────────────────────────────────────────────────

    private fun DrawScope.drawBackground(vw: Float, vh: Float) {
        drawRect(color = Color(0xFF0A0A14), size = Size(vw, vh))
        val gridSize  = 80f
        val gridColor = Color(0xFF161622)
        var x = -(cameraX % gridSize)
        while (x < vw) { drawLine(gridColor, Offset(x, 0f), Offset(x, vh), strokeWidth = 1f); x += gridSize }
        var y = -(cameraY % gridSize)
        while (y < vh) { drawLine(gridColor, Offset(0f, y), Offset(vw, y), strokeWidth = 1f); y += gridSize }
        // Vignette
        val vig = Color(0x55000000); val vs = 120f
        drawRect(vig, topLeft = Offset(0f,    0f),    size = Size(vs, vh))
        drawRect(vig, topLeft = Offset(vw-vs, 0f),    size = Size(vs, vh))
        drawRect(vig, topLeft = Offset(0f,    0f),    size = Size(vw, vs))
        drawRect(vig, topLeft = Offset(0f,    vh-vs), size = Size(vw, vs))
    }

    // ── Pickup ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPickup(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY
        if (!inViewport(sx, sy, e.width)) return
        val pulse = (sin(System.currentTimeMillis() * 0.004f) * 0.15f + 0.85f)
        val sz    = e.width * pulse
        if (e.glowRadius > 0f)
            drawCircle(Color(e.glowColor), sz * 0.5f + e.glowRadius * pulse, Offset(sx, sy))
        drawCircle(Color(e.color), sz * 0.5f, Offset(sx, sy))
        drawCircle(Color(0x33FFFFFF), sz * 0.25f, Offset(sx - sz * 0.12f, sy - sz * 0.12f))
    }

    // ── Enemy ───────────────────────────────────────────────────────────────

    private fun DrawScope.renderEnemy(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY
        if (!inViewport(sx, sy, e.width)) return

        // Glow pass
        if (e.glowRadius > 0f)
            drawCircle(Color(e.glowColor), e.width * 0.5f + e.glowRadius, Offset(sx, sy))

        val drawColor = if (e.isFlashing) Color.White else Color(e.color)

        when (e.shape) {
            RenderShape.CIRCLE -> {
                drawCircle(drawColor, e.width * 0.5f, Offset(sx, sy))
                if (e.showHealthBar) drawHealthBar(sx, sy - e.width * 0.5f - 10f, e.width, e.hpPercent)
            }
            RenderShape.RECT -> {
                drawRect(drawColor,
                    topLeft = Offset(sx - e.width * 0.5f, sy - e.height * 0.5f),
                    size    = Size(e.width, e.height))
                if (e.showHealthBar) drawHealthBar(sx, sy - e.height * 0.5f - 10f, e.width, e.hpPercent)
            }
            RenderShape.TRIANGLE -> {
                drawTriangle(sx, sy, e.width * 0.5f, drawColor, e.rotation)
                if (e.showHealthBar) drawHealthBar(sx, sy - e.width * 0.5f - 14f, e.width, e.hpPercent)
            }
        }

        // Boss ring
        if (e.kind == RenderEntityKind.BOSS) {
            drawCircle(Color(0x88FF6B6B), e.width * 0.5f + 6f, Offset(sx, sy),
                style = Stroke(width = 3f))
        }
    }

    private fun DrawScope.drawHealthBar(cx: Float, topY: Float, width: Float, pct: Float) {
        if (pct >= 1f) return
        val barW = width * 1.2f; val barH = 5f; val left = cx - barW * 0.5f
        drawRect(Color(0xFF333333), topLeft = Offset(left, topY), size = Size(barW, barH))
        val fillColor = when { pct > 0.6f -> Color(0xFF4CAF50); pct > 0.3f -> Color(0xFFFFC107); else -> Color(0xFFF44336) }
        drawRect(fillColor, topLeft = Offset(left, topY), size = Size(barW * pct, barH))
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

    private fun DrawScope.renderOrbital(e: RenderEntity) {
        val ox = e.x + cos(e.orbitAngle) * e.orbitRadius - cameraX
        val oy = e.y + sin(e.orbitAngle) * e.orbitRadius - cameraY
        drawCircle(Color(0x44F9A825), e.orbitSize * 1.8f, Offset(ox, oy))
        drawCircle(Color(0xFFF9A825), e.orbitSize,         Offset(ox, oy))
        drawCircle(Color(0x55FFFFFF), e.orbitSize * 0.4f,  Offset(ox - e.orbitSize * 0.2f, oy - e.orbitSize * 0.2f))
    }

    // ── Player ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPlayer(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY

        // Invincibility flicker
        if (e.invincibleTimer > 0f && ((e.invincibleTimer * 20f).toInt() % 2 == 0)) return

        // Outer glow
        if (e.glowRadius > 0f)
            drawCircle(Color(e.glowColor), e.width * 0.5f + e.glowRadius, Offset(sx, sy))

        // Aura ring (Garlic)
        if (e.hasAura) {
            val time  = System.currentTimeMillis() * 0.003f
            val pulse = sin(time) * 0.08f + 0.92f
            drawCircle(Color(0x2289BA5B), e.auraRadius * pulse, Offset(sx, sy), style = Stroke(width = 2f))
        }

        val bodyColor = if (e.isFlashing) Color.White else Color(e.color)
        drawCircle(bodyColor, e.width * 0.5f, Offset(sx, sy))
        drawCircle(Color(e.secondaryColor), e.width * 0.28f, Offset(sx, sy))
        drawCircle(Color(0x55FFFFFF), e.width * 0.18f, Offset(sx - e.width * 0.14f, sy - e.width * 0.14f))
    }

    // ── Projectile ──────────────────────────────────────────────────────────

    private fun DrawScope.renderProjectile(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY
        if (!inViewport(sx, sy, e.width)) return
        if (e.glowRadius > 0f)
            drawCircle(Color(e.glowColor), e.width * 0.5f + e.glowRadius, Offset(sx, sy))
        val alpha = if (e.isCritical) 1f else 0.88f + sin(System.currentTimeMillis() * 0.01f).toFloat() * 0.12f
        drawCircle(Color(e.color).copy(alpha = alpha), e.width * 0.5f, Offset(sx, sy))
        if (e.isCritical)
            drawCircle(Color(0xFFFFD740), e.width * 0.28f, Offset(sx, sy))
    }

    // ── Particle ────────────────────────────────────────────────────────────

    private fun DrawScope.renderParticle(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY
        if (!inViewport(sx, sy, e.width * 2f)) return
        val lifeRatio = if (e.maxLifetime > 0f) (e.lifetime / e.maxLifetime).coerceIn(0f, 1f) else 0f
        drawCircle(Color(e.color).copy(alpha = lifeRatio), e.width * lifeRatio, Offset(sx, sy))
    }

    // ── Damage number ───────────────────────────────────────────────────────

    private fun DrawScope.renderDamageNumber(e: RenderEntity) {
        val sx = e.x - cameraX; val sy = e.y - cameraY
        val lifeRatio = if (e.maxLifetime > 0f) (e.lifetime / e.maxLifetime).coerceIn(0f, 1f) else 0f
        val color     = if (e.isCritical) Color(0xFFFFD740) else Color.White
        val fontSize  = if (e.isCritical) 18.sp else 13.sp
        drawText(
            textMeasurer = textMeasurer,
            text         = if (e.isCritical) "★${e.damageValue}" else "${e.damageValue}",
            topLeft      = Offset(sx - 12f, sy),
            style        = TextStyle(color = color.copy(alpha = lifeRatio), fontSize = fontSize)
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun inViewport(sx: Float, sy: Float, size: Float) =
        sx > -size && sx < 2000f + size && sy > -size && sy < 1200f + size

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
