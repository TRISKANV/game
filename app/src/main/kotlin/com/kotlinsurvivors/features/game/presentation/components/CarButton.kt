package com.kotlinsurvivors.features.game.presentation.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.engine.map.LevelCars
import com.kotlinsurvivors.features.game.domain.model.CarPrompt

private val PanelColor = Color(0xE6141018)
private val Teal = Color(0xFF3FD0C8)
private val Amber = Color(0xFFFFC94A)
private val Red = Color(0xFFE5484D)
private val Ink = Color(0xFF0A0910)

/**
 * The button of the car and of the searches, bottom right. It is only a picture, no frame: a magnifying
 * glass next to a place that can be searched, a lockpick next to a locked car (a ring fills up while the
 * lock is picked), and next to an open car the car itself with an arrow up (get in); at the wheel, the car
 * being driven with an arrow down (get out). Each car shows its own look. While driving, the health of the
 * car shows above it.
 *
 * [onButton] gets true when the finger goes down and false when it comes up.
 */
@Composable
fun CarButton(
    prompt: CarPrompt,
    progress: Float,
    carHealth: Float,
    carLook: Int,
    onButton: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (prompt == CarPrompt.NONE && carHealth < 0f) return
    val carSheet = rememberCarSheet()
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
                    .size(104.dp)
                    .pointerInput(prompt) {
                        detectTapGestures(onPress = {
                            onButton(true)
                            // The finger can be lifted, or this button can disappear under it (the car goes out of
                            // reach), and either way the engine must hear that it is up; otherwise it would keep
                            // "holding" and pick the next lock all by itself.
                            try { tryAwaitRelease() } finally { onButton(false) }
                        })
                    }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                if ((prompt == CarPrompt.LOCKPICK || prompt == CarPrompt.SEARCH) && progress > 0f) {
                    Canvas(modifier = Modifier.size(98.dp)) {
                        drawArc(
                            color = Ink, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false, style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Butt)
                        )
                        drawArc(
                            color = Teal, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                            useCenter = false, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Butt)
                        )
                    }
                }
                when (prompt) {
                    CarPrompt.SEARCH   -> PixelIcon(MAGNIFIER)
                    CarPrompt.LOCKPICK -> PixelIcon(LOCKPICK)
                    else -> if (carSheet != null && carLook >= 0 && (carLook + 1) * LevelCars.CELL_W <= carSheet.width) {
                        CarIcon(carSheet, carLook, goingIn = prompt != CarPrompt.EXIT)
                    } else {
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
    }
}

