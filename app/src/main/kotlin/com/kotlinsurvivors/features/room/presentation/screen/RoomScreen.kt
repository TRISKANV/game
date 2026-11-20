package com.kotlinsurvivors.features.room.presentation.screen

import androidx.compose.ui.res.stringResource
import com.kotlinsurvivors.R
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import com.kotlinsurvivors.features.game.presentation.components.JoystickWidget
import com.kotlinsurvivors.features.room.domain.RoomLayout
import com.kotlinsurvivors.features.room.domain.RoomWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Images the room needs: the painted room and the character sprite sheet. */
private class RoomArt(room: Bitmap, character: Bitmap) {
    // Wrapped once here, not on every frame.
    val roomImage      = room.asImageBitmap()
    val characterImage = character.asImageBitmap()
    val characterCellW = character.width / SpriteAtlas.PLAYER_COLUMNS
    val characterCellH = character.height / SpriteAtlas.PLAYER_ROWS
}

/**
 * RoomScreen
 *
 * Level 0: the protagonist's room. The whole room fits on screen and the character
 * walks around with the same virtual joystick as the game. There are no enemies
 * or attacks. Stepping on the mat in front of the door calls [onExit].
 */
@Composable
fun RoomScreen(onExit: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val art by produceState<RoomArt?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try {
                RoomArt(
                    room      = SpriteAtlas.bitmap(context, "room.png"),
                    character = SpriteAtlas.bitmap(context, "player.png")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    val world    = remember { RoomWorld() }
    val joystick = remember { VirtualJoystick() }
    val latestOnExit by rememberUpdatedState(onExit)

    // Bumped every frame so the canvas below is drawn again.
    var frameTick by remember { mutableStateOf(0) }

    LaunchedEffect(world, joystick) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt  = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now

            val input = joystick.getState()
            world.update(dt, input.dx, input.dy)
            frameTick++

            if (world.hasReachedDoor) {
                latestOnExit()
                break
            }
        }
    }

    // The hint fades away after a few seconds.
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MS)
        showHint = false
    }

    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val viewportWidth = with(density) { maxWidth.toPx() }

        // The joystick widget reads plain (non-Compose) state, so the screen is recomposed every
        // frame, the same way the game screen is, to keep the knob following the finger.
        @Suppress("UNUSED_VARIABLE")
        val recomposeEveryFrame = frameTick

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .roomJoystickInput(joystick, viewportWidth)
        ) {
            @Suppress("UNUSED_VARIABLE")
            val redraw = frameTick            // reading it here makes the canvas redraw each frame
            drawRoom(art, world)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            JoystickWidget(
                joystick = joystick,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 40.dp, bottom = 40.dp)
            )
        }

        if (showHint) {
            Text(
                text       = stringResource(R.string.room_hint),
                color      = Color(0xFFE0E0E0),
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xCC0A0A14), RoundedCornerShape(3.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_SECONDS = 0.05f      // a long pause must not make the character jump
private const val HINT_MS = 6000L
private const val WALK_FRAMES_PER_SECOND = 9f

private fun DrawScope.drawRoom(art: RoomArt?, world: RoomWorld) {
    drawRect(Color.Black, size = size)
    if (art == null) return

    // Fit the whole room on screen, keeping its proportions (black bars if needed).
    val scale   = min(size.width / RoomLayout.WIDTH, size.height / RoomLayout.HEIGHT)
    val roomW   = RoomLayout.WIDTH * scale
    val roomH   = RoomLayout.HEIGHT * scale
    val originX = (size.width - roomW) / 2f
    val originY = (size.height - roomH) / 2f

    drawImage(
        image         = art.roomImage,
        dstOffset     = IntOffset(originX.roundToInt(), originY.roundToInt()),
        dstSize       = IntSize(roomW.roundToInt(), roomH.roundToInt()),
        filterQuality = FilterQuality.None
    )

    val cellW = art.characterCellW
    val cellH = art.characterCellH
    val column = if (world.isMoving)
        1 + (world.walkClock * WALK_FRAMES_PER_SECOND).toInt() % (SpriteAtlas.PLAYER_COLUMNS - 1)
    else 0

    // The sprite cell's bottom edge is the feet line; world.x / world.y are the feet.
    val feetScreenX = originX + world.x * scale
    val feetScreenY = originY + world.y * scale
    val spriteW = cellW * scale
    val spriteH = cellH * scale

    drawOval(
        color   = Color(0x66000000),
        topLeft = Offset(feetScreenX - spriteW * 0.3f, feetScreenY - spriteH * 0.04f),
        size    = Size(spriteW * 0.6f, spriteH * 0.1f)
    )
    drawImage(
        image         = art.characterImage,
        srcOffset     = IntOffset(column * cellW, world.facing * cellH),
        srcSize       = IntSize(cellW, cellH),
        dstOffset     = IntOffset((feetScreenX - spriteW / 2f).roundToInt(), (feetScreenY - spriteH).roundToInt()),
        dstSize       = IntSize(spriteW.roundToInt(), spriteH.roundToInt()),
        filterQuality = FilterQuality.None
    )
}

/** Left half of the screen controls the character: touch down to place the joystick, drag to walk. */
private fun Modifier.roomJoystickInput(joystick: VirtualJoystick, viewportWidth: Float): Modifier =
    this.pointerInput(joystick) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x > viewportWidth * 0.5f) return@awaitEachGesture

            val pointerId = down.id
            joystick.onDown(down.position.x, down.position.y)
            try {
                while (true) {
                    val event  = awaitPointerEvent()
                    val change = event.changes.find { it.id == pointerId } ?: break
                    if (!change.pressed) { change.consume(); break }
                    joystick.onMove(change.position.x, change.position.y)
                    change.consume()
                }
            } finally {
                joystick.onUp()
            }
        }
    }
