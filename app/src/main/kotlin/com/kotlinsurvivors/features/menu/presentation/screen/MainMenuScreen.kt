package com.kotlinsurvivors.features.menu.presentation.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * MainMenuScreen
 *
 * Atmospheric main menu with animated particle background,
 * inspired by Vampire Survivors' dark fantasy aesthetic.
 */
@Composable
fun MainMenuScreen(
    onStartGame     : () -> Unit,
    onOpenShop      : () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenSettings  : () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "menuBg")
    val time by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label         = "time"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawMenuBackground(time) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier            = Modifier.padding(24.dp)
        ) {
            // ── Title ─────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text       = "KOTLIN",
                color      = Color(0xFF4FC3F7),
                fontSize   = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 10.sp
            )
            Text(
                text       = "SURVIVORS",
                color      = Color.White,
                fontSize   = 52.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp,
                lineHeight  = 54.sp
            )
            Text(
                text     = "Survive the endless night",
                color    = Color(0xFF888888),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(56.dp))

            // ── Buttons ───────────────────────────────────────────────────
            MenuButton("▶  START GAME",  Color(0xFF4FC3F7), onClick = onStartGame,    isPrimary = true)
            Spacer(Modifier.height(12.dp))
            MenuButton("🛒  SHOP",        Color(0xFFFFD740), onClick = onOpenShop)
            Spacer(Modifier.height(12.dp))
            MenuButton("🏆  ACHIEVEMENTS",Color(0xFF66BB6A), onClick = onOpenAchievements)
            Spacer(Modifier.height(12.dp))
            MenuButton("⚙  SETTINGS",    Color(0xFFAAAAAA), onClick = onOpenSettings)

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                "v1.0.0 · Open Source",
                color    = Color(0xFF444444),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MenuButton(
    text      : String,
    color     : Color,
    onClick   : () -> Unit,
    isPrimary : Boolean = false
) {
    Button(
        onClick  = onClick,
        modifier = Modifier
            .width(260.dp)
            .height(if (isPrimary) 58.dp else 50.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (isPrimary) color.copy(alpha = 0.2f) else Color.Transparent,
            contentColor   = color
        ),
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(
            width = if (isPrimary) 2.dp else 1.dp,
            color = color.copy(alpha = if (isPrimary) 0.7f else 0.35f)
        )
    ) {
        Text(
            text       = text,
            fontSize   = if (isPrimary) 16.sp else 14.sp,
            fontWeight = if (isPrimary) FontWeight.ExtraBold else FontWeight.SemiBold,
            letterSpacing = if (isPrimary) 2.sp else 0.sp
        )
    }
}

private fun DrawScope.drawMenuBackground(time: Float) {
    // Dark background
    drawRect(color = Color(0xFF080812), size = size)

    // Animated grid
    val gridColor = Color(0xFF0F0F1F)
    val gridSize  = 80f
    var x = 0f
    while (x < size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += gridSize
    }
    var y = 0f
    while (y < size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += gridSize
    }

    // Floating orbs
    val orbCount = 20
    for (i in 0 until orbCount) {
        val t    = time + i * 0.314f
        val rx   = size.width  * 0.5f + cos(t * 0.7f + i) * (size.width  * 0.45f)
        val ry   = size.height * 0.5f + sin(t * 0.5f + i * 1.3f) * (size.height * 0.4f)
        val rr   = 2f + sin(t + i * 0.5f).toFloat() * 1.5f
        val alpha= (0.1f + 0.06f * sin(t + i).toFloat()).coerceIn(0f, 1f)
        val hue  = (i * 37f) % 360f
        val col  = when ((i % 3)) {
            0 -> Color(0xFF4FC3F7).copy(alpha = alpha)
            1 -> Color(0xFFCE93D8).copy(alpha = alpha)
            else -> Color(0xFFFFD740).copy(alpha = alpha)
        }
        drawCircle(col, rr, Offset(rx, ry))
    }

    // Bottom gradient fade
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, Color(0x88000000)),
            startY = size.height * 0.5f,
            endY   = size.height
        ),
        size = size
    )
}
