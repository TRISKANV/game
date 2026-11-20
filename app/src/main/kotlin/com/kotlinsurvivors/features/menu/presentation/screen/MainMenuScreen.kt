package com.kotlinsurvivors.features.menu.presentation.screen

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import androidx.compose.ui.res.stringResource
import com.kotlinsurvivors.R
import com.kotlinsurvivors.features.game.presentation.components.PixelButton
import com.kotlinsurvivors.features.game.presentation.components.PixelImage
import com.kotlinsurvivors.features.game.presentation.components.rememberUiImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private val Accent = Color(0xFF7CE04A)

/** Pixel art used behind the menu: the night skyline and the character sprite sheet. */
private class MenuArt(val background: Bitmap, val character: Bitmap)

/**
 * MainMenuScreen
 *
 * Night-city backdrop in pixel art with the main character standing in the
 * street. If the images cannot be loaded the menu still works on a plain
 * dark background.
 */
@Composable
fun MainMenuScreen(
    onStartGame     : () -> Unit,
    onOpenShop      : () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenSettings  : () -> Unit
) {
    val context = LocalContext.current
    val art by produceState<MenuArt?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try {
                MenuArt(
                    background = SpriteAtlas.bitmap(context.applicationContext, "menu_bg.png"),
                    character  = SpriteAtlas.bitmap(context.applicationContext, "player.png")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // Walk-in-place animation of the character: advances a frame every 140 ms.
    val step by produceState(0) {
        while (true) {
            delay(CHARACTER_FRAME_MS)
            value = (value + 1) % WALK_FRAMES
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawMenuBackground(art, step) },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier            = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            val titleImage = rememberUiImage("ui_title.png")
            if (titleImage != null) {
                PixelImage(titleImage, "Kotlin Survivors", targetWidth = 250.dp)
            } else {
                Text(
                    text       = "KOTLIN",
                    color      = Accent,
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 10.sp
                )
                Text(
                    text       = "SURVIVORS",
                    color      = Color.White,
                    fontSize   = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    lineHeight  = 52.sp
                )
            }
            Text(
                text     = stringResource(R.string.menu_subtitle),
                color    = Color(0xFFA5B3A0),
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            MenuButton(stringResource(R.string.menu_start_game),  Accent,            "start",        onClick = onStartGame,    isPrimary = true)
            Spacer(Modifier.height(8.dp))
            MenuButton(stringResource(R.string.menu_shop),  Color(0xFFFFD740), "shop",         onClick = onOpenShop)
            Spacer(Modifier.height(8.dp))
            MenuButton(stringResource(R.string.menu_achievements), Color(0xFF80CBC4), "achievements", onClick = onOpenAchievements)
            Spacer(Modifier.height(8.dp))
            MenuButton(stringResource(R.string.menu_settings),  Color(0xFFB0B8B0), "settings",     onClick = onOpenSettings)

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                stringResource(R.string.menu_version),
                color    = Color(0xFF55605A),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MenuButton(
    text      : String,
    color     : Color,
    art       : String,
    onClick   : () -> Unit,
    isPrimary : Boolean = false
) {
    PixelButton(
        art         = art,
        description = text.filter { it.isLetter() || it == ' ' }.trim(),
        targetWidth = 216.dp,
        onClick     = onClick,
        fallback    = {
            Button(
                onClick  = onClick,
                modifier = Modifier
                    .width(260.dp)
                    .height(if (isPrimary) 56.dp else 48.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isPrimary) color.copy(alpha = 0.22f) else Color(0xAA0A0A12),
                    contentColor   = color
                ),
                shape    = RoundedCornerShape(2.dp),
                border   = BorderStroke(
                    width = if (isPrimary) 3.dp else 2.dp,
                    color = color.copy(alpha = if (isPrimary) 0.85f else 0.45f)
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
    )
}

private fun DrawScope.drawMenuBackground(art: MenuArt?, step: Int) {
    drawRect(color = Color(0xFF07070F), size = size)

    if (art != null) {
        val bg = art.background
        // Cover the screen, keeping the picture's proportions and the street in view.
        val scale   = max(size.width / bg.width, size.height / bg.height)
        val dstW    = (bg.width * scale).roundToInt()
        val dstH    = (bg.height * scale).roundToInt()
        val offsetX = ((size.width - dstW) / 2f).roundToInt()
        val offsetY = (size.height - dstH).roundToInt()
        drawImage(
            image         = bg.asImageBitmap(),
            srcSize       = IntSize(bg.width, bg.height),
            dstOffset     = IntOffset(offsetX, offsetY),
            dstSize       = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None
        )

        // The character stands on the street, to the right of the buttons.
        val sheet  = art.character
        val cellW  = sheet.width / SpriteAtlas.PLAYER_COLUMNS
        val cellH  = sheet.height / SpriteAtlas.PLAYER_ROWS
        val spriteH = size.height * 0.34f
        val spriteW = spriteH * cellW / cellH
        val column  = 1 + step                                  // walk frames 1..4 of the front row
        val feetY   = offsetY + dstH * 0.93f
        val bob     = if (step % 2 == 0) 0f else -spriteH * 0.015f
        drawImage(
            image         = sheet.asImageBitmap(),
            srcOffset     = IntOffset(column * cellW, 0),
            srcSize       = IntSize(cellW, cellH),
            dstOffset     = IntOffset((size.width * 0.80f - spriteW / 2f).roundToInt(), (feetY - spriteH + bob).roundToInt()),
            dstSize       = IntSize(spriteW.roundToInt(), spriteH.roundToInt()),
            filterQuality = FilterQuality.None
        )
    }

    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0x66000000), Color.Transparent, Color(0x99000000)),
            startY = 0f,
            endY   = size.height
        ),
        size = size
    )
}

private const val CHARACTER_FRAME_MS = 140L
private const val WALK_FRAMES = SpriteAtlas.PLAYER_COLUMNS - 1
