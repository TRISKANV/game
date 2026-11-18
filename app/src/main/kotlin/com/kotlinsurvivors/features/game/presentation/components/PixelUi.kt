package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Size of one image pixel on screen, in dp, when an image [imageWidth] pixels wide is
 * drawn close to [targetWidth]. It is always a whole number of screen pixels, so the
 * pixel art keeps hard, evenly sized pixels instead of blurring or shimmering.
 */
@Composable
fun pixelUnit(targetWidth: Dp, imageWidth: Int): Dp {
    val density = LocalDensity.current.density
    val scale   = floor(targetWidth.value * density / imageWidth + 0.01f).coerceAtLeast(1f)
    return (scale / density).dp
}

/** Draws pixel art close to [targetWidth] using [pixelUnit]. */
@Composable
fun PixelImage(
    image       : ImageBitmap,
    description : String?,
    targetWidth : Dp,
    modifier    : Modifier = Modifier
) {
    val unit = pixelUnit(targetWidth, image.width)
    Image(
        bitmap             = image,
        contentDescription = description,
        contentScale       = ContentScale.FillBounds,
        filterQuality      = FilterQuality.None,
        modifier           = modifier.size(unit * image.width, unit * image.height)
    )
}

/**
 * A button drawn from ui_btn_<art>.png, with ui_btn_<art>_down.png while it is pressed.
 * While the images load, or if they are missing, [fallback] is shown instead.
 */
@Composable
fun PixelButton(
    art         : String,
    description : String,
    targetWidth : Dp,
    onClick     : () -> Unit,
    fallback    : @Composable () -> Unit
) {
    val normal = rememberUiImage("ui_btn_$art.png")
    val down   = rememberUiImage("ui_btn_${art}_down.png")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val image = if (pressed) (down ?: normal) else normal

    if (image == null) {
        fallback()
        return
    }
    PixelImage(
        image       = image,
        description = description,
        targetWidth = targetWidth,
        modifier    = Modifier.clickable(
            interactionSource = source,
            indication        = null,
            role              = Role.Button,
            onClick           = onClick
        )
    )
}

/** Like [pixelUnit] for things that are not images: a whole number of screen pixels close to [approx]. */
@Composable
fun pixelUnitOf(approx: Dp): Dp {
    val density = LocalDensity.current.density
    val scale   = floor(approx.value * density + 0.01f).coerceAtLeast(1f)
    return (scale / density).dp
}

private val PixelInk = Color(0xFF06050A)

/**
 * Pixel-art panel behind any content: a dark outline, a [frame]-coloured border two
 * [unit]s thick with a lighter top line, and a [body] fill, all with cut corners.
 * Unlike an image it stretches to whatever size the content needs.
 */
fun Modifier.pixelFrame(unit: Dp, frame: Color, body: Color, light: Color): Modifier = drawBehind {
    val u = unit.toPx()

    fun layer(inset: Float, color: Color) {
        val w = size.width - 2f * inset
        val h = size.height - 2f * inset
        if (w <= 2f * u || h <= 2f * u) return
        drawRect(color, Offset(inset + u, inset), Size(w - 2f * u, h))
        drawRect(color, Offset(inset, inset + u), Size(w, h - 2f * u))
    }

    layer(0f, PixelInk)
    layer(u, frame)
    drawRect(light, Offset(2f * u, u), Size(size.width - 4f * u, u))
    layer(3f * u, body)
}
