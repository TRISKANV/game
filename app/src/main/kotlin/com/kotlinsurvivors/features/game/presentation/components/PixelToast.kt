package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ToastInk  = Color(0xFF06050A)
private val ToastDeep = Color(0xFF0A0A14)

/**
 * The game's notification: a dark pixel-art panel with a coloured frame, an icon in its own
 * slot on the left, the title in the accent colour and a line of white text under it.
 * Used for warnings, missions and unlocked achievements, so they all read as one family.
 *
 * Frame, highlight and body are all derived from [accent], which keeps every notification inside
 * the palette of the game. With [animate] the panel slides in from above and fades in; the
 * permanent ones (the current objective) turn it off.
 */
@Composable
fun PixelToast(
    icon     : String,
    title    : String,
    subtitle : String,
    accent   : Color,
    modifier : Modifier = Modifier,
    animate  : Boolean  = true,
    maxWidth : androidx.compose.ui.unit.Dp = 270.dp
) {
    val unit  = pixelUnitOf(2.dp)
    val frame = lerp(accent, Color.Black, 0.35f)
    val light = lerp(accent, Color.White, 0.45f)
    val body  = lerp(accent, ToastDeep, 0.9f)

    val entry = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) entry.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                alpha        = entry.value
                translationY = -(1f - entry.value) * 36.dp.toPx()
            }
            .widthIn(max = maxWidth)
            .pixelFrame(unit, frame, body, light)
            .padding(horizontal = unit * 5, vertical = unit * 3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(unit * 5)
    ) {
        // the icon sits in a darker slot, like an item frame
        Box(
            modifier = Modifier
                .drawBehind {
                    val u = unit.toPx()
                    drawRect(ToastInk, Offset.Zero, Size(size.width, size.height))
                    drawRect(lerp(accent, ToastDeep, 0.8f), Offset(u, u), Size(size.width - 2f * u, size.height - 2f * u))
                }
                .padding(unit * 3),
            contentAlignment = Alignment.Center
        ) { PixelIcon(icon, 20.dp) }

        Column(verticalArrangement = Arrangement.spacedBy(unit)) {
            Text(
                text  = title,
                style = toastStyle(accent, 12.sp, FontWeight.Bold)
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text  = subtitle,
                    style = toastStyle(Color(0xFFF4F0E6), 10.sp, FontWeight.Normal)
                )
            }
        }
    }
}

/** Monospace with a one-pixel hard shadow: the closest thing to pixel type without a font file. */
private fun toastStyle(color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) = TextStyle(
    color      = color,
    fontSize   = size,
    fontWeight = weight,
    fontFamily = FontFamily.Monospace,
    shadow     = Shadow(ToastInk, Offset(2f, 2f), 0f)
)
