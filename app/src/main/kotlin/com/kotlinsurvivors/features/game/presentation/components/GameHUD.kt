package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.formatClock
import kotlin.math.roundToInt

/**
 * GameHUD
 *
 * In-game heads-up display showing:
 *  - HP bar (top-left)
 *  - XP bar (below HP)
 *  - Level, time, kill count (top-center)
 *  - Coins (top-right)
 *  - Pause button (top-right)
 */
/**
 * Takes plain values instead of the whole GameState so Compose can skip it
 * on every frame where none of them changed (the state is republished
 * 60 times per second, but the numbers shown here change far less often).
 * [onPause] must be a stable reference, e.g. created with remember.
 */
@Composable
fun GameHUD(
    hp             : Int,
    maxHp          : Int,
    xp             : Int,
    xpToNext       : Int,
    level          : Int,
    kills          : Int,
    enemies        : Int,
    coins          : Int,
    elapsedSeconds : Int,
    onPause        : () -> Unit
) {
    val pauseIcon = rememberUiImage("ui_icon_pause.png")
    val coinIcon  = rememberUiImage("ui_icon_money.png")

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Left side: HP + XP ────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .width(220.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // HP Bar
            StatBar(
                art       = "hp",
                label     = "HP",
                current   = hp,
                max       = maxHp,
                percent   = if (maxHp > 0) hp.toFloat() / maxHp else 0f,
                fillColor = Color(0xFFEF5350),
                bgColor   = Color(0xFF330000)
            )

            // XP Bar
            StatBar(
                art       = "xp",
                label     = "XP",
                current   = xp,
                max       = xpToNext,
                percent   = if (xpToNext > 0) xp.toFloat() / xpToNext else 0f,
                fillColor = Color(0xFF66BB6A),
                bgColor   = Color(0xFF003300)
            )
        }

        // ── Center: Level + Time ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Timer
            Text(
                text      = remember(elapsedSeconds) { formatClock(elapsedSeconds) },
                color     = Color(0xFFE0E0E0),
                fontSize  = 22.sp,
                fontWeight= FontWeight.Bold,
                modifier  = Modifier
                    .background(Color(0xCC0A0A14), RoundedCornerShape(3.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HUDChip(label = remember(level)   { "Lv.$level" }, color = Color(0xFFCE93D8))
                HUDChip(label = remember(kills)   { "☠ $kills" },  color = Color(0xFFEF9A9A))
                HUDChip(label = remember(enemies) { "👾 $enemies" }, color = Color(0xFFFFCC80))
            }
        }

        // ── Right side: Coins + Pause ─────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick  = onPause,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xCC1A1A2E), RoundedCornerShape(3.dp))
            ) {
                if (pauseIcon != null) {
                    Image(
                        bitmap             = pauseIcon,
                        contentDescription = "Pause",
                        modifier           = Modifier.size(20.dp),
                        colorFilter        = ColorFilter.tint(Color(0xFFE0E0E0), BlendMode.Modulate)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = Color(0xFFE0E0E0)
                    )
                }
            }

            HUDChip(label = remember(coins) { "$coins" }, color = Color(0xFFFFD740), icon = coinIcon)
        }
    }
}

/** Small banner shown for a few seconds when an achievement is unlocked. */
@Composable
fun AchievementBanner(achievement: Achievement, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xE61A1A2E), RoundedCornerShape(3.dp))
            .border(1.dp, Color(0xFFFFD740), RoundedCornerShape(3.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(achievement.icon, fontSize = 22.sp)
        Column {
            Text(
                "Achievement unlocked",
                color      = Color(0xFFFFD740),
                fontSize   = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                achievement.name,
                color      = Color(0xFFE0E0E0),
                fontSize   = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * A pixel-art bar: ui_bar_<art>.png is the frame with its icon and empty track,
 * ui_bar_<art>_fill.png is cropped to the current value and drawn inside the track.
 * Falls back to the plain [HUDBar] while the images load or if they are missing.
 */
@Composable
private fun StatBar(
    art: String,
    label: String,
    current: Int,
    max: Int,
    percent: Float,
    fillColor: Color,
    bgColor: Color
) {
    val frame = rememberUiImage("ui_bar_$art.png")
    val fill  = rememberUiImage("ui_bar_${art}_fill.png")
    if (frame == null || fill == null) {
        HUDBar(label, current, max, percent, fillColor, bgColor)
        return
    }

    val animatedPercent by animateFloatAsState(
        targetValue   = percent.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150),
        label         = "barAnim"
    )
    val unit = pixelUnit(BAR_WIDTH, frame.width)

    Box(
        modifier         = Modifier.size(unit * frame.width, unit * frame.height),
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val k = size.width / frame.width
            drawImage(
                image         = frame,
                dstSize       = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None
            )
            val filled = (fill.width * animatedPercent).roundToInt().coerceIn(0, fill.width)
            if (filled > 0) {
                drawImage(
                    image         = fill,
                    srcSize       = IntSize(filled, fill.height),
                    dstOffset     = IntOffset((BAR_TRACK_X * k).roundToInt(), (BAR_TRACK_Y * k).roundToInt()),
                    dstSize       = IntSize((filled * k).roundToInt(), (fill.height * k).roundToInt()),
                    filterQuality = FilterQuality.None
                )
            }
        }
        Text(
            text     = remember(current, max) { "$current / $max" },
            color    = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style    = TextStyle(shadow = Shadow(Color.Black, Offset(1f, 1f), 0f)),
            modifier = Modifier.padding(end = unit * 3)
        )
    }
}

// Where the track starts inside ui_bar_*.png, in image pixels (see gen_menu_ui.py).
private const val BAR_TRACK_X = 12
private const val BAR_TRACK_Y = 2
private val BAR_WIDTH = 220.dp

@Composable
private fun HUDBar(
    label: String,
    current: Int,
    max: Int,
    percent: Float,
    fillColor: Color,
    bgColor: Color
) {
    val animatedPercent by animateFloatAsState(
        targetValue    = percent.coerceIn(0f, 1f),
        animationSpec  = tween(durationMillis = 150),
        label          = "barAnim"
    )

    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, color = Color(0xFFBBBBBB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(remember(current, max) { "$current / $max" }, color = Color(0xFFBBBBBB), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(bgColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedPercent)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(fillColor.copy(alpha = 0.8f), fillColor))
                    )
            )
        }
    }
}

@Composable
private fun HUDChip(label: String, color: Color, icon: ImageBitmap? = null) {
    Row(
        modifier = Modifier
            .background(Color(0xCC0A0A14), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Image(
                bitmap             = icon,
                contentDescription = null,
                modifier           = Modifier.size(14.dp),
                colorFilter        = ColorFilter.tint(color, BlendMode.Modulate)
            )
        }
        Text(
            text       = label,
            color      = color,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
