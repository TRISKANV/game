package com.kotlinsurvivors.features.room.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.features.game.presentation.components.pixelFrame
import com.kotlinsurvivors.features.game.presentation.components.pixelUnitOf
import com.kotlinsurvivors.features.room.domain.Countdown
import kotlin.math.sin

private val Blood      = Color(0xFFB02A2A)
private val BloodLight = Color(0xFFFF7A6A)
private val BloodBody  = Color(0xFF3A0C10)
private val Panel      = Color(0xFF1A0A0C)
private val Yellow     = Color(0xFFFFD36B)
private val Cream      = Color(0xFFF1E6D0)

/**
 * The infection: the screen shakes and turns red, error windows keep popping up and a bar fills
 * while the files are "encrypted". [progress] goes from 0 to 1, [time] drives the shaking.
 */
@Composable
fun StoryInfection(progress: Float, time: Float, modifier: Modifier = Modifier) {
    val unit = pixelUnitOf(2.dp)
    val shakeX = if (progress < 1f) sin(time * 61f) * 3f else 0f
    val shakeY = if (progress < 1f) sin(time * 47f + 1f) * 2f else 0f
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { translationX = shakeX; translationY = shakeY }
            .background(Color(0xFF4A0A0E).copy(alpha = 0.25f + 0.5f * progress))
    ) {
        // Scan lines, a few of them flickering.
        Canvas(Modifier.fillMaxSize()) {
            var y = 0f
            var row = 0
            while (y < size.height) {
                val a = if ((row + (time * 14f).toInt()) % 9 == 0) 0.32f else 0.12f
                drawRect(Color.Black.copy(alpha = a), Offset(0f, y), Size(size.width, 2f))
                y += 7f; row++
            }
        }

        // Error windows, one more every half second, in places that look random but are always the same.
        val windows = (progress * 11f).toInt() + 1
        for (i in 0 until windows) {
            val fx = ((i * 37 + 11) % 64) / 100f
            val fy = ((i * 53 + 7) % 58) / 100f
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * fx, y = maxHeight * fy)
                    .width(126.dp)
                    .pixelFrame(unit, Blood, BloodBody, BloodLight)
                    .padding(horizontal = unit * 5, vertical = unit * 3)
            ) {
                Text(
                    text = (if (i % 3 == 0) "!!  " else "X  ") + stringResource(R.string.story_infect_error),
                    color = if (i % 2 == 0) Yellow else Cream, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // The progress bar, in a panel at the bottom.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
                .fillMaxWidth(0.6f)
                .pixelFrame(unit, Blood, Panel, BloodLight)
                .padding(horizontal = unit * 7, vertical = unit * 4),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.story_infect_title) + "  " + (progress * 100).toInt() + "%",
                color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            PixelBar(progress, Modifier.fillMaxWidth().height(14.dp), Blood, Yellow)
        }
    }
}

/** A bar made of square blocks, like the game's own meters. */
@Composable
private fun PixelBar(fraction: Float, modifier: Modifier, track: Color, fill: Color) {
    Canvas(modifier) {
        val block = size.height
        val n = (size.width / (block * 1.25f)).toInt().coerceAtLeast(1)
        val step = size.width / n
        val lit = (fraction.coerceIn(0f, 1f) * n).toInt()
        for (i in 0 until n) {
            drawRect(
                if (i < lit) fill else track.copy(alpha = 0.35f),
                Offset(i * step, 0f), Size(step - 2f, size.height)
            )
        }
    }
}

/** A padlock in pixel art: red body, light shackle, a keyhole. */
@Composable
private fun Padlock(modifier: Modifier) {
    Canvas(modifier) {
        val px = size.width / 12f
        fun r(x: Int, y: Int, w: Int, h: Int, c: Color) = drawRect(c, Offset(x * px, y * px), Size(w * px, h * px))
        val ink = Color(0xFF120A12)
        r(2, 0, 8, 2, Cream); r(2, 0, 2, 7, Cream); r(8, 0, 2, 7, Cream)            // shackle
        r(0, 6, 12, 1, ink)
        r(0, 7, 12, 9, Blood); r(0, 7, 12, 1, BloodLight); r(0, 15, 12, 1, ink)      // body
        r(5, 9, 2, 2, ink); r(5, 11, 2, 3, ink)                                       // keyhole
    }
}

