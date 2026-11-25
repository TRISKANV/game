package com.kotlinsurvivors.engine.rendering

import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
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
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
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
        set(value) {
            if (field === value) return
            field = value
            neighbour.lines = value?.npcLines ?: emptyArray()
            cameraPlaced = false        // a new level: the camera jumps to the player instead of sweeping across the map
        }

    /** Set from the game state every frame: while false, the barrier at the end of the street is drawn. */
    var exitOpen = false

    /** What the officer shouts when he recognises the player, in the language of the phone; set by the screen. */
    var copShout: String = ""

    /** The neighbour of Level 1; only drawn while [levelArt] is set, which also gives him his lines. */
    val neighbour = NeighbourRenderer()

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
    private val exitArrowPaint = Paint().apply {
        isAntiAlias = false
        color       = 0xFFFFD36B.toInt()
        strokeWidth = 8f
        strokeCap   = Paint.Cap.SQUARE
    }
    private val shoutTextPaint = Paint().apply {
        isAntiAlias = false
        color       = 0xFF14101C.toInt()
        textSize    = 15f
        typeface    = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
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
                val worldSize = levelArt?.info?.worldSize ?: Level1.WORLD_SIZE
                targetCX = targetCX.coerceIn(0f, (worldSize - viewportWidth).coerceAtLeast(0f))
                targetCY = targetCY.coerceIn(0f, (worldSize - viewportHeight).coerceAtLeast(0f))
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
            // Pass 2b: leashes, under the bodies they join
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.LEASH) renderLeash(buf, i)
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
            // Pass 5: Neighbour and player, whichever stands lower on the screen is drawn last
            val levelPicture = levelArt
            val now = System.currentTimeMillis()
            var playerFeetY = Float.NaN
            var playerFeetX = 0f
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PLAYER) { playerFeetX = buf.x[i]; playerFeetY = buf.y[i] + MovementSystem.PLAYER_FEET_OFFSET; break }
            }
            val hasNeighbour = levelPicture != null && levelPicture.info.number == 1
            val neighbourDrawn = hasNeighbour && !playerFeetY.isNaN()
            val neighbourBehind = neighbourDrawn && neighbour.isBehindPlayer(playerFeetY)
            if (neighbourDrawn) neighbour.update(playerFeetX, playerFeetY, now)
            if (levelPicture != null && hasNeighbour && (neighbourBehind || playerFeetY.isNaN())) with(neighbour) { drawSprite(levelPicture, cameraX, cameraY, now) }
            for (i in 0 until buf.count) {
                if (buf.kind[i] == RenderKind.PLAYER) renderPlayer(buf, i)
            }
            if (levelPicture != null && hasNeighbour && !neighbourBehind && !playerFeetY.isNaN()) with(neighbour) { drawSprite(levelPicture, cameraX, cameraY, now) }
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
                if (art.info.number != 3) drawRain()          // it does not rain indoors
                drawExitArrow(art)
            }
            // Pass 7c: the neighbour's speech bubble, over everything in the world
            if (art != null && art.info.number == 1) with(neighbour) { drawBubble(cameraX, cameraY, now) }
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
        if (levelArt?.info?.number == 1) drawContext.canvas.nativeCanvas.drawText(neighbour.debugLine(), size.width - 16f, size.height - 16f - fpsPaint.textSize * 1.4f, fpsPaint)
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
        val scale = art.info.imageScale
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

        // The end of the street is shut with a barrier until the first boss is down.
        val barrier = art.exitBarrier
        if (barrier != null && !exitOpen) {
            val bx = Level1.BARRIER_DRAW_X - camX
            val by = Level1.BARRIER_DRAW_Y - camY
            dstRect.set(bx, by, bx + barrier.width * scale, by + barrier.height * scale)
            canvas.drawBitmap(barrier, null, dstRect, spritePaint)
        }
    }

    /** Once the way out is open, a pulsing arrow at the edge of the screen shows where it is. */
    private fun DrawScope.drawExitArrow(art: LevelArt) {
        if (!exitOpen || art.info.number != 1) return
        val targetX = (Level1.EXIT_X0 + Level1.EXIT_X1) * 0.5f - cameraX
        val targetY = Level1.EXIT_TRIGGER_Y - cameraY
        if (targetX in 0f..screenW && targetY in 0f..screenH) return
        val dx = targetX - screenW * 0.5f
        val dy = targetY - screenH * 0.5f
        val len = hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val reachX = if (abs(ux) < 0.001f) Float.MAX_VALUE else (screenW * 0.5f - EXIT_ARROW_MARGIN) / abs(ux)
        val reachY = if (abs(uy) < 0.001f) Float.MAX_VALUE else (screenH * 0.5f - EXIT_ARROW_MARGIN) / abs(uy)
        val reach = min(reachX, reachY)
        val px = screenW * 0.5f + ux * reach
        val py = screenH * 0.5f + uy * reach
        val pulse = 0.65f + 0.35f * sin(System.currentTimeMillis() * 0.008f)
        exitArrowPaint.alpha = (255 * pulse).toInt()
        val canvas = drawContext.canvas.nativeCanvas
        val nx = -uy
        val ny = ux
        val tipX = px + ux * EXIT_ARROW_SIZE;      val tipY = py + uy * EXIT_ARROW_SIZE
        val aX   = px - ux * EXIT_ARROW_SIZE + nx * EXIT_ARROW_SIZE; val aY = py - uy * EXIT_ARROW_SIZE + ny * EXIT_ARROW_SIZE
        val bX   = px - ux * EXIT_ARROW_SIZE - nx * EXIT_ARROW_SIZE; val bY = py - uy * EXIT_ARROW_SIZE - ny * EXIT_ARROW_SIZE
        canvas.drawLine(aX, aY, tipX, tipY, exitArrowPaint)
        canvas.drawLine(bX, bY, tipX, tipY, exitArrowPaint)
        canvas.drawLine(px - ux * EXIT_ARROW_SIZE, py - uy * EXIT_ARROW_SIZE, tipX, tipY, exitArrowPaint)
    }

    /** A soft warm light on every lamp that is on screen, added on top of the picture. */
    private fun DrawScope.drawLampGlows(art: LevelArt) {
        if (glowSource !== art.glow) {
            glowImage  = art.glow.asImageBitmap()
            glowSource = art.glow
        }
        val glow = glowImage ?: return
        val lamps = art.info.lamps
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
        if (id == PickupType.CHEST.ordinal) { renderChest(buf, i, sx, sy); return }
        if (sprites != null && (id == PickupType.HEALTH_SMALL.ordinal || id == PickupType.HEALTH_LARGE.ordinal)) {
            renderEnergyCan(sprites.energyCan, sx, sy, if (id == PickupType.HEALTH_LARGE.ordinal) CAN_LARGE_SCALE else CAN_SMALL_SCALE)
            return
        }
        if (sprites != null && id >= 0 && id * SpriteAtlas.ITEM_CELL < sprites.pickups.width) {
            val size = sz * PICKUP_SPRITE_SCALE
            drawCell(sprites.pickups, id, 0, SpriteAtlas.ITEM_CELL, sprites.pickups.height, sx, sy, size, size, spritePaint)
            return
        }
        drawCircle(Color(buf.getColor(i)), sz * 0.5f, Offset(sx, sy))
        drawCircle(Color(0x33FFFFFF), sz * 0.25f, Offset(sx - sz * 0.12f, sy - sz * 0.12f))
    }

    // ── Energy can (heals) ───────────────────────────────────────────────────

    /** The can floats a little above its shadow and, every couple of seconds, a glint slides down it. */
    private fun DrawScope.renderEnergyCan(sheet: Bitmap, sx: Float, sy: Float, scale: Float) {
        val w = SpriteAtlas.CAN_CELL_W * scale
        val h = SpriteAtlas.CAN_CELL_H * scale
        if (!inViewport(sx, sy, h)) return
        val now  = System.currentTimeMillis()
        val bob  = sin(now * 0.005f) * 2.5f
        val glintStep = (now % CAN_GLINT_PERIOD_MS) / CAN_GLINT_STEP_MS          // 0 = no glint, 1..3 = sliding
        val frame = if (glintStep in 0L until SpriteAtlas.CAN_FRAMES.toLong()) glintStep.toInt() else 0
        drawOval(Color(0x55000000), Offset(sx - w * 0.4f, sy + h * 0.32f), Size(w * 0.8f, h * 0.16f))
        drawCircle(Color(0x30FF3D8B), h * 0.75f, Offset(sx, sy + bob))
        drawCell(sheet, frame, 0, SpriteAtlas.CAN_CELL_W, SpriteAtlas.CAN_CELL_H, sx, sy + bob - h * 0.1f, w, h, spritePaint)
    }

    // ── Boss chest ──────────────────────────────────────────────────────────

    /**
     * On the floor the chest bobs gently, its glow breathes and its LED blinks; once the
     * player touches it ([RenderBuffer.lifetime] holds the seconds it has been opening,
     * -1 while closed) it plays the opening frames and a column of light rises from it.
     */
    private fun DrawScope.renderChest(buf: RenderBuffer, i: Int, sx: Float, sy: Float) {
        val size = CHEST_DRAW_SIZE
        if (!inViewport(sx, sy, size * 2f)) return
        val now      = System.currentTimeMillis()
        val opening  = buf.lifetime[i]
        val progress = if (opening >= 0f) (opening / buf.maxLifetime[i]).coerceIn(0f, 1f) else -1f
        val bob      = if (progress < 0f) sin(now * 0.004f) * 3f else 0f
        val breathe  = sin(now * 0.005f) * 0.25f + 0.75f
        val cy       = sy - 4f + bob

        // soft shadow and a glow that grows while it opens
        drawOval(Color(0x55000000), Offset(sx - size * 0.45f, sy + size * 0.28f), Size(size * 0.9f, size * 0.22f))
        val glowR = size * (0.75f + (if (progress < 0f) 0f else progress * 0.9f)) * breathe
        drawCircle(Color(0x3326C6DA), glowR, Offset(sx, cy))
        drawCircle(Color(0x2226C6DA), glowR * 1.4f, Offset(sx, cy))

        if (progress >= 0f) {                                  // column of light
            val beam = size * 1.6f * progress
            drawRect(Color(0x55B2EBF2), Offset(sx - size * 0.16f, cy - beam), Size(size * 0.32f, beam))
            drawRect(Color(0x88FFFFFF), Offset(sx - size * 0.06f, cy - beam), Size(size * 0.12f, beam))
        }

        val sprites = atlas ?: run {
            drawRect(Color(0xFF455A64), Offset(sx - size * 0.4f, cy - size * 0.3f), Size(size * 0.8f, size * 0.6f))
            return
        }
        val frame = if (progress < 0f) ((now / CHEST_IDLE_FRAME_MS) % SpriteAtlas.CHEST_IDLE_FRAMES).toInt()
                    else SpriteAtlas.CHEST_IDLE_FRAMES +
                         (progress * SpriteAtlas.CHEST_OPEN_FRAMES).toInt().coerceAtMost(SpriteAtlas.CHEST_OPEN_FRAMES - 1)
        drawCell(sprites.chest, frame, 0, SpriteAtlas.CHEST_CELL, sprites.chest.height, sx, cy, size, size, spritePaint)
    }

    // ── Weapon effects: DDoS ring, Zero-Day beam, lit Logic Bomb ────────────

    /** The ring grows from the player to its full radius while it fades. */
    private fun DrawScope.renderDdosRing(buf: RenderBuffer, i: Int, sx: Float, sy: Float) {
        val t = 1f - buf.lifetime[i] / buf.maxLifetime[i]
        val r = buf.width[i] * (0.25f + 0.75f * t)
        val a = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        drawCircle(Color(0xFF26C6DA).copy(alpha = a / 400f), r, Offset(sx, sy))
        drawCircle(Color(0xFF80DEEA).copy(alpha = a / 255f), r, Offset(sx, sy), style = Stroke(width = 5f))
        drawCircle(Color.White.copy(alpha = a / 255f), r * 0.92f, Offset(sx, sy), style = Stroke(width = 2f))
    }

    /** A thick green beam with a white core that thins out and fades. */
    private fun DrawScope.renderZeroDayBeam(buf: RenderBuffer, i: Int, sx: Float, sy: Float) {
        val t     = 1f - buf.lifetime[i] / buf.maxLifetime[i]
        val alpha = (1f - t).coerceIn(0f, 1f)
        val width = (1f - t * 0.7f)
        val ex    = sx + cos(buf.rotation[i]) * buf.width[i]
        val ey    = sy + sin(buf.rotation[i]) * buf.width[i]
        drawLine(Color(0xFF00E676).copy(alpha = alpha * 0.45f), Offset(sx, sy), Offset(ex, ey), strokeWidth = 26f * width)
        drawLine(Color(0xFF69F0AE).copy(alpha = alpha), Offset(sx, sy), Offset(ex, ey), strokeWidth = 12f * width)
        drawLine(Color.White.copy(alpha = alpha), Offset(sx, sy), Offset(ex, ey), strokeWidth = 4f * width)
    }

    /** A black bomb that blinks faster as the fuse burns, with its blast radius marked on the floor. */
    private fun DrawScope.renderLogicBomb(buf: RenderBuffer, i: Int, sx: Float, sy: Float) {
        val left  = (buf.lifetime[i] - 0.3f).coerceAtLeast(0f)                 // seconds to the blast
        val urge  = 1f - (left / EntityFactory.BOMB_FUSE_SECONDS).coerceIn(0f, 1f)
        val blink = ((System.currentTimeMillis() / (260 - (urge * 190f).toLong())) % 2L) == 0L
        drawCircle(Color(0x22EF5350), buf.width[i], Offset(sx, sy))
        drawCircle(Color(0x88EF5350), buf.width[i] * (0.4f + 0.6f * urge), Offset(sx, sy), style = Stroke(width = 2f))
        drawCircle(Color(0xFF263238), 11f, Offset(sx, sy))
        drawCircle(Color(0xFF546E7A), 4f, Offset(sx - 4f, sy - 4f))
        drawLine(Color(0xFFFFC107), Offset(sx + 3f, sy - 9f), Offset(sx + 8f, sy - 14f), strokeWidth = 2f)
        drawCircle(if (blink) Color(0xFFFF1744) else Color(0xFF880E4F), 3f, Offset(sx, sy + 1f))
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
            if (buf.hasFlag(i, RenderBuffer.FLAG_IS_MINI_BOSS)) drawMiniBossRing(sx, sy, buf.width[i] * 0.5f * ENEMY_SPRITE_SCALE)
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

    /** The gold ring that marks a mini-boss; it breathes slowly so it stands out from ordinary enemies. */
    private fun DrawScope.drawMiniBossRing(sx: Float, sy: Float, radius: Float) {
        val pulse = 0.5f + 0.5f * sin(System.currentTimeMillis() * 0.006f)
        drawCircle(Color(0x22FFC107), radius * 1.15f, Offset(sx, sy))
        drawCircle(Color(red = 1f, green = 0.757f, blue = 0.027f, alpha = 0.4f + 0.35f * pulse), radius * (1.0f + 0.08f * pulse), Offset(sx, sy), style = Stroke(width = 3f))
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

        if (typeId == EnemyType.FAST.ordinal) { renderDrone(a, buf, i, sx, sy, flashing); return }
        if (typeId == EnemyType.BASIC.ordinal) { renderGuard(a, buf, i, sx, sy, flashing, EnemyType.BASIC); return }
        if (typeId == EnemyType.COP.ordinal)   { renderGuard(a, buf, i, sx, sy, flashing, EnemyType.COP); return }
        if (typeId == EnemyType.DOG.ordinal)   { renderDog(a, buf, i, sx, sy, flashing); return }

        drawShadow(sx, sy + h * 0.5f, w * 0.8f)
        drawCell(sheet, frame, 0, frameW, sheet.height, sx, sy + bob, w, h, if (flashing) flashPaint else spritePaint)

        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
            drawHealthBar(sx, sy - h * 0.5f - 10f, w * 0.85f, buf.hpPercent[i])
    }

    /**
     * The security guard. His sheet has a walk cycle and a punch for each of the eight headings
     * (see [facingRow]). The engine says where he is heading, whether
     * he is walking, whether he is raising his fists and how long ago his punch landed.
     */
    private fun DrawScope.renderGuard(
        a: SpriteAtlas, buf: RenderBuffer, i: Int, sx: Float, sy: Float, flashing: Boolean, kind: EnemyType
    ) {
        // the security guard and the police officer share one layout: only the picture differs
        val sheet = a.enemies[kind.ordinal]
        val cellW = sheet.width  / SpriteAtlas.GUARD_COLUMNS
        val cellH = sheet.height / SpriteAtlas.GUARD_ROWS
        val h     = buf.width[i] * GUARD_SPRITE_SCALE
        val w     = h * cellW / cellH
        val now   = System.currentTimeMillis()

        val row = facingRow(buf.rotation[i])

        val sincePunch = buf.lifetime[i]
        val punching   = sincePunch < RenderBuffer.GUARD_PUNCH_SECONDS
        val column = when {
            punching -> {
                // four beats: fists up, jab, second jab, back to guard
                val beat = (sincePunch / RenderBuffer.GUARD_PUNCH_SECONDS * 3.99f).toInt().coerceIn(0, 3)
                SpriteAtlas.GUARD_WALK_FRAMES + beat
            }
            buf.hasFlag(i, RenderBuffer.FLAG_WINDING_UP) -> SpriteAtlas.GUARD_WALK_FRAMES        // guard up
            buf.hasFlag(i, RenderBuffer.FLAG_MOVING) ->
                // each guard starts its stride at a different point, so a crowd does not march in step
                ((now / (if (buf.hasFlag(i, RenderBuffer.FLAG_STROLLING)) GUARD_STROLL_MS else GUARD_STEP_MS) + i * 3) % SpriteAtlas.GUARD_WALK_FRAMES).toInt()
            else -> 0
        }

        drawShadow(sx, sy + h * 0.5f, w * 0.7f)
        val paint = if (flashing) flashPaint else spritePaint
        drawCell(sheet, column, row, cellW, cellH, sx, sy, w, h, paint)

        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
            drawHealthBar(sx, sy - h * 0.5f - 6f, w * 0.7f, buf.hpPercent[i])
        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOUTING) && copShout.isNotEmpty()) drawShoutBubble(sx, sy - h * 0.5f - 8f, copShout)
        else if (buf.hasFlag(i, RenderBuffer.FLAG_ALERTED)) drawAlertMark(sx, sy - h * 0.5f - 16f, now)
    }

    /**
     * Row of the guard and dog sheets for a heading (radians, screen coordinates: 0 = right, a quarter
     * turn = down). The eight rows run right, down-right, down, down-left, left, up-left, up, up-right.
     */
    private fun facingRow(rotation: Float): Int {
        val step = Math.round(rotation / (PI.toFloat() / 4f))
        return ((step % FACING_ROWS) + FACING_ROWS) % FACING_ROWS
    }

    /** A pixel speech bubble over an officer's head, its tail pointing down at him. */
    private fun DrawScope.drawShoutBubble(cx: Float, bottomY: Float, message: String) {
        val px = 3f                                                     // one "pixel" of the frame
        val textW = shoutTextPaint.measureText(message)
        val boxW = textW + px * 8f
        val boxH = shoutTextPaint.textSize + px * 6f
        val left = cx - boxW * 0.5f
        val top  = bottomY - px * 4f - boxH
        drawRect(Color(0xFF14101C), Offset(left, top + px), Size(boxW, boxH))                       // hard shadow
        drawRect(Color(0xFF14101C), Offset(left - px, top - px), Size(boxW + px * 2f, boxH + px * 2f))
        drawRect(Color(0xFFFFF6DC), Offset(left, top), Size(boxW, boxH))
        drawRect(Color(0xFFFF3B3B), Offset(left, top), Size(boxW, px))                              // a red edge, like the alert mark
        drawRect(Color(0xFF14101C), Offset(cx - px * 3f, top + boxH), Size(px * 6f, px * 3f))       // the tail
        drawRect(Color(0xFFFFF6DC), Offset(cx - px * 2f, top + boxH), Size(px * 4f, px * 2f))
        drawContext.canvas.nativeCanvas.drawText(message, left + px * 4f, top + px * 3f + shoutTextPaint.textSize * 0.82f, shoutTextPaint)
    }

    /** The red "!" that jumps over a guard's head when he spots the player. */
    private fun DrawScope.drawAlertMark(cx: Float, topY: Float, now: Long) {
        val px = 4f                                           // one "pixel" of the mark
        val hop = if ((now / 90L) % 2L == 0L) 0f else -px
        val y = topY + hop
        drawRect(Color(0xFF120A12), Offset(cx - px * 2f, y - px), Size(px * 4f, px * 9f))
        drawRect(Color(0xFFFF3B3B), Offset(cx - px, y), Size(px * 2f, px * 5f))
        drawRect(Color(0xFFFFB0A0), Offset(cx - px, y), Size(px * 0.5f, px * 5f))
        drawRect(Color(0xFFFF3B3B), Offset(cx - px, y + px * 6f), Size(px * 2f, px * 2f))
    }

    /**
     * The guard dog. Same eight headings as the guard. It trots on patrol, gallops when it is after the player and bites with its head
     * thrown forward; the engine says which through the flags and the bite clock in [lifetime].
     */
    private fun DrawScope.renderDog(
        a: SpriteAtlas, buf: RenderBuffer, i: Int, sx: Float, sy: Float, flashing: Boolean
    ) {
        val sheet = a.enemies[EnemyType.DOG.ordinal]
        val h     = buf.width[i] * DOG_SPRITE_SCALE
        val w     = h * a.dogCellW / a.dogCellH
        val now   = System.currentTimeMillis()

        val row = facingRow(buf.rotation[i])

        val sinceBite = buf.lifetime[i]
        val biting    = sinceBite < DOG_BITE_SECONDS
        val running   = buf.hasFlag(i, RenderBuffer.FLAG_RUNNING)
        val column = when {
            biting -> SpriteAtlas.DOG_BITE_FIRST + (if (sinceBite < DOG_BITE_SECONDS * 0.5f) 0 else 1)
            buf.hasFlag(i, RenderBuffer.FLAG_MOVING) && running ->
                SpriteAtlas.DOG_GALLOP_FIRST + ((now / DOG_GALLOP_MS + i) % 2L).toInt()
            buf.hasFlag(i, RenderBuffer.FLAG_MOVING) ->
                ((now / DOG_TROT_MS + i * 3) % SpriteAtlas.DOG_TROT_FRAMES).toInt()
            else -> 0
        }

        drawShadow(sx, sy + h * 0.42f, w * 0.7f)
        val paint = if (flashing) flashPaint else spritePaint
        drawCell(sheet, column, row, a.dogCellW, a.dogCellH, sx, sy, w, h, paint)
        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
            drawHealthBar(sx, sy - h * 0.5f - 4f, w * 0.7f, buf.hpPercent[i])
    }

    /**
     * The leash between a patrolling guard and his dog: a slightly sagging cord from the guard's
     * hand to the dog's collar, with a loop around the hand. [buf].x/y is the guard, width/height
     * the dog, both in world coordinates.
     */
    private fun DrawScope.renderLeash(buf: RenderBuffer, i: Int) {
        val gx = buf.x[i] - cameraX; val gy = buf.y[i] - cameraY
        val dx = buf.width[i] - cameraX; val dy = buf.height[i] - cameraY
        if (!inViewport((gx + dx) * 0.5f, (gy + dy) * 0.5f, 300f)) return
        val side = if (dx >= gx) 1f else -1f
        val hx = gx + side * LEASH_HAND_X; val hy = gy + LEASH_HAND_Y         // his hand
        val cx = dx;  val cy = dy - LEASH_COLLAR_UP      // the collar ring
        val sag = (hypot(cx - hx, cy - hy) * 0.18f).coerceIn(2f, 14f)
        var px = hx; var py = hy
        for (s in 1..LEASH_SEGMENTS) {
            val u = s / LEASH_SEGMENTS.toFloat()
            val x = hx + (cx - hx) * u
            val y = hy + (cy - hy) * u + sin(u * PI.toFloat()) * sag
            drawLine(Color(0xFF120A12), Offset(px, py), Offset(x, y), strokeWidth = 4.5f)
            drawLine(Color(0xFF8B5A3C), Offset(px, py), Offset(x, y), strokeWidth = 2.5f)
            px = x; py = y
        }
        drawCircle(Color(0xFF120A12), 5f, Offset(hx, hy))
        drawCircle(Color(0xFF8B5A3C), 3.2f, Offset(hx, hy))
        drawCircle(Color(0xFFB8C0D0), 2.2f, Offset(cx, cy))
    }

    /**
     * The drone hovers above the ground: its shadow stays on the floor while the body bobs
     * in the air, and its four rotors spin all the time (drawn here, not in the picture,
     * so they turn smoothly instead of flipping between two frames).
     */
    private fun DrawScope.renderDrone(
        a: SpriteAtlas, buf: RenderBuffer, i: Int, sx: Float, sy: Float, flashing: Boolean
    ) {
        val sheet  = a.enemies[EnemyType.FAST.ordinal]
        val frameW = a.enemyFrameWidth(EnemyType.FAST.ordinal)
        val h      = buf.width[i] * ENEMY_SPRITE_SCALE
        val scale  = h / sheet.height
        val w      = frameW * scale
        val now    = System.currentTimeMillis()
        val frame  = ((now / DRONE_LED_MS) and 1L).toInt()
        val hover  = sin(now * 0.007f + i * 1.7f) * h * 0.05f
        val lift   = h * DRONE_LIFT + hover
        val bodyY  = sy - lift

        // The higher it flies the smaller and paler its shadow.
        drawShadow(sx, sy + h * 0.5f, w * 0.55f)
        drawCell(sheet, frame, 0, frameW, sheet.height, sx, bodyY, w, h, if (flashing) flashPaint else spritePaint)

        val bladeR = DRONE_ROTOR_RADIUS * scale
        for (k in DRONE_ROTOR_HUBS.indices) {
            val hub = DRONE_ROTOR_HUBS[k]
            val cx  = sx + (hub[0] - frameW * 0.5f) * scale
            val cy  = bodyY + (hub[1] - sheet.height * 0.5f) * scale
            drawCircle(Color(0x26DDEEF4), bladeR, Offset(cx, cy))                  // the blur of the blades
            val dir  = if (k % 2 == 0) 1f else -1f                                  // neighbours turn opposite ways
            val head = dir * now * DRONE_ROTOR_SPEED + k * 0.9f                     // where the leading edge is, in radians
            val headDeg = head * (180f / PI.toFloat())
            val topLeft = Offset(cx - bladeR, cy - bladeR)
            val arcSize = Size(bladeR * 2f, bladeR * 2f)
            for (blade in 0..1) {
                val base = headDeg + blade * 180f
                // A fading wedge behind each blade, like the smear a fast propeller leaves.
                for (t in 0 until DRONE_TRAIL_STEPS) {
                    val alpha = (0x70 * (1f - t / DRONE_TRAIL_STEPS.toFloat())).toInt()
                    val start = if (dir > 0f) base - (t + 1) * DRONE_TRAIL_STEP_DEG else base + t * DRONE_TRAIL_STEP_DEG
                    drawArc(Color((alpha shl 24) or 0xECEFF1), start, DRONE_TRAIL_STEP_DEG, true, topLeft, arcSize)
                }
                val b = head + blade * PI.toFloat()
                drawLine(Color(0xEEFFFFFF), Offset(cx, cy), Offset(cx + cos(b) * bladeR, cy + sin(b) * bladeR),
                    strokeWidth = (scale * 0.8f).coerceAtLeast(2f))
            }
            drawCircle(Color(0xFF37474F), (scale * 0.7f).coerceAtLeast(1.5f), Offset(cx, cy))   // the hub
        }
        if (buf.hasFlag(i, RenderBuffer.FLAG_SHOW_HP_BAR))
            drawHealthBar(sx, bodyY - h * 0.5f - 10f, w * 0.85f, buf.hpPercent[i])
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
        when (buf.spriteId[i]) {
            RenderBuffer.SPRITE_WHIP_LASH     -> { renderWhipLash(buf, i, sx, sy); return }
            RenderBuffer.SPRITE_DDOS_RING     -> { renderDdosRing(buf, i, sx, sy); return }
            RenderBuffer.SPRITE_ZERO_DAY_BEAM -> { renderZeroDayBeam(buf, i, sx, sy); return }
            RenderBuffer.SPRITE_LOGIC_BOMB    -> { renderLogicBomb(buf, i, sx, sy); return }
        }
        if (!inViewport(sx, sy, buf.width[i] * 2f)) return
        val lifeRatio = if (buf.maxLifetime[i] > 0f)
            (buf.lifetime[i] / buf.maxLifetime[i]).coerceIn(0f, 1f) else 0f
        drawCircle(Color(buf.getColor(i)).copy(alpha = lifeRatio), buf.width[i] * lifeRatio, Offset(sx, sy))
    }

    // ── Whip: the network cable ─────────────────────────────────────────────

    /**
     * One crack of the cable. It flies out along its lane with a smooth bend that
     * straightens as it reaches full length, snaps (a small flash at the tip), and is
     * pulled back bending the other way. The cable is thick at the hand and thin at the
     * tip, like a real whip. [RenderBuffer.width] is the reach and [RenderBuffer.rotation]
     * the direction in radians. The time left minus [WHIP_LASH_SECONDS] is the delay
     * before it starts.
     */
    private fun DrawScope.renderWhipLash(buf: RenderBuffer, i: Int, ox: Float, oy: Float) {
        val elapsed = buf.maxLifetime[i] - buf.lifetime[i]
        val delay   = buf.maxLifetime[i] - WHIP_LASH_SECONDS
        if (elapsed < delay) return
        val t = ((elapsed - delay) / WHIP_LASH_SECONDS).coerceIn(0f, 1f)

        val angle = buf.rotation[i]
        val dx = cos(angle); val dy = sin(angle)
        val nx = -dy;        val ny = dx                       // perpendicular, the side the cable bends to

        val out    = t < WHIP_OUT_END
        val extend = when {
            t < WHIP_OUT_END  -> t / WHIP_OUT_END
            t < WHIP_HOLD_END -> 1f
            else              -> 1f - (t - WHIP_HOLD_END) / (1f - WHIP_HOLD_END)
        }
        val length = buf.width[i] * extend
        if (length < 6f) return
        val bend = (WHIP_BEND * (1f - extend)).coerceAtMost(length * 0.4f) * (if (out) 1f else -0.6f)

        val handX = ox + dx * 12f
        val handY = oy + dy * 12f - WHIP_HAND_HEIGHT
        var px = handX; var py = handY
        for (k in 1..WHIP_SEGMENTS) {
            val f   = k / WHIP_SEGMENTS.toFloat()
            val off = bend * sin(f * 3.14159f)
            val x   = handX + dx * length * f + nx * off
            val y   = handY + dy * length * f + ny * off
            val w   = WHIP_WIDTH_HAND + (WHIP_WIDTH_TIP - WHIP_WIDTH_HAND) * f
            drawLine(Color(0xFF0D2A57), Offset(px, py), Offset(x, y), strokeWidth = w + 3f)
            drawLine(Color(0xFF1E88E5), Offset(px, py), Offset(x, y), strokeWidth = w)
            if (w > 2.5f) drawLine(Color(0xFF90CAF9), Offset(px - 1f, py - 1f), Offset(x - 1f, y - 1f), strokeWidth = 1.2f)
            px = x; py = y
        }
        // RJ45 plug at the tip, turned the way the cable points
        val plugAngle = Math.toDegrees(kotlin.math.atan2(py - handY, px - handX).toDouble()).toFloat()
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.rotate(plugAngle, px, py)
        drawRect(Color(0xFF120A12), Offset(px - 2f, py - 6f), Size(13f, 12f))
        drawRect(Color(0xFFCFD8DC), Offset(px - 1f, py - 5f), Size(11f, 10f))
        drawRect(Color(0xFFFFC107), Offset(px + 6f, py - 3f), Size(3f, 6f))
        canvas.restore()
        // the crack: a flash while the cable is fully stretched
        if (t in WHIP_OUT_END..WHIP_HOLD_END) {
            drawCircle(Color(0xCCFFF59D), 9f, Offset(px + dx * 8f, py + dy * 8f))
            drawCircle(Color(0xFFFFFFFF), 4f, Offset(px + dx * 8f, py + dy * 8f))
        }
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
        const val GUARD_SPRITE_SCALE      = 3.3f      // 106 px tall: a bit taller than the player (90)
        const val FACING_ROWS             = 8
        const val GUARD_STEP_MS           = 130L
        const val GUARD_STROLL_MS         = 270L
        const val DOG_SPRITE_SCALE        = 2.9f      // times its width in world px: a dog about 85 px long
        const val DOG_TROT_MS             = 120L
        const val DOG_GALLOP_MS           = 85L
        const val DOG_BITE_SECONDS        = 0.4f
        const val LEASH_HAND_X            = 13f
        const val LEASH_HAND_Y            = 10f
        const val LEASH_COLLAR_UP         = 8f
        const val LEASH_SEGMENTS          = 8
        const val BOSS_SPRITE_SCALE       = 1.5f
        const val PICKUP_SPRITE_SCALE     = 1.5f
        const val PROJECTILE_SPRITE_SCALE = 1.9f
        const val MIN_PROJECTILE_SPRITE   = 22f
        const val ORBITAL_SPRITE_SCALE    = 3.4f

        const val PLAYER_FRAME_MS = 110L
        const val ENEMY_FRAME_MS  = 220L
        const val SPIN_PERIOD_MS  = 500L
        const val CHEST_DRAW_SIZE      = 48f
        const val CAN_SMALL_SCALE      = 2.0f
        const val CAN_LARGE_SCALE      = 2.6f
        const val CAN_GLINT_PERIOD_MS  = 1800L
        const val CAN_GLINT_STEP_MS    = 150L
        const val DRONE_LED_MS         = 300L
        const val DRONE_LIFT           = 0.45f      // how high above its shadow, in sprite heights
        const val DRONE_ROTOR_RADIUS   = 3.4f       // in sprite pixels
        const val DRONE_ROTOR_SPEED    = 0.024f     // radians per millisecond: about 3.8 turns a second, ~23 degrees per frame so the eye can follow it
        const val DRONE_TRAIL_STEPS    = 5          // wedges of smear behind each blade
        const val DRONE_TRAIL_STEP_DEG = 14f        // width of each wedge
        /** Centres of the four motor hubs in a drone frame, in sprite pixels (same as ROTOR_HUBS in gen_drone.py). */
        val DRONE_ROTOR_HUBS = arrayOf(floatArrayOf(4f, 4f), floatArrayOf(19f, 4f), floatArrayOf(4f, 11f), floatArrayOf(19f, 11f))
        const val CHEST_IDLE_FRAME_MS  = 260L
        const val WHIP_LASH_SECONDS = com.kotlinsurvivors.engine.ecs.EntityFactory.WHIP_LASH_SECONDS
        const val WHIP_SEGMENTS     = 14
        const val WHIP_WIDTH_HAND   = 6f
        const val WHIP_WIDTH_TIP    = 2f
        const val WHIP_BEND         = 46f
        const val WHIP_OUT_END      = 0.35f
        const val WHIP_HOLD_END     = 0.55f
        const val WHIP_HAND_HEIGHT  = 6f

        // Ground: each 32px tile is drawn 3x, clutter is placed on a coarse world grid.
        const val LEVEL_BACKDROP = 0xFF0A0A14.toInt()
        const val EXIT_ARROW_MARGIN = 56f
        const val EXIT_ARROW_SIZE   = 18f
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
