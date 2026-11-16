package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.features.game.domain.model.formatClock

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
            HUDBar(
                label     = "HP",
                current   = hp,
                max       = maxHp,
                percent   = if (maxHp > 0) hp.toFloat() / maxHp else 0f,
                fillColor = Color(0xFFEF5350),
                bgColor   = Color(0xFF330000)
            )

            // XP Bar
            HUDBar(
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
                    .background(Color(0xCC0A0A14), RoundedCornerShape(8.dp))
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
                    .background(Color(0xCC1A1A2E), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Pause",
                    tint = Color(0xFFE0E0E0)
                )
            }

            HUDChip(label = remember(coins) { "💰 $coins" }, color = Color(0xFFFFD740))
        }
    }
}

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
                .clip(RoundedCornerShape(6.dp))
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
private fun HUDChip(label: String, color: Color) {
    Text(
        text     = label,
        color    = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Color(0xCC0A0A14), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}
