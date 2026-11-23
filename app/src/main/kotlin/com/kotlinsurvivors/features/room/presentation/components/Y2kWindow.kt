package com.kotlinsurvivors.features.room.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the old-computer look: flat lime and cream with thick black outlines. */
object Y2k {
    val Ink   = Color(0xFF0A0A0A)
    val Lime  = Color(0xFFC6F65A)
    val Cream = Color(0xFFF1EBD6)
}

/**
 * A window in the style of old operating systems: thick black outline, a hard black drop
 * shadow, a title bar with the three little window buttons and a flat body colour.
 */
@Composable
fun Y2kWindow(
    title: String,
    modifier: Modifier = Modifier,
    body: Color = Y2k.Lime,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .drawBehind {
                val d = 4.dp.toPx()
                drawRoundRect(Y2k.Ink, Offset(d, d), size, CornerRadius(6.dp.toPx()))
            }
            .clip(shape)
            .background(body)
            .border(3.dp, Y2k.Ink, shape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(22.dp).background(Y2k.Ink).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, color = Y2k.Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            for (glyph in listOf("-", "o", "x")) {
                Box(
                    modifier = Modifier.padding(start = 4.dp).size(13.dp).border(1.5.dp, Y2k.Lime),
                    contentAlignment = Alignment.Center
                ) { Text(glyph, color = Y2k.Lime, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            }
        }
        content()
    }
}

/** A plain speech panel (no title bar): outline, hard shadow and flat fill. */
@Composable
fun Y2kBubble(modifier: Modifier = Modifier, body: Color = Y2k.Cream, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .drawBehind {
                val d = 4.dp.toPx()
                drawRoundRect(Y2k.Ink, Offset(d, d), size, CornerRadius(8.dp.toPx()))
            }
            .clip(shape)
            .background(body)
            .border(3.dp, Y2k.Ink, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { content() }
}

/** A portrait in a thick black frame; the image is drawn as it was supplied, only scaled. */
@Composable
fun Y2kPortrait(image: ImageBitmap?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.border(3.dp, Y2k.Ink).background(Y2k.Ink)) {
        if (image != null) drawImage(
            image = image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low
        )
    }
}
