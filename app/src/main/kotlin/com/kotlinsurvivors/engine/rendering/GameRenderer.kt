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
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.*

/**
 * GameRenderer
 *
 * Performs all Canvas drawing for one frame.
 * Called from a Compose Canvas composable — entirely on the UI thread.
 *
 * Rendering layers (back to front):
 *  1. Background grid
 *  2. Pickup orbs
 *  3. Enemy glows
 *  4. Enemies + health bars
 *  5. Orbital weapons
 *  6. Player (with aura rings)
 *  7. Projectiles
 *  8. Particles
 *  9. Damage numbers
 *
 * Camera follows the player with a smooth lerp.
 */
class GameRenderer(
    private val textMeasurer: TextMeasurer
) {
    private var cameraX = 0f
    private var cameraY = 0f

    // Pre-allocated to avoid creating a new Path object every frame for every triangle enemy
    private val trianglePath = Path()

    fun render(
        drawScope     : DrawScope,
        world         : World,
        viewportWidth : Float,
        viewportHeight: Float,
        dt            : Float
    ) {
        with(drawScope) {
            val pid = world.getPlayerEntity()
            val pt  = world.transforms[pid]

            // Smooth camera follow
            if (pt != null) {
                val targetCX = pt.x - viewportWidth  * 0.5f
                val targetCY = pt.y - viewportHeight * 0.5f
                cameraX = lerp(cameraX, targetCX, (dt * 8f).coerceAtMost(1f))
                cameraY = lerp(cameraY, targetCY, (dt * 8f).coerceAtMost(1f))
            }

            drawBackground(viewportWidth, viewportHeight)
            renderPickups(world)
            renderEnemyGlows(world)
            renderEnemies(world)
            renderOrbitals(world, pid)
            renderPlayer(world, pid, dt)
            renderProjectiles(world)
            renderParticles(world)
            renderDamageNumbers(world)
        }
    }

    // ── Background ─────────────────────────────────────────────────────────

    private fun DrawScope.drawBackground(vw: Float, vh: Float) {
        drawRect(color = Color(0xFF0A0A14), size = Size(vw, vh))

        val gridSize  = 80f
        val gridColor = Color(0xFF161622)
        val startX    = -(cameraX % gridSize)
        val startY    = -(cameraY % gridSize)

        var x = startX
        while (x < vw) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, vh), strokeWidth = 1f)
            x += gridSize
        }
        var y = startY
        while (y < vh) {
            drawLine(gridColor, Offset(0f, y), Offset(vw, y), strokeWidth = 1f)
            y += gridSize
        }

        // Vignette corners
        val vigColor = Color(0x55000000)
        val vigSize  = 120f
        drawRect(vigColor, topLeft = Offset(0f, 0f),           size = Size(vigSize, vh))
        drawRect(vigColor, topLeft = Offset(vw - vigSize, 0f), size = Size(vigSize, vh))
        drawRect(vigColor, topLeft = Offset(0f, 0f),           size = Size(vw, vigSize))
        drawRect(vigColor, topLeft = Offset(0f, vh - vigSize), size = Size(vw, vigSize))
    }

    // ── Pickups ─────────────────────────────────────────────────────────────

    private fun DrawScope.renderPickups(world: World) {
        val pulse = (sin(System.currentTimeMillis() * 0.004f) * 0.15f + 0.85f)

        for (pkId in world.getPickupEntities()) {
            val t = world.transforms[pkId] ?: continue
            val r = world.renders[pkId]    ?: continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY
            if (!inViewport(sx, sy, r.width)) continue

            val sz = r.width * pulse

            if (r.glowRadius > 0f) {
                drawCircle(
                    color  = Color(r.glowColor),
                    radius = sz * 0.5f + r.glowRadius * pulse,
                    center = Offset(sx, sy)
                )
            }
            drawCircle(color = Color(r.color), radius = sz * 0.5f, center = Offset(sx, sy))
            drawCircle(
                color  = Color(0x33FFFFFF),
                radius = sz * 0.25f,
                center = Offset(sx - sz * 0.12f, sy - sz * 0.12f)
            )
        }
    }

    // ── Enemy glows ────────────────────────────────────────────────────────

    private fun DrawScope.renderEnemyGlows(world: World) {
        for (eid in world.getEnemyEntities()) {
            val t = world.transforms[eid] ?: continue
            val r = world.renders[eid]    ?: continue
            if (r.glowRadius <= 0f) continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY
            if (!inViewport(sx, sy, r.width + r.glowRadius * 2f)) continue

            drawCircle(
                color  = Color(r.glowColor),
                radius = r.width * 0.5f + r.glowRadius,
                center = Offset(sx, sy)
            )
        }
    }

    // ── Enemies ─────────────────────────────────────────────────────────────

    private fun DrawScope.renderEnemies(world: World) {
        for (eid in world.getEnemyEntities()) {
            val t      = world.transforms[eid] ?: continue
            val r      = world.renders[eid]    ?: continue
            val health = world.healths[eid]    ?: continue
            if (health.isDead) continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY
            if (!inViewport(sx, sy, r.width)) continue

            val drawColor = if (r.flashTimer > 0f) Color.White else Color(r.color)

            when (r.shape) {
                RenderShape.CIRCLE -> {
                    drawCircle(drawColor, r.width * 0.5f, Offset(sx, sy))
                    renderHealthBar(sx, sy - r.width * 0.5f - 10f, r.width, health.percentage)
                }
                RenderShape.RECT -> {
                    drawRect(
                        drawColor,
                        topLeft = Offset(sx - r.width * 0.5f, sy - r.height * 0.5f),
                        size    = Size(r.width, r.height)
                    )
                    renderHealthBar(sx, sy - r.height * 0.5f - 10f, r.width, health.percentage)
                }
                RenderShape.TRIANGLE -> {
                    drawTriangle(sx, sy, r.width * 0.5f, drawColor, t.rotation)
                    renderHealthBar(sx, sy - r.width * 0.5f - 14f, r.width, health.percentage)
                }
            }

            // Boss ring
            if (world.bosses.containsKey(eid)) {
                drawCircle(
                    color  = Color(0x88FF6B6B),
                    radius = r.width * 0.5f + 6f,
                    center = Offset(sx, sy),
                    style  = Stroke(width = 3f)
                )
            }
        }
    }

    private fun DrawScope.renderHealthBar(cx: Float, topY: Float, width: Float, pct: Float) {
        if (pct >= 1f) return
        val barW = width * 1.2f
        val barH = 5f
        val left = cx - barW * 0.5f
        drawRect(Color(0xFF333333), topLeft = Offset(left, topY), size = Size(barW, barH))
        val fillColor = when {
            pct > 0.6f -> Color(0xFF4CAF50)
            pct > 0.3f -> Color(0xFFFFC107)
            else       -> Color(0xFFF44336)
        }
        drawRect(fillColor, topLeft = Offset(left, topY), size = Size(barW * pct, barH))
    }

    private fun DrawScope.drawTriangle(cx: Float, cy: Float, r: Float, color: Color, rotation: Float) {
        // Reuse pre-allocated Path — reset and refill instead of allocating new
        trianglePath.reset()
        for (i in 0..2) {
            val angle = rotation + i * 2f * PI.toFloat() / 3f - PI.toFloat() / 2f
            val px    = cx + cos(angle) * r
            val py    = cy + sin(angle) * r
            if (i == 0) trianglePath.moveTo(px, py) else trianglePath.lineTo(px, py)
        }
        trianglePath.close()
        drawPath(trianglePath, color)
    }

    // ── Orbital weapons ────────────────────────────────────────────────────

    private fun DrawScope.renderOrbitals(world: World, pid: Int) {
        if (pid == -1) return
        val pt       = world.transforms[pid] ?: return
        val orbitals = world.orbitals[pid]   ?: return

        for (orb in orbitals) {
            val ox = pt.x + cos(orb.currentAngle) * orb.orbitRadius - cameraX
            val oy = pt.y + sin(orb.currentAngle) * orb.orbitRadius - cameraY
            drawCircle(Color(0x44F9A825), orb.size * 1.8f, Offset(ox, oy))
            drawCircle(Color(0xFFF9A825), orb.size,         Offset(ox, oy))
            drawCircle(Color(0x55FFFFFF), orb.size * 0.4f,  Offset(ox - orb.size * 0.2f, oy - orb.size * 0.2f))
        }
    }

    // ── Player ─────────────────────────────────────────────────────────────

    private fun DrawScope.renderPlayer(world: World, pid: Int, dt: Float) {
        if (pid == -1) return
        val t      = world.transforms[pid] ?: return
        val r      = world.renders[pid]    ?: return
        val health = world.healths[pid]    ?: return

        val sx = t.x - cameraX
        val sy = t.y - cameraY

        // Invincibility flicker
        if (health.invincibleTimer > 0f) {
            val flicker = ((health.invincibleTimer * 20f).toInt() % 2 == 0)
            if (flicker) return
        }

        // Outer glow
        if (r.glowRadius > 0f) {
            drawCircle(Color(r.glowColor), r.width * 0.5f + r.glowRadius, Offset(sx, sy))
        }

        // Garlic aura rings
        val auras = world.auras[pid]
        if (auras != null) {
            val time  = System.currentTimeMillis() * 0.003f
            val pulse = sin(time) * 0.08f + 0.92f
            for (aura in auras) {
                drawCircle(
                    color  = Color(0x2289BA5B),
                    radius = aura.radius * pulse,
                    center = Offset(sx, sy),
                    style  = Stroke(width = 2f)
                )
            }
        }

        val flashColor = if (r.flashTimer > 0f) Color.White else Color(r.color)
        drawCircle(flashColor, r.width * 0.5f, Offset(sx, sy))
        drawCircle(Color(r.secondaryColor), r.width * 0.28f, Offset(sx, sy))
        drawCircle(Color(0x55FFFFFF), r.width * 0.18f,
            Offset(sx - r.width * 0.14f, sy - r.width * 0.14f))
    }

    // ── Projectiles ────────────────────────────────────────────────────────

    private fun DrawScope.renderProjectiles(world: World) {
        for (projId in world.getProjectileEntities()) {
            val t    = world.transforms[projId]  ?: continue
            val r    = world.renders[projId]     ?: continue
            val proj = world.projectiles[projId] ?: continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY
            if (!inViewport(sx, sy, r.width)) continue

            if (r.glowRadius > 0f) {
                drawCircle(Color(r.glowColor), r.width * 0.5f + r.glowRadius, Offset(sx, sy))
            }

            val alpha = if (proj.isCritical) 1f else 0.88f + sin(System.currentTimeMillis() * 0.01f).toFloat() * 0.12f
            drawCircle(Color(r.color).copy(alpha = alpha), r.width * 0.5f, Offset(sx, sy))

            if (proj.isCritical) {
                drawCircle(Color(0xFFFFD740), r.width * 0.28f, Offset(sx, sy))
            }
        }
    }

    // ── Particles ──────────────────────────────────────────────────────────

    private fun DrawScope.renderParticles(world: World) {
        for (pId in world.getParticleEntities()) {
            val t = world.transforms[pId] ?: continue
            val p = world.particles[pId]  ?: continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY
            if (!inViewport(sx, sy, p.size * 2f)) continue

            val lifeRatio = (p.lifetime / p.maxLifetime).coerceIn(0f, 1f)
            drawCircle(Color(p.color).copy(alpha = lifeRatio), p.size * lifeRatio, Offset(sx, sy))
        }
    }

    // ── Damage Numbers ─────────────────────────────────────────────────────

    private fun DrawScope.renderDamageNumbers(world: World) {
        for (dnId in world.getDamageNumberEntities()) {
            val t  = world.transforms[dnId]    ?: continue
            val dn = world.damageNumbers[dnId] ?: continue

            val sx = t.x - cameraX
            val sy = t.y - cameraY

            val lifeRatio = (dn.lifetime / dn.maxLifetime).coerceIn(0f, 1f)
            val color     = if (dn.isCritical) Color(0xFFFFD740) else Color.White
            val fontSize  = if (dn.isCritical) 18.sp else 13.sp

            drawText(
                textMeasurer = textMeasurer,
                text         = if (dn.isCritical) "★${dn.value}" else "${dn.value}",
                topLeft      = Offset(sx - 12f, sy),
                style        = TextStyle(color = color.copy(alpha = lifeRatio), fontSize = fontSize)
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun inViewport(sx: Float, sy: Float, size: Float): Boolean =
        sx > -size && sx < 2000f + size && sy > -size && sy < 1200f + size

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun getCameraOffset(): Offset = Offset(cameraX, cameraY)
}
