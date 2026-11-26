package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ToastInk  = Color(0xFF06050A)
private val ToastDeep = Color(0xFF0A0A14)

/**
 * The game's notification: a dark pixel-art panel with a coloured frame, an icon in its own slot on the
 * left, the title in the accent colour and a line of white text under it. Used for warnings, missions,
 * events and unlocked achievements, so they all read as one family.
 *
 * It slides in from the left edge and, when it has a [lifetimeMs], slides back out just before that time
 * is over while a thin bar along the bottom runs out. Without a lifetime (the current objective) it stays;
 * with [animate] off it is simply there from the first frame.
 */
@Composable
fun PixelToast(
    icon       : String,
    title      : String,
    subtitle   : String,
    accent     : Color,
    modifier   : Modifier = Modifier,
    lifetimeMs : Long?    = null,
    animate    : Boolean  = true,
    maxWidth   : androidx.compose.ui.unit.Dp = 236.dp
) {
    val unit  = pixelUnitOf(2.dp)
    val frame = lerp(accent, Color.Black, 0.35f)
    val light = lerp(accent, Color.White, 0.45f)
    val body  = lerp(accent, ToastDeep, 0.92f)

    // 0 = off the screen edge, 1 = in place
    val slide = remember { Animatable(if (animate) 0f else 1f) }
    val drain = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (animate) slide.animateTo(1f, tween(TOAST_SLIDE_MS, easing = FastOutSlowInEasing))
        if (lifetimeMs != null) {
            val rest = (lifetimeMs - 2L * TOAST_SLIDE_MS).coerceAtLeast(0L)
            launch { drain.animateTo(0f, tween(rest.toInt(), easing = LinearEasing)) }
            delay(rest)
            slide.animateTo(0f, tween(TOAST_SLIDE_MS, easing = FastOutSlowInEasing))
        }
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha        = slide.value
                translationX = -(1f - slide.value) * 48.dp.toPx()
            }
            .widthIn(max = maxWidth)
            .pixelFrame(unit, frame, body, light)
            .padding(horizontal = unit * 4, vertical = unit * 3)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(unit * 4)
        ) {
            // the icon sits in a darker slot, like an item frame
            Box(
                modifier = Modifier
                    .drawBehind {
                        val u = unit.toPx()
                        drawRect(ToastInk, Offset.Zero, Size(size.width, size.height))
                        drawRect(lerp(accent, ToastDeep, 0.75f), Offset(u, u), Size(size.width - 2f * u, size.height - 2f * u))
                    }
                    .padding(unit * 2),
                contentAlignment = Alignment.Center
            ) { PixelIcon(icon, 22.dp) }

            Column(verticalArrangement = Arrangement.spacedBy(unit)) {
                Text(
                    text     = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style    = toastStyle(accent, 12.sp, FontWeight.Bold)
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text     = subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style    = toastStyle(Color(0xFFF4F0E6), 10.sp, FontWeight.Normal)
                    )
                }
            }
        }
        if (lifetimeMs != null) {
            Box(
                modifier = Modifier
                    .padding(top = unit * 2)
                    .fillMaxWidth()
                    .height(unit)
                    .drawBehind {
                        drawRect(ToastInk)
                        drawRect(accent, Offset.Zero, Size(size.width * drain.value, size.height))
                    }
            )
        }
    }
}

private const val TOAST_SLIDE_MS = 260

/** Monospace with a one-pixel hard shadow: the closest thing to pixel type without a font file. */
private fun toastStyle(color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) = TextStyle(
    color      = color,
    fontSize   = size,
    fontWeight = weight,
    fontFamily = FontFamily.Monospace,
    shadow     = Shadow(ToastInk, Offset(2f, 2f), 0f)
)
