package com.kotlinsurvivors.features.game.presentation.components

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.detectTapGestures
import com.kotlinsurvivors.R
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.presentation.language.weaponName
import com.kotlinsurvivors.engine.ChestReward
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Cells of chest_big.png (see gen_chest_big.py)
private const val CHEST_W = 48
private const val CHEST_H = 44
private const val FRAME_CLOSED = 0
private const val FRAME_GLINT  = 1
private const val FRAME_CRACK  = 2
private const val FRAME_HALF   = 3
private const val FRAME_WIDE   = 4
private const val FRAME_BRIGHT = 5

// Timeline of the opening, in milliseconds since the player tapped the chest
private const val T_CRACK  = 450L     // the lid lifts a crack and light leaks out
private const val T_POP    = 800L     // it flies open: flash, burst of light and sparks
private const val T_REVEAL = 1500L    // the weapon rises out of the chest
private const val CONTINUE_AFTER = 2200L   // after this long in the reveal the player may go on

private val Gold    = Color(0xFFFFC53D)
private val GoldHi  = Color(0xFFFFF0B3)
private val CyanHi  = Color(0xFF7FF4FF)
private val Scrim   = Color(0xF2060A10)

/**
 * The screen of a boss chest. The game is frozen behind it. The chest waits until the player taps
 * it; it then shakes, cracks, bursts open in a flash of light and sparks, and the weapon it holds
 * rises out of it surrounded by turning rays. A tap after that closes the screen and the weapon is
 * handed over ([onFinished] is called exactly once).
 */
@Composable
fun ChestOverlay(reward: ChestReward, onFinished: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val chestSheet by produceState<ImageBitmap?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try { SpriteAtlas.bitmap(context, "chest_big.png").asImageBitmap() } catch (e: Exception) { null }
        }
    }
    val iconSheet = rememberIconSheet()
    val sounds = LocalSoundEffects.current

    // Time in milliseconds since the screen appeared, advanced every frame.
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (start < 0L) start = t
                now = (t - start) / 1_000_000L
            }
        }
    }

    var openedAt by remember { mutableStateOf(-1L) }
    var finished by remember { mutableStateOf(false) }
    val sinceOpen = if (openedAt < 0L) -1L else now - openedAt
    val revealing = sinceOpen >= T_REVEAL

    LaunchedEffect(revealing) {
        if (revealing) sounds.play(Sound.ROULETTE_WIN)
    }

    val title = stringResource(
        when (reward.kind) {
            ChestReward.Kind.NEW_WEAPON -> R.string.chest_reveal_new
            ChestReward.Kind.LEVEL_UP   -> R.string.chest_reveal_level
            ChestReward.Kind.HEAL       -> R.string.chest_reveal_heal
        }
    )
    val name = when (reward.kind) {
        ChestReward.Kind.HEAL -> stringResource(R.string.chest_heal_name)
        else -> reward.weapon?.let { weaponName(it, it.name) } ?: ""
    }
    val levelText = if (reward.kind == ChestReward.Kind.LEVEL_UP) stringResource(R.string.chest_level_fmt, reward.level) else ""
    val tapToOpen = stringResource(R.string.chest_tap_open)
    val tapToGo   = stringResource(R.string.chest_tap_continue)
    val bossText  = stringResource(R.string.chest_boss_title)
    val iconKey = when (reward.kind) {
        ChestReward.Kind.HEAL -> "heart"
        else -> chestWeaponIcon(reward.weapon)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    when {
                        openedAt < 0L -> {
                            // The chest has to be touched, not just the screen: a generous box around it.
                            if (chestBounds(size.width.toFloat(), size.height.toFloat()).inflate(size.height * 0.08f).contains(tap)) {
                                openedAt = now
                                sounds.play(Sound.CHEST_OPEN)
                            }
                        }
                        now - openedAt >= T_REVEAL + CONTINUE_AFTER && !finished -> {
                            finished = true
                            sounds.play(Sound.UI_CLICK)
                            onFinished()
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawScene(
                now = now, sinceOpen = sinceOpen, chestSheet = chestSheet, iconSheet = iconSheet,
                iconKey = iconKey, heal = reward.kind == ChestReward.Kind.HEAL,
                title = title, name = name, levelText = levelText,
                tapToOpen = tapToOpen, tapToGo = tapToGo, bossText = bossText
            )
        }
    }
}

private fun chestWeaponIcon(weapon: WeaponType?): String = when (weapon) {
    WeaponType.DDOS     -> "w_ddos"
    WeaponType.ZERO_DAY -> "w_zeroday"
    else                -> "w_logicbomb"
}

