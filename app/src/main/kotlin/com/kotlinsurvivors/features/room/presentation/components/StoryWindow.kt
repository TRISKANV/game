package com.kotlinsurvivors.features.room.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.features.game.presentation.components.pixelFrame
import com.kotlinsurvivors.features.game.presentation.components.pixelUnitOf

/** Colours of the story panels: a dark body and one accent per speaker, like the game's own panels. */
object StoryStyle {
    val Body        = Color(0xFF130F1F)
    val Gold        = Color(0xFFD9A93A)
    val GoldLight   = Color(0xFFFFE08A)
    val Purple      = Color(0xFF8A52E0)
    val PurpleLight = Color(0xFFD2A8FF)
    val Text        = Color(0xFFF2EEFF)
    val Hint        = Color(0xFF8A84A8)
}

/**
 * A dialogue panel in the game's pixel frame: stepped corners, a lit top edge and a dark body.
 * The speaker's name sits on top in the speaker's colour.
 */
@Composable
fun StoryWindow(
    name: String,
    accent: Color,
    light: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val unit = pixelUnitOf(2.dp)
    Column(
        modifier = modifier
            .pixelFrame(unit, accent, StoryStyle.Body, light)
            .padding(horizontal = unit * 7, vertical = unit * 4)
    ) {
        Text(
            text = name, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

/** A short remark, in the same frame but without a name. */
@Composable
fun StoryRemark(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val unit = pixelUnitOf(2.dp)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .pixelFrame(unit, StoryStyle.Gold, StoryStyle.Body, StoryStyle.GoldLight)
            .padding(horizontal = unit * 7, vertical = unit * 4)
    ) { content() }
}

/** DEAUTH's portrait: pixel art with a transparent background, drawn straight onto the panel. */
@Composable
fun StoryPortrait(image: ImageBitmap?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (image != null) drawImage(
            image = image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None
        )
    }
}