/** cars.png once, or null when the game has no car sprites (then the button falls back to its label). */
@Composable
private fun rememberCarSheet(): ImageBitmap? {
    val context = LocalContext.current
    return remember {
        try {
            val options = BitmapFactory.Options().apply { inScaled = false }
            context.assets.open("sprites/cars.png").use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
    }
}

// Pixel-art icons, one character per pixel: '.' empty, 'o' outline, 'g'/'G' glass, 'w' glint, 'h'/'H' wood,
// 's'/'S' steel, 'b'/'B' brass, 'y' bright rim.
private val MAGNIFIER = listOf(
    "......................",
    "....oooooooooo........",
    "...ooyyyyyyysoo.......",
    "..ooyyyyyyyyssoo......",
    ".ooyyywwGGGGsssoo.....",
    ".oyyywGgwggGGssso.....",
    ".oyywGggggggGGsso.....",
    ".oyywggggggggGsso.....",
    ".oyyGwgggggggGSSo.....",
    ".oyyGgggggggGGSSo.....",
    ".oyyGggggggGGGSSo.....",
    ".oysGGggggGGGGSSo.....",
    ".osssGGggGGGGSSSo.....",
    ".oosssGGGGGGSSSBoo....",
    "..oosssSSSSSSSbbBoo...",
    "...oossSSSSSSBBBBHoo..",
    "....ooooooooooBBBhHoo.",
    ".............ooHHhhHoo",
    "..............ooHHhhHo",
    "...............ooHHhHo",
    "................ooHHHo",
    ".................ooooo"
)

private val LOCKPICK = listOf(
    "................ooBBBo",
    "...............ooBbbBB",
    "..............ooBBbbBB",
    "............oooBBbBBBo",
    "............oBBBbBBBoo",
    "............oBbBBBBoo.",
    "...........ooBBBBBoo..",
    "..........oowBBBBoo...",
    ".........oowssoooo....",
    "..ooo...oowsooo.......",
    "..oso.ooowsoo.........",
    "..osooowssoo..........",
    "..osssssooo...oooo....",
    "..ossssoo.....oSSo....",
    "..oooooo......osSo....",
    "..............osSo....",
    "....ooooooooooosSo....",
    "...ooSSSSSSSSSSsSo....",
    "...oSSSSSSSSSSSSSo....",
    "...ooSSSSSSSSSSSoo....",
    "....ooooooooooooo.....",
    "......................"
)

private fun pixelColor(c: Char): Color? = when (c) {
    'o' -> Ink
    'g' -> Color(0xFF96E2F0)
    'G' -> Color(0xFF48A8C4)
    'w' -> Color(0xFFFAFAF5)
    'h' -> Color(0xFFA8703A)
    'H' -> Color(0xFF68421E)
    's' -> Color(0xFFC4CCD6)
    'S' -> Color(0xFF767E8C)
    'b' -> Color(0xFFECB840)
    'B' -> Color(0xFF9E6E1C)
    'y' -> Color(0xFFE8EEF6)
    else -> null
}

@Composable
private fun PixelIcon(rows: List<String>) {
    Canvas(modifier = Modifier.size(84.dp)) {
        val cell = size.width / rows.size
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val color = pixelColor(ch) ?: return@forEachIndexed
                drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

private val ARROW_UP = listOf(
    "....o....",
    "...ogo...",
    "..oggGo..",
    ".ogggGGo.",
    "oggggGGGo",
    "ooogGGooo",
    "..ogGGo..",
    "..ogGGo..",
    "..ooooo.."
)
private val ARROW_DOWN = ARROW_UP.reversed()

/** The car with an arrow: above it pointing up to get in, below it pointing down to get out. */
@Composable
private fun CarIcon(sheet: ImageBitmap, look: Int, goingIn: Boolean) {
    Canvas(modifier = Modifier.size(width = 96.dp, height = 96.dp)) {
        val carW = size.width
        val carH = carW * LevelCars.CELL_H / LevelCars.CELL_W
        val arrowCell = size.width * 0.34f / 9f
        val gap = size.height * 0.03f
        val total = carH + gap + arrowCell * 9
        val top = (size.height - total) / 2f
        val carY = if (goingIn) top + arrowCell * 9 + gap else top
        val arrowY = if (goingIn) top else top + carH + gap
        drawImage(
            image = sheet,
            srcOffset = IntOffset(look * LevelCars.CELL_W, 0),
            srcSize = IntSize(LevelCars.CELL_W, LevelCars.CELL_H),
            dstOffset = IntOffset(0, carY.toInt()),
            dstSize = IntSize(carW.toInt(), carH.toInt()),
            filterQuality = FilterQuality.None
        )
        val rows = if (goingIn) ARROW_UP else ARROW_DOWN
        drawArrow(rows, Offset((size.width - arrowCell * 9) / 2f, arrowY), arrowCell, if (goingIn) null else Amber)
    }
}

private fun DrawScope.drawArrow(rows: List<String>, origin: Offset, cell: Float, tint: Color?) {
    rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            val color = when (ch) {
                'o' -> Ink
                'g' -> tint ?: Color(0xFF8CF0A0)
                'G' -> (tint ?: Color(0xFF8CF0A0)).let { Color(it.red * 0.7f, it.green * 0.7f, it.blue * 0.7f) }
                else -> return@forEachIndexed
            }
            drawRect(color, Offset(origin.x + x * cell, origin.y + y * cell), Size(cell + 0.5f, cell + 0.5f))
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
                .border(2.dp, Ink, RoundedCornerShape(3.dp))
        ) {
            Box(modifier = Modifier.fillMaxWidth(fraction).height(12.dp).background(fill, RoundedCornerShape(3.dp)))
        }
    }
}