/** Where the chest sits while it waits: the middle of the screen, a little low. */
private fun chestBounds(width: Float, height: Float): Rect {
    val unit = chestUnit(height)
    val w = CHEST_W * unit; val h = CHEST_H * unit
    return Rect(width / 2f - w / 2f, height * 0.56f - h / 2f, width / 2f + w / 2f, height * 0.56f + h / 2f)
}

/** Screen pixels per pixel of the chest drawing: a whole number, so the pixel art stays sharp. */
private fun chestUnit(height: Float): Float = max(3f, floor(height * 0.50f / CHEST_H))

// ── Scene ───────────────────────────────────────────────────────────────────

private fun DrawScope.drawScene(
    now: Long, sinceOpen: Long, chestSheet: ImageBitmap?, iconSheet: ImageBitmap?,
    iconKey: String, heal: Boolean, title: String, name: String, levelText: String,
    tapToOpen: String, tapToGo: String, bossText: String
) {
    val w = size.width; val h = size.height
    val cx = w / 2f
    val opened = sinceOpen >= 0L
    val reveal = (sinceOpen - T_REVEAL).coerceAtLeast(-1L)           // ms into the reveal, -1 before it
    val accent = if (heal) Color(0xFFFF6B8A) else Gold

    // Dark background with a vignette; it breathes a little with the light in the chest.
    drawRect(Scrim)
    val breathe = 0.5f + 0.5f * sin(now * 0.003f)
    val lightLevel = when {
        !opened -> 0.35f + 0.15f * breathe
        sinceOpen < T_POP -> 0.35f + 0.65f * (sinceOpen / T_POP.toFloat())
        else -> 1f
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = 0.30f * lightLevel), Color.Transparent),
            center = Offset(cx, h * 0.56f), radius = h * (0.55f + 0.25f * lightLevel)
        ),
        radius = h * 0.9f, center = Offset(cx, h * 0.56f)
    )

    // Where the chest is and how big. After the opening it shrinks and drops to make room for the weapon.
    val shrink = if (reveal < 0L) 0f else easeOutCubic((reveal / 700f).coerceIn(0f, 1f))
    val baseUnit = chestUnit(h)
    val unit = max(2f, floor(baseUnit * (1f - 0.42f * shrink)))
    val centerY = h * (0.56f + 0.25f * shrink)
    var shakeX = 0f; var shakeY = 0f
    if (opened && sinceOpen < T_POP + 450L) {
        val amp = if (sinceOpen < T_POP) baseUnit * (0.3f + 1.7f * (sinceOpen / T_POP.toFloat()))
                  else baseUnit * 3f * (1f - (sinceOpen - T_POP) / 450f)
        shakeX = sin(sinceOpen * 0.09f) * amp
        shakeY = cos(sinceOpen * 0.13f) * amp * 0.6f
    }
    val bob = if (!opened) sin(now * 0.004f) * baseUnit * 0.5f else 0f
    val chestTop = centerY - CHEST_H * unit / 2f + bob + shakeY
    val chestLeft = cx - CHEST_W * unit / 2f + shakeX
    val mouth = Offset(cx + shakeX, chestTop + 20f * unit)          // the seam between lid and box

    // Rays and light from the chest, behind it.
    if (opened && sinceOpen >= T_CRACK) {
        val leak = ((sinceOpen - T_CRACK) / (T_POP - T_CRACK).toFloat()).coerceIn(0f, 1f)
        drawRays(mouth, count = 9, length = h * (0.35f + 0.5f * leak), alpha = 0.25f * leak, spin = now * 0.0002f, color = GoldHi)
    }
    if (opened && sinceOpen >= T_POP) {
        val t = (sinceOpen - T_POP) / 1000f
        val burst = (1f - t / 0.9f).coerceIn(0f, 1f)
        drawRays(mouth, count = 16, length = w, alpha = 0.55f * burst, spin = 0f, color = Color.White)
    }
    if (reveal >= 0L) {
        val grow = (reveal / 900f).coerceIn(0f, 1f)
        val hover = Offset(cx, h * 0.38f)
        drawRays(hover, count = 12, length = h * 1.2f * grow, alpha = 0.45f * grow, spin = now * 0.00035f, color = accent)
        drawRays(hover, count = 7, length = h * 0.8f * grow, alpha = 0.35f * grow, spin = -now * 0.0005f, color = CyanHi)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.55f * grow), accent.copy(alpha = 0.35f * grow), Color.Transparent),
                center = hover, radius = h * 0.34f * (0.9f + 0.1f * sin(now * 0.006f))
            ),
            radius = h * 0.4f, center = hover
        )
    }

    // The chest itself.
    if (chestSheet != null) {
        val frame = when {
            !opened -> if (now % 2600L < 260L) FRAME_GLINT else FRAME_CLOSED
            sinceOpen < T_CRACK -> FRAME_CLOSED
            sinceOpen < T_POP -> FRAME_CRACK
            sinceOpen < T_POP + 250L -> FRAME_HALF
            sinceOpen < T_POP + 500L -> FRAME_WIDE
            else -> FRAME_BRIGHT
        }
        drawImage(
            image = chestSheet,
            srcOffset = IntOffset(frame * CHEST_W, 0), srcSize = IntSize(CHEST_W, CHEST_H),
            dstOffset = IntOffset(chestLeft.roundToInt(), chestTop.roundToInt()),
            dstSize = IntSize((CHEST_W * unit).roundToInt(), (CHEST_H * unit).roundToInt()),
            filterQuality = FilterQuality.None
        )
    } else {
        drawRect(Color(0xFF33444E), Offset(chestLeft, chestTop), Size(CHEST_W * unit, CHEST_H * unit))
    }

    // Sparks: floating around the closed chest, bursting out of the open one.
    if (!opened) {
        for (i in 0 until 14) {
            val seed = hash(i)
            val phase = ((now / 2600f) + seed) % 1f
            val x = cx + (hash(i + 40) - 0.5f) * CHEST_W * baseUnit * 1.2f
            val y = chestTop + CHEST_H * baseUnit * 0.9f - phase * CHEST_H * baseUnit * 1.4f
            val a = sin(phase * PI.toFloat())
            drawRect(CyanHi.copy(alpha = 0.8f * a), Offset(x, y), Size(baseUnit * 0.8f, baseUnit * 0.8f))
        }
    } else if (sinceOpen >= T_POP) {
        drawSparks(mouth, (sinceOpen - T_POP) / 1000f, h)
    }

    // The weapon rises out of the chest, overshoots and settles, hovering.
    if (reveal >= 0L && iconSheet != null) {
        val index = UI_ICON_KEYS.indexOf(iconKey)
        if (index >= 0) {
            val rise = (reveal / 1000f).coerceIn(0f, 1f)
            val eased = easeOutBack(rise)
            val from = mouth.y
            val to = h * 0.38f + sin(now * 0.004f) * h * 0.012f * rise
            val y = from + (to - from) * eased
            val iconUnit = max(4f, floor(h * 0.30f / UI_ICON_CELL))
            val scale = (0.25f + 0.75f * eased) * (1f + 0.05f * sin(now * 0.008f) * rise)
            val side = UI_ICON_CELL * iconUnit * scale
            rotate(degrees = sin(now * 0.003f) * 5f * rise, pivot = Offset(cx, y)) {
                // a dark rim first, so the icon stands out of the light
                drawImage(
                    image = iconSheet,
                    srcOffset = IntOffset(index * UI_ICON_CELL, 0), srcSize = IntSize(UI_ICON_CELL, UI_ICON_CELL),
                    dstOffset = IntOffset((cx - side / 2f).roundToInt(), (y - side / 2f).roundToInt()),
                    dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                    filterQuality = FilterQuality.None
                )
            }
            // sparkles orbiting it
            for (i in 0 until 8) {
                val a = now * 0.0016f * (if (i % 2 == 0) 1f else -1f) + i * (2f * PI.toFloat() / 8f)
                val r = side * (0.62f + 0.08f * sin(now * 0.005f + i))
                val s = iconUnit * (0.8f + 0.5f * hash(i + 90))
                drawRect(GoldHi.copy(alpha = rise), Offset(cx + cos(a) * r, y + sin(a) * r * 0.8f), Size(s, s))
            }
        }
    }

    // Texts
    val text = drawContext.canvas.nativeCanvas
    val density = h / 360f
    if (!opened) {
        drawLabel(text, bossText, cx, h * 0.14f, 22f * density, accent, 1f, now)
        val pulse = 0.55f + 0.45f * sin(now * 0.006f)
        drawLabel(text, tapToOpen, cx, h * 0.93f, 15f * density, Color.White, pulse, now, shimmer = false)
    } else if (reveal >= 0L) {
        val t = reveal
        if (t > 250L) drawLabel(text, title, cx, h * 0.12f, 26f * density, accent, ((t - 250L) / 300f).coerceIn(0f, 1f), now)
        if (t > 600L && name.isNotEmpty()) {
            val shown = ((t - 600L) / 45L).toInt().coerceIn(0, name.length)
            drawLabel(text, name.substring(0, shown), cx, h * 0.585f, 24f * density, Color.White, 1f, now, shimmer = false)
        }
        if (t > 900L && levelText.isNotEmpty()) {
            drawLabel(text, levelText, cx, h * 0.585f + 22f * density, 14f * density, CyanHi, ((t - 900L) / 300f).coerceIn(0f, 1f), now, shimmer = false)
        }
        if (t >= CONTINUE_AFTER) {
            val pulse = 0.55f + 0.45f * sin(now * 0.006f)
            drawLabel(text, tapToGo, cx, h * 0.95f, 14f * density, Color.White, pulse, now, shimmer = false)
        }
    }

    // The flash of the lid bursting open, and a little white of the light that stays.
    if (opened && sinceOpen >= T_POP) {
        val f = 1f - ((sinceOpen - T_POP) / 450f).coerceIn(0f, 1f)
        if (f > 0f) drawRect(Color.White.copy(alpha = 0.85f * f * f))
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

/** Wedges of light spreading from [origin], fading with distance. */
private fun DrawScope.drawRays(origin: Offset, count: Int, length: Float, alpha: Float, spin: Float, color: Color) {
    if (alpha <= 0.01f || length <= 1f) return
    val brush = Brush.radialGradient(
        listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = origin, radius = length
    )
    val step = 2f * PI.toFloat() / count
    val half = step * 0.17f
    val path = Path()
    for (i in 0 until count) {
        val a = i * step + spin
        path.reset()
        path.moveTo(origin.x, origin.y)
        path.lineTo(origin.x + cos(a - half) * length, origin.y + sin(a - half) * length)
        path.lineTo(origin.x + cos(a + half) * length, origin.y + sin(a + half) * length)
        path.close()
        drawPath(path, brush)
    }
}

/** A fountain of square sparks from [origin]; [t] is seconds since the burst. Nothing is stored: each spark is worked out from its number. */
private fun DrawScope.drawSparks(origin: Offset, t: Float, screenHeight: Float) {
    val palette = arrayOf(Gold, GoldHi, CyanHi, Color.White)
    for (i in 0 until 70) {
        val life = 0.9f + 1.0f * hash(i + 200)
        if (t > life) continue
        val angle = (-PI.toFloat() / 2f) + (hash(i) - 0.5f) * 2.6f          // mostly upwards
        val speed = screenHeight * (0.45f + 1.1f * hash(i + 100))
        val x = origin.x + cos(angle) * speed * t
        val y = origin.y + sin(angle) * speed * t + 0.5f * screenHeight * 1.6f * t * t
        val fade = (1f - t / life).coerceIn(0f, 1f)
        val s = screenHeight * (0.008f + 0.012f * hash(i + 300))
        drawRect(palette[i % palette.size].copy(alpha = fade), Offset(x, y), Size(s, s))
    }
}

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
}
private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
    style = Paint.Style.STROKE
    color = 0xFF05080C.toInt()
}

