package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.features.game.domain.model.CarPrompt

private val PanelColor = Color(0xE6141018)
private val Teal = Color(0xFF3FD0C8)
private val Amber = Color(0xFFFFC94A)
private val Red = Color(0xFFE5484D)

/**
 * The car button, bottom right. Next to a locked car it picks the lock for as long as it is held (a ring
 * fills up); next to an open car it gets in, and at the wheel it gets out. While driving, the car's health
 * shows above it.
 *
 * [onButton] gets true when the finger goes down and false when it comes up.
 */
@Composable
fun CarButton(
    prompt: CarPrompt,
    progress: Float,
    carHealth: Float,
    onButton: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (prompt == CarPrompt.NONE && carHealth < 0f) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (carHealth >= 0f) CarHealthBar(carHealth)
        if (prompt != CarPrompt.NONE) {
            val label = when (prompt) {
                CarPrompt.LOCKPICK -> stringResource(R.string.car_lockpick)
                CarPrompt.ENTER    -> stringResource(R.string.car_enter)
                CarPrompt.SEARCH   -> stringResource(R.string.search_button)
                else               -> stringResource(R.string.car_exit)
            }
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(PanelColor, RoundedCornerShape(14.dp))
                    .border(3.dp, if (prompt == CarPrompt.EXIT) Amber else Teal, RoundedCornerShape(14.dp))
                    .pointerInput(prompt) {
                        detectTapGestures(onPress = {
                            onButton(true)
                            // The finger can be lifted, or this button can disappear under it (the car goes out of
                            // reach), and either way the engine must hear that it is up; otherwise it would keep
                            // "holding" and pick the next lock all by itself.
                            try { tryAwaitRelease() } finally { onButton(false) }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                if ((prompt == CarPrompt.LOCKPICK || prompt == CarPrompt.SEARCH) && progress > 0f) {
                    Canvas(modifier = Modifier.size(78.dp)) {
                        drawArc(
                            color = Teal, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Butt),
                            topLeft = Offset.Zero, size = Size(size.width, size.height)
                        )
                    }
                }
                Text(
                    text = label,
                    color = Color(0xFFF4F0E6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CarHealthBar(health: Float) {
    val fraction = health.coerceIn(0f, 1f)
    val fill = when {
        fraction > 0.5f  -> Teal
        fraction > 0.25f -> Amber
        else             -> Red
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.car_health),
            color = Color(0xFFF4F0E6), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .width(120.dp).height(12.dp)
                .background(PanelColor, RoundedCornerShape(3.dp))
                .border(2.dp, Color(0xFF0A0910), RoundedCornerShape(3.dp))
        ) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(12.dp).background(fill, RoundedCornerShape(3.dp)))
        }
    }
}