@Composable
private fun TimerBox(label: String, caption: String, remaining: Long, total: Long, modifier: Modifier = Modifier) {
    val unit = pixelUnitOf(2.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pixelFrame(unit, Blood, BloodBody, BloodLight)
            .padding(horizontal = unit * 5, vertical = unit * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        Text(caption, color = Cream, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(Countdown.format(remaining), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        PixelBar((remaining.toFloat() / total).coerceIn(0f, 1f), Modifier.fillMaxWidth().height(8.dp), Blood, Color(0xFF58D86A))
    }
}

/**
 * The ransom screen: a locked padlock, the two timers (which keep running, see
 * [Countdown]), the explanation and the payment buttons. Nothing here is real: the currency, the
 * address and the program are made up for the game. CLOSE goes back to the room.
 */
@Composable
fun StoryRansomScreen(
    paymentDoublesIn: Long,
    filesLostIn: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnClose by rememberUpdatedState(onClose)
    val unit = pixelUnitOf(2.dp)
    var note by remember { mutableStateOf("") }
    val noPayment = stringResource(R.string.story_ransom_no_payment)
    val needPayment = stringResource(R.string.story_ransom_need_payment)

    Box(modifier = modifier.fillMaxSize().background(Color(0xDD120406)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .fillMaxWidth(0.9f)
                .pixelFrame(unit, Blood, BloodBody, BloodLight)
                .padding(horizontal = unit * 6, vertical = unit * 4),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.story_ransom_title), color = BloodLight, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Box(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .pixelFrame(unit, Blood, Panel, BloodLight)
                        .pointerInput(Unit) { detectTapGestures { latestOnClose() } }
                        .padding(horizontal = unit * 6, vertical = unit * 2)
                ) { Text(stringResource(R.string.story_ransom_close), color = Cream, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            }
            Text(
                stringResource(R.string.story_ransom_header), color = Yellow, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(0.36f), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Padlock(Modifier.size(width = 54.dp, height = 72.dp))
                    TimerBox(stringResource(R.string.story_ransom_pay_label), stringResource(R.string.story_ransom_time_left), paymentDoublesIn, Countdown.PAYMENT_DOUBLES_SECONDS)
                    TimerBox(stringResource(R.string.story_ransom_lost_label), stringResource(R.string.story_ransom_time_left), filesLostIn, Countdown.FILES_LOST_SECONDS)
                }
                Column(modifier = Modifier.weight(0.64f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pixelFrame(unit, Blood, Panel, BloodLight)
                            .padding(horizontal = unit * 5, vertical = unit * 3)
                    ) {
                        Text(
                            stringResource(R.string.story_ransom_text), color = Cream, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    Text(stringResource(R.string.story_ransom_send), color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(R.string.story_ransom_address), color = Cream, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().pixelFrame(unit, Blood, Panel, BloodLight).padding(horizontal = unit * 5, vertical = unit * 2)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RansomButton(stringResource(R.string.story_ransom_check), Modifier.weight(1f)) { note = noPayment }
                        RansomButton(stringResource(R.string.story_ransom_decrypt), Modifier.weight(1f)) { note = needPayment }
                    }
                    if (note.isNotEmpty()) Text(note, color = BloodLight, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun RansomButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val latestOnClick by rememberUpdatedState(onClick)
    val unit = pixelUnitOf(2.dp)
    Box(
        modifier = modifier
            .pixelFrame(unit, Color(0xFFD8C8A8), Color(0xFF5A4A3A), Color(0xFFFFF3D8))
            .pointerInput(Unit) { detectTapGestures { latestOnClick() } }
            .padding(vertical = unit * 4),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
}
