package com.kotlinsurvivors.engine.rendering

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.ecs.components.RenderShape
import com.kotlinsurvivors.engine.ecs.components.WeaponType
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
    private var cameraPlaced = false

    // Camera actually used for drawing this frame: smoothed position plus shake.
    private var cameraX = 0f
    private var cameraY = 0f

    // Size of the area being drawn, refreshed every frame, for culling.
    private var screenW = 0f
    private var screenH = 0f

    // RenderShape.values() allocates a new array on every call; keep one copy.
    private val shapes = RenderShape.values()

    /**
     * Pixel-art images. While this is null (still loading, or a file is
     * missing) everything is drawn with the original plain shapes.
     */
    var atlas: SpriteAtlas? = null

    /**
     * The picture of the level being played. While it is null the repeating ground is
     * drawn instead. Setting it also switches on lamp glows, rain and the camera limits.
     */
    var levelArt: LevelArt? = null

    private var glowImage: ImageBitmap? = null
    private var glowSource: Bitmap? = null
    private val rainPaint = Paint().apply {
        isAntiAlias = false
        strokeWidth = RAIN_WIDTH
        color       = RAIN_COLOR
    }
    private val rainPoints = FloatArray(RAIN_DROPS * 4)
    private val rainSeeds  = FloatArray(RAIN_DROPS * 3).also {
        val random = kotlin.random.Random(RAIN_SEED)
        for (i in it.indices) it[i] = random.nextFloat()
    }

    // Nearest-neighbour paints keep the pixel art crisp when it is scaled up.
    private val spritePaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val flashPaint  = Paint().apply {
        isAntiAlias   = false
        isFilterBitmap = false
        colorFilter   = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
    }
    private val groundPaint  = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val groundMatrix = Matrix()
    private var groundShader: BitmapShader? = null
    private var groundShaderSource: Bitmap? = null

    // Reused every draw call so sprites allocate nothing.
    private val srcRect = Rect()
    private val dstRect = RectF()

    private val knifeOrdinal    = WeaponType.KNIFE.ordinal
    private val fireWandOrdinal = WeaponType.FIRE_WAND.ordinal
    private val axeOrdinal      = WeaponType.AXE.ordinal
    private val crossOrdinal    = WeaponType.CROSS.ordinal
    private val bibleOrdinal    = WeaponType.BIBLE.ordinal

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
            var targetCX = buf.cameraTargetX - viewportWidth  * 0.5f
            var targetCY = buf.cameraTargetY - viewportHeight * 0.5f
            if (levelArt != null) {
                // Never show what lies beyond the edge of the map.
                targetCX = targetCX.coerceIn(0f, (Level1.WORLD_SIZE - viewportWidth).coerceAtLeast(0f))
                targetCY = targetCY.coerceIn(0f, (Level1.WORLD_SIZE - viewportHeight).coerceAtLeast(0f))
            }
            if (!cameraPlaced) {            // first frame: start on the player instead of sweeping in from 0,0
                smoothX = targetCX; smoothY = targetCY; cameraPlaced = true
            }
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
                if (atlas == null && (k == RenderKind.ENEMY || k == RenderKind.BOSS) && buf.glowRadius[i] > 0f) {
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
            // Pass 7b: lamp light and rain (level pictures only)
            val art = levelArt
            if (art != null) {
                drawLampGlows(art)
                drawRain()
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
        val art = levelArt
        if (art != null) {
            drawLevel(art, vw, vh)
            return
        }
        val sprites = atlas
        if (sprites != null) {
            drawGround(sprites, vw, vh)
            return
        }
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

    /**
     * Draws the part of the baked level picture that is on screen. The source rectangle is
     * cut on whole image pixels and the destination follows from it, so every image pixel
     * becomes a crisp 2x2 block of world pixels.
     */
    private fun DrawScope.drawLevel(art: LevelArt, vw: Float, vh: Float) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawColor(LEVEL_BACKDROP)
        val scale = Level1.IMAGE_SCALE
        val bmp   = art.level
        val camX  = floor(cameraX)
        val camY  = floor(cameraY)
        val left   = floor(camX / scale).toInt().coerceIn(0, bmp.width)
        val top    = floor(camY / scale).toInt().coerceIn(0, bmp.height)
        val right  = ceil((camX + vw) / scale).toInt().coerceIn(0, bmp.width)
        val bottom = ceil((camY + vh) / scale).toInt().coerceIn(0, bmp.height)
        if (right <= left || bottom <= top) return
        srcRect.set(left, top, right, bottom)
        dstRect.set(left * scale - camX, top * scale - camY, right * scale - camX, bottom * scale - camY)
        canvas.drawBitmap(bmp, srcRect, dstRect, spritePaint)
    }

    /** A soft warm light on every lamp that is on screen, added on top of the picture. */
    private fun DrawScope.drawLampGlows(art: LevelArt) {
        if (glowSource !== art.glow) {
            glowImage  = art.glow.asImageBitmap()
            glowSource = art.glow
        }
        val glow = glowImage ?: return
        val lamps = Level1.LAMPS
        val now   = System.currentTimeMillis()
        val size  = GLOW_SIZE.toInt()
        var i = 0
        while (i < lamps.size) {
            val sx = lamps[i] - cameraX
            val sy = lamps[i + 1] - cameraY
            if (inViewport(sx, sy, GLOW_SIZE * 0.5f)) {
                // Each lamp flickers a little, out of step with the others.
                val flicker = GLOW_ALPHA + GLOW_FLICKER * sin(now * 0.004f + i * 1.7f)
                drawImage(
                    image         = glow,
                    dstOffset     = IntOffset((sx - size / 2).toInt(), (sy - size / 2).toInt()),
                    dstSize       = IntSize(size, size),
                    alpha         = flicker,
                    blendMode     = BlendMode.Plus,
                    filterQuality = FilterQuality.Low
                )
            }
            i += 2
        }
    }

    /** Slanted rain streaks over the whole screen, computed from the clock: no allocation, no state. */
    private fun DrawScope.drawRain() {
        val t = System.nanoTime() / 1_000_000_000.0
        val w = screenW
        val h = screenH + RAIN_LENGTH
        for (i in 0 until RAIN_DROPS) {
            val speed = RAIN_SPEED + rainSeeds[i * 3 + 2] * RAIN_SPEED_SPREAD
            val y = wrap(rainSeeds[i * 3 + 1] * h + (t * speed).toFloat(), h) - RAIN_LENGTH
            val x = wrap(rainSeeds[i * 3] * w - (t * RAIN_DRIFT).toFloat(), w)
            val o = i * 4
            rainPoints[o]     = x
            rainPoints[o + 1] = y
            rainPoints[o + 2] = x - RAIN_LENGTH * RAIN_SLANT
            rainPoints[o + 3] = y + RAIN_LENGTH
        }
        drawContext.canvas.nativeCanvas.drawLines(rainPoints, rainPaint)
    }

    private fun wrap(value: Float, period: Float) = value - floor(value / period) * period

    /** Tiled street, scattered clutter and a darkened border. */
    private fun DrawScope.drawGround(a: SpriteAtlas, vw: Float, vh: Float) {
        if (groundShader == null || groundShaderSource !== a.ground) {
            groundShader = BitmapShader(a.ground, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            groundShaderSource = a.ground
            groundPaint.shader = groundShader
        }
        groundMatrix.setScale(GROUND_SCALE, GROUND_SCALE)
        groundMatrix.postTranslate(-floor(cameraX), -floor(cameraY))
        groundShader?.setLocalMatrix(groundMatrix)

        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawRect(0f, 0f, vw, vh, groundPaint)

        // Decals: at most one per world cell, chosen by hashing the cell coordinates,
        // so the same spot always shows the same thing.
        val decalSize = SpriteAtlas.DECAL_CELL * GROUND_SCALE
        val firstX = floor(cameraX / DECAL_CELL_WORLD).toInt()
        val lastX  = floor((cameraX + vw) / DECAL_CELL_WORLD).toInt()
        val firstY = floor(cameraY / DECAL_CELL_WORLD).toInt()
        val lastY  = floor((cameraY + vh) / DECAL_CELL_WORLD).toInt()
        for (cy in firstY..lastY) {
            for (cx in firstX..lastX) {
                val h = cellHash(cx, cy)
                if (h % 100 >= DECAL_CHANCE_PERCENT) continue
                val type = (h ushr 8) % a.decalCount
                val room = DECAL_CELL_WORLD - decalSize
                val sx = cx * DECAL_CELL_WORLD + ((h ushr 4) and 0xFF) / 255f * room - cameraX
                val sy = cy * DECAL_CELL_WORLD + ((h ushr 12) and 0xFF) / 255f * room - cameraY
                if (sx < -decalSize || sx > vw || sy < -decalSize || sy > vh) continue
                srcRect.set(type * SpriteAtlas.DECAL_CELL, 0, (type + 1) * SpriteAtlas.DECAL_CELL, a.decals.height)
                dstRect.set(sx, sy, sx + decalSize, sy + decalSize)
                canvas.drawBitmap(a.decals, srcRect, dstRect, spritePaint)
            }
        }
    }

    /** Stable pseudo-random non-negative number for a pair of cell coordinates. */
    private fun cellHash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    /** Draws cell ([col], [row]) of a sheet, centered on ([cx], [cy]). */
    private fun DrawScope.drawCell(
        sheet: Bitmap, col: Int, row: Int, cellW: Int, cellH: Int,
        cx: Float, cy: Float, w: Float, h: Float, paint: Paint
    ) {
        srcRect.set(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
        dstRect.set(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
        drawContext.canvas.nativeCanvas.drawBitmap(sheet, srcRect, dstRect, paint)
    }

    private fun DrawScope.drawShadow(cx: Float, feetY: Float, w: Float) {
        drawOval(Color(0x55000000), topLeft = Offset(cx - w * 0.5f, feetY - w * 0.1f), size = Size(w, w * 0.22f))
    }

    // ── Pickup ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPickup(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i])) return
        val pulse = (sin(System.currentTimeMillis() * 0.004f) * 0.15f + 0.85f)
        val sz    = buf.width[i] * pulse
        if (buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), sz * 0.5f + buf.glowRadius[i] * pulse, Offset(sx, sy))
        val sprites = atlas
        val id      = buf.spriteId[i]
        if (sprites != null && id >= 0 && id * SpriteAtlas.ITEM_CELL < sprites.pickups.width) {
            val size = sz * PICKUP_SPRITE_SCALE
            drawCell(sprites.pickups, id, 0, SpriteAtlas.ITEM_CELL, sprites.pickups.height, sx, sy, size, size, spritePaint)
            return
        }
        drawCircle(Color(buf.getColor(i)), sz * 0.5f, Offset(sx, sy))
        drawCircle(Color(0x33FFFFFF), sz * 0.25f, Offset(sx - sz * 0.12f, sy - sz * 0.12f))
    }

    // ── Enemy ───────────────────────────────────────────────────────────────

    private fun DrawScope.renderEnemy(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i] * 2f)) return
        val isFlashing = buf.hasFlag(i, RenderBuffer.FLAG_FLASHING)
        val sprites    = atlas
        val typeId     = buf.spriteId[i]
        if (sprites != null && typeId >= 0 && typeId < sprites.enemies.size) {
            renderEnemySprite(sprites, buf, i, typeId, sx, sy, isFlashing)
            return
        }
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

    private fun DrawScope.renderEnemySprite(
        a: SpriteAtlas, buf: RenderBuffer, i: Int, typeId: Int, sx: Float, sy: Float, flashing: Boolean
    ) {
        val isBoss  = buf.hasFlag(i, RenderBuffer.FLAG_IS_BOSS)
        val sheet   = a.enemies[typeId]
        val frameW  = a.enemyFrameWidth(typeId)
        val h       = buf.width[i] * (if (isBoss) BOSS_SPRITE_SCALE else ENEMY_SPRITE_SCALE)
        val w       = h * frameW / sheet.height
        val frame   = ((System.currentTimeMillis() / ENEMY_FRAME_MS) and 1L).toInt()
        val bob     = if (frame == 1) -h * 0.04f else 0f

        drawShadow(sx, sy + h * 0.5f, w * 0.8f)
        drawCell(sheet, frame, 0, frameW, sheet.height, sx, sy + bob, w, h, if (flashing) flashPaint else spritePaint)

        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
            drawHealthBar(sx, sy - h * 0.5f - 10f, w * 0.85f, buf.hpPercent[i])
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
        val sprites = atlas
        if (sprites != null && (bibleOrdinal + 1) * SpriteAtlas.ITEM_CELL <= sprites.projectiles.width) {
            drawCircle(Color(0x33F9A825), sz * 1.6f, Offset(ox, oy))
            val size = sz * ORBITAL_SPRITE_SCALE
            drawCell(sprites.projectiles, bibleOrdinal, 0, SpriteAtlas.ITEM_CELL, sprites.projectiles.height, ox, oy, size, size, spritePaint)
            return
        }
        drawCircle(Color(0x44F9A825), sz * 1.8f, Offset(ox, oy))
        drawCircle(Color(0xFFF9A825), sz,          Offset(ox, oy))
        drawCircle(Color(0x55FFFFFF), sz * 0.4f,   Offset(ox - sz * 0.2f, oy - sz * 0.2f))
    }

    // ── Player ──────────────────────────────────────────────────────────────

    private fun DrawScope.renderPlayer(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        // Invincibility flicker
        if (buf.invincTimer[i] > 0f && ((buf.invincTimer[i] * 20f).toInt() % 2 == 0)) return
        val sprites = atlas
        if (sprites == null && buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), buf.width[i] * 0.5f + buf.glowRadius[i], Offset(sx, sy))
        if (buf.hasFlag(i, RenderBuffer.FLAG_HAS_AURA)) {
            val pulse = sin(System.currentTimeMillis() * 0.003f) * 0.08f + 0.92f
            drawCircle(Color(0x2289BA5B), buf.auraRadius[i] * pulse, Offset(sx, sy), style = Stroke(2f))
        }
        if (sprites != null) {
            renderPlayerSprite(sprites, buf, i, sx, sy)
            return
        }
        val bodyColor = if (buf.hasFlag(i, RenderBuffer.FLAG_FLASHING)) Color.White else Color(buf.getColor(i))
        drawCircle(bodyColor, buf.width[i] * 0.5f, Offset(sx, sy))
        drawCircle(Color(buf.getSecColor(i)), buf.width[i] * 0.28f, Offset(sx, sy))
        drawCircle(Color(0x55FFFFFF), buf.width[i] * 0.18f,
            Offset(sx - buf.width[i] * 0.14f, sy - buf.width[i] * 0.14f))
    }

    private fun DrawScope.renderPlayerSprite(a: SpriteAtlas, buf: RenderBuffer, i: Int, sx: Float, sy: Float) {
        val row    = buf.spriteId[i].coerceIn(0, SpriteAtlas.PLAYER_ROWS - 1)
        val moving = buf.hasFlag(i, RenderBuffer.FLAG_MOVING)
        val column = if (moving)
            1 + ((System.currentTimeMillis() / PLAYER_FRAME_MS) % (SpriteAtlas.PLAYER_COLUMNS - 1)).toInt()
        else 0
        val h = buf.width[i] * PLAYER_SPRITE_SCALE
        val w = h * a.playerCellW / a.playerCellH
        val centerY = sy - h * 0.08f

        drawShadow(sx, centerY + h * 0.5f, w * 0.7f)
        val paint = if (buf.hasFlag(i, RenderBuffer.FLAG_FLASHING)) flashPaint else spritePaint
        drawCell(a.player, column, row, a.playerCellW, a.playerCellH, sx, centerY, w, h, paint)
    }

    // ── Projectile ──────────────────────────────────────────────────────────

    private fun DrawScope.renderProjectile(buf: RenderBuffer, i: Int) {
        val sx = buf.x[i] - cameraX; val sy = buf.y[i] - cameraY
        if (!inViewport(sx, sy, buf.width[i])) return
        if (buf.glowRadius[i] > 0f)
            drawCircle(Color(buf.getGlowColor(i)), buf.width[i] * 0.5f + buf.glowRadius[i], Offset(sx, sy))
        val isCrit  = buf.hasFlag(i, RenderBuffer.FLAG_IS_CRITICAL)
        val sprites = atlas
        val weapon  = buf.spriteId[i]
        if (sprites != null && weapon >= 0 && (weapon + 1) * SpriteAtlas.ITEM_CELL <= sprites.projectiles.width) {
            val size = (buf.width[i] * PROJECTILE_SPRITE_SCALE).coerceAtLeast(MIN_PROJECTILE_SPRITE)
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.rotate(projectileAngle(weapon, buf.rotation[i]), sx, sy)
            drawCell(sprites.projectiles, weapon, 0, SpriteAtlas.ITEM_CELL, sprites.projectiles.height, sx, sy, size, size, spritePaint)
            canvas.restore()
            if (isCrit) drawCircle(Color(0x66FFD740), size * 0.6f, Offset(sx, sy))
            return
        }
        val alpha  = if (isCrit) 1f else 0.88f + sin(System.currentTimeMillis() * 0.01f).toFloat() * 0.12f
        drawCircle(Color(buf.getColor(i)).copy(alpha = alpha), buf.width[i] * 0.5f, Offset(sx, sy))
        if (isCrit) drawCircle(Color(0xFFFFD740), buf.width[i] * 0.28f, Offset(sx, sy))
    }

    /** Rotation in degrees for a projectile sprite, depending on how the weapon behaves. */
    private fun projectileAngle(weapon: Int, headingRadians: Float): Float {
        val heading = Math.toDegrees(headingRadians.toDouble()).toFloat()
        return when (weapon) {
            knifeOrdinal    -> heading + 45f     // drawn pointing up-right
            fireWandOrdinal -> heading + 90f     // drawn pointing up
            axeOrdinal, crossOrdinal -> ((System.currentTimeMillis() % SPIN_PERIOD_MS) * 360f) / SPIN_PERIOD_MS
            else            -> 0f
        }
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

        // Sprite sizes are expressed relative to the collision size of each entity.
        const val PLAYER_SPRITE_SCALE     = 2.5f
        const val ENEMY_SPRITE_SCALE      = 1.9f
        const val BOSS_SPRITE_SCALE       = 1.5f
        const val PICKUP_SPRITE_SCALE     = 1.5f
        const val PROJECTILE_SPRITE_SCALE = 1.9f
        const val MIN_PROJECTILE_SPRITE   = 22f
        const val ORBITAL_SPRITE_SCALE    = 3.4f

        const val PLAYER_FRAME_MS = 110L
        const val ENEMY_FRAME_MS  = 220L
        const val SPIN_PERIOD_MS  = 500L

        // Ground: each 32px tile is drawn 3x, clutter is placed on a coarse world grid.
        const val LEVEL_BACKDROP = 0xFF0A0A14.toInt()
        const val GLOW_SIZE      = 300f
        const val GLOW_ALPHA     = 0.85f
        const val GLOW_FLICKER   = 0.08f

        // Rain: sizes in screen pixels, speeds in pixels per second.
        const val RAIN_DROPS        = 120
        const val RAIN_SEED         = 4242
        const val RAIN_LENGTH       = 26f
        const val RAIN_WIDTH        = 2f
        const val RAIN_SLANT        = 0.18f
        const val RAIN_SPEED        = 1100f
        const val RAIN_SPEED_SPREAD = 500f
        const val RAIN_DRIFT        = 200f
        const val RAIN_COLOR        = 0x55BFD8FF

        const val GROUND_SCALE        = 3f
        const val DECAL_CELL_WORLD    = 360f
        const val DECAL_CHANCE_PERCENT = 40
    }
}