/** Text with a dark outline; [shimmer] runs a band of light across it. */
private fun drawLabel(
    canvas: android.graphics.Canvas, text: String, x: Float, y: Float, size: Float,
    color: Color, alpha: Float, now: Long, shimmer: Boolean = true
) {
    if (text.isEmpty() || alpha <= 0f) return
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    outlinePaint.textSize = size
    outlinePaint.strokeWidth = max(3f, size * 0.14f)
    outlinePaint.alpha = a
    canvas.drawText(text, x, y, outlinePaint)

    labelPaint.textSize = size
    val base = (color.toArgb() and 0x00FFFFFF) or (a shl 24)
    if (shimmer) {
        val width = labelPaint.measureText(text).coerceAtLeast(1f)
        val sweep = ((now % 2200L) / 2200f) * 2f - 0.5f
        val x0 = x - width / 2f + width * sweep
        val bright = android.graphics.Color.argb(a, 255, 255, 255)
        labelPaint.shader = LinearGradient(
            x0 - width * 0.25f, 0f, x0 + width * 0.25f, 0f,
            intArrayOf(base, bright, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        labelPaint.color = base
    } else {
        labelPaint.shader = null
        labelPaint.color = base
    }
    canvas.drawText(text, x, y, labelPaint)
    labelPaint.shader = null
}

/** A repeatable pseudo-random number in 0..1 for a given index: the same spark is always in the same place. */
private fun hash(i: Int): Float {
    var n = i * 374761393 + 668265263
    n = (n xor (n ushr 13)) * 1274126177
    n = n xor (n ushr 16)
    return (n and 0xFFFF) / 65535f
}

private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}
