package com.kotlinsurvivors.features.room.presentation.screen

import com.kotlinsurvivors.features.settings.presentation.viewmodel.SettingsViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import com.kotlinsurvivors.features.game.presentation.components.JoystickWidget
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.features.room.domain.Interactable
import com.kotlinsurvivors.features.room.domain.RoomInteractables
import com.kotlinsurvivors.features.room.domain.StoryCue
import com.kotlinsurvivors.features.room.domain.StoryDirector
import com.kotlinsurvivors.features.room.presentation.components.PhoneCallOverlay
import com.kotlinsurvivors.features.room.presentation.components.StoryBubble
import com.kotlinsurvivors.features.room.presentation.components.StoryButton
import com.kotlinsurvivors.features.room.presentation.components.StoryDialogueBox
import com.kotlinsurvivors.features.room.presentation.components.StoryFileViewer
import com.kotlinsurvivors.features.room.presentation.components.StoryGlitchMessage
import com.kotlinsurvivors.features.room.presentation.components.StoryInfection
import com.kotlinsurvivors.features.room.presentation.components.StoryRansomScreen
import com.kotlinsurvivors.features.room.presentation.components.StoryKeyItem
import com.kotlinsurvivors.features.room.presentation.components.StoryChatPopup
import com.kotlinsurvivors.features.room.presentation.components.StoryAssistantMessage
import com.kotlinsurvivors.features.room.presentation.components.StoryMission
import com.kotlinsurvivors.features.room.presentation.components.StoryNotification
import com.kotlinsurvivors.features.room.domain.RoomLayout
import com.kotlinsurvivors.features.room.domain.RoomWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

/** Images the room needs: the painted room and the character sprite sheet. */
private class RoomArt(room: Bitmap, character: Bitmap) {
    // Wrapped once here, not on every frame.
    val roomImage      = room.asImageBitmap()
    val characterImage = character.asImageBitmap()
    val characterCellW = character.width / SpriteAtlas.PLAYER_COLUMNS
    val characterCellH = character.height / SpriteAtlas.PLAYER_ROWS
}

/** Clock for the small animations (rain, glow, flicker); read while drawing. */
private class RoomFx { var time = 0f }

/**
 * RoomScreen
 *
 * Level 0: the protagonist's room, where Chapter 0 «Sin conexión» is played. The whole room
 * fits on screen and the character walks around with the same virtual joystick as the game.
 * There are no enemies or attacks. The script itself lives in [StoryDirector]; this screen
 * draws it, plays its sounds and hands the player's actions to it. [onExit] is called when
 * the player has gone through the door at the end of the chapter.
 */
@Composable
fun RoomScreen(
    onExit: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val sfx = LocalSoundEffects.current
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

    // DEAUTH's portrait, decoded once: it goes with her lines, her calls and her notifications.
    val portrait by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try { SpriteAtlas.bitmap(context, "portrait_deauth.png").asImageBitmap() } catch (e: Exception) { null }
        }
    }

    val world    = remember { RoomWorld() }
    val joystick = remember { VirtualJoystick() }
    val fx       = remember { RoomFx() }
    // Lines of the script are string resources found by name, so both languages share one script.
    val director = remember(context) {
        StoryDirector(textOf = { key ->
            val id = context.resources.getIdentifier(key, "string", context.packageName)
            if (id == 0) key else context.getString(id)
        })
    }
    // The joystick size chosen in Settings applies here too, not only in the game.
    SideEffect { joystick.setScale(settings.joystickSize) }
    val latestOnExit by rememberUpdatedState(onExit)

    // Bumped every frame so the canvas below is drawn again.
    var frameTick by remember { mutableStateOf(0) }
    var near by remember { mutableStateOf<Interactable?>(null) }

    // Ambience: the fan, the rain and a quiet melody keep going for as long as the room is on screen.
    val ambience = remember { mutableListOf<Int>() }
    DisposableEffect(sfx) {
        val loops = listOf(Sound.AMB_FAN, Sound.AMB_RAIN, Sound.AMB_MUSIC).map { sfx.startLoop(it) }
        ambience.addAll(loops)
        sfx.play(Sound.AMB_STATIC)
        onDispose { loops.forEach { sfx.stopLoop(it) }; ambience.clear() }
    }

    // The skip button only exists for players who have already been through the introduction:
    // the first time, every line of the story is mandatory.
    val prefs = remember(context) { context.getSharedPreferences("story", android.content.Context.MODE_PRIVATE) }
    val introSeen = remember(prefs) { prefs.getBoolean("intro_seen", false) }

    LaunchedEffect(world, joystick, director) {
        var last = withFrameNanos { it }
        var ringHandle = -1
        var lastChars = 0
        var lastAmbience = 1f
        var seenSaved = false
        try {
            while (true) {
                val now = withFrameNanos { it }
                val dt  = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
                last = now
                fx.time += dt

                director.update(dt)

                // During a call the ambience steps back a little so the voices come first.
                val ambienceVolume = director.ambienceVolume
                if (ambienceVolume != lastAmbience) {
                    ambience.forEach { sfx.setLoopVolume(it, ambienceVolume) }
                    lastAmbience = ambienceVolume
                }
                if (director.missionVisible && !seenSaved) {
                    prefs.edit().putBoolean("intro_seen", true).apply()
                    seenSaved = true
                }

                val input = joystick.getState()
                if (director.canWalk) world.update(dt, input.dx, input.dy) else world.update(dt, 0f, 0f)
                near = if (director.canWalk) RoomInteractables.near(world.x, world.y, director.usable) else null

                for (cue in director.drainCues()) when (cue) {
                    StoryCue.KEYS      -> sfx.play(Sound.AMB_KEYS)
                    StoryCue.ANSWER    -> sfx.play(Sound.PHONE_ANSWER)
                    StoryCue.HANGUP    -> sfx.play(Sound.PHONE_HANGUP)
                    StoryCue.NOTIFY    -> sfx.play(Sound.NOTIFY_PING)
                    StoryCue.FILE_OPEN -> sfx.play(Sound.UI_CLICK)
                    StoryCue.GLITCH    -> { sfx.play(Sound.GLITCH); sfx.play(Sound.AMB_STATIC) }
                    StoryCue.DOOR      -> sfx.play(Sound.DOOR_OPEN)
                    StoryCue.INFECT    -> { sfx.play(Sound.GLITCH); sfx.play(Sound.AMB_STATIC) }
                }

                // The phone rings, on top of the ambience, for as long as the story says so.
                if (director.phoneRinging && ringHandle < 0) ringHandle = sfx.startLoop(Sound.PHONE_RING).let { if (it < 0) Int.MAX_VALUE else it }
                if (!director.phoneRinging && ringHandle != -1) {
                    if (ringHandle != Int.MAX_VALUE) sfx.stopLoop(ringHandle)
                    ringHandle = -1
                }

                // A soft blip every other letter while a line is being typed.
                val chars = director.dialogue?.let { if (it.speaker != null) it.visibleChars else 0 } ?: 0
                if (chars > lastChars && chars % 2 == 0) sfx.play(Sound.DIALOGUE_BLIP)
                lastChars = chars

                frameTick++

                if (director.isDone) {
                    latestOnExit()
                    break
                }
            }
        } finally {
            if (ringHandle > 0 && ringHandle != Int.MAX_VALUE) sfx.stopLoop(ringHandle)
        }
    }

    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val viewportWidth = with(density) { maxWidth.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }
        val roomScale = min(viewportWidth / RoomLayout.WIDTH, viewportHeight / RoomLayout.HEIGHT)
        val roomLeft = (viewportWidth - RoomLayout.WIDTH * roomScale) / 2f
        val roomTop = (viewportHeight - RoomLayout.HEIGHT * roomScale) / 2f

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
            drawRoom(art, world, director, fx.time, near)
        }

        // "CONEXIÓN ESTABLECIDA / DISPOSITIVO IDENTIFICADO", flickering while the monitors glitch.
        if (director.glitchMessageVisible) {
            StoryGlitchMessage(
                text = stringResource(R.string.story_glitch),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (director.canWalk) {
            Box(modifier = Modifier.fillMaxSize()) {
                JoystickWidget(
                    joystick = joystick,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 40.dp, bottom = 40.dp)
                )
            }
            val target = near
            if (target != null) {
                StoryButton(
                    label = stringResource(R.string.story_interact),
                    onClick = { director.interact(target) },
                    strong = true,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp)
                )
            }
            director.bubbleKey?.let { key ->
                StoryBubble(
                    text = remember(key, settings) { context.getString(context.resources.getIdentifier(key, "string", context.packageName)) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                )
            }
        }

        if (director.showAssistantMessage) {
            StoryAssistantMessage(
                name = stringResource(R.string.story_contact),
                text = stringResource(R.string.story_final_msg),
                onTap = { director.tap() },
                portrait = portrait
            )
        }

        if (director.showNotification) {
            StoryNotification(
                title = stringResource(R.string.story_notif_title),
                body  = stringResource(R.string.story_notif_body),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            )
        }

        if (director.missionVisible) {
            StoryMission(objective = stringResource(R.string.story_mission_exit), showBanner = director.showMissionBanner)
        }

        if (director.isCallScreen) {
            PhoneCallOverlay(
                incoming = director.isCallIncoming,
                ending = director.isCallEnding,
                contactName = stringResource(R.string.story_contact),
                onAnswer = { director.answer() },
                portrait = portrait
            )
        }

        // The green window of the file is up first; the chat pop-up lands on top of it and the text
        // only starts to type once the pop-up has been dismissed.
        if (director.chatPopupOpen || director.infecting || director.fileOpen) {
            StoryFileViewer(
                fileName = stringResource(R.string.story_file_name),
                text = stringResource(R.string.story_file_text),
                onClose = { director.closeFile() },
                typing = director.fileOpen
            )
        }

        if (director.infecting) StoryInfection(progress = director.infectionProgress, time = fx.time)

        if (director.showRansom) {
            StoryRansomScreen(
                paymentDoublesIn = director.paymentDoublesIn,
                filesLostIn = director.filesLostIn,
                onClose = { director.closeRansom() }
            )
        }

        if (director.chatPopupOpen) {
            StoryChatPopup(
                name = stringResource(R.string.story_chat_name),
                request = stringResource(R.string.story_chat_request),
                question = stringResource(R.string.story_chat_question),
                yesLabel = stringResource(R.string.story_chat_yes),
                noLabel = stringResource(R.string.story_chat_no),
                onYes = { director.chatYes() },
                onNo = { director.chatNo() }
            )
        }

        director.dialogue?.takeIf { !it.isFinished }?.let { StoryDialogueBox(runner = it, onTap = { director.tap() }, portrait = portrait) }

        if (director.usbRegistered) {
            StoryKeyItem(
                label = stringResource(R.string.story_usb_label),
                name = stringResource(R.string.story_usb_name),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = if (director.missionVisible) 72.dp else 12.dp)
            )
        }

        if (introSeen && !director.missionVisible && !director.isDone) {
            StoryButton(
                label = stringResource(R.string.story_skip),
                onClick = { director.skip() },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).offset(y = if (director.showNotification) 78.dp else 0.dp)
            )
        }

        // Fade from and to black.
        val black = director.blackAlpha
        if (black > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = black)))
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_FRAME_SECONDS = 0.05f      // a long pause must not make the character jump
private const val WALK_FRAMES_PER_SECOND = 9f

// Places in the room picture (pixels of the 640x360 image) that the story animates.
// Screens of the three monitors, inside their frames; each one is lit on its own.
private val SCREENS = listOf(
    RoomLayout.Box(261f, 75f, 32f, 28f),
    RoomLayout.Box(303f, 75f, 46f, 28f),      // the middle monitor is wider than the other two
    RoomLayout.Box(359f, 75f, 32f, 28f)
)
private val WINDOW   = RoomLayout.Box(440f, 18f, 90f, 80f)
private const val PHONE_X = 122f
private const val PHONE_Y = 194f

private fun DrawScope.drawRoom(art: RoomArt?, world: RoomWorld, story: StoryDirector, time: Float, near: Interactable?) {
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

    // Rectangle in room pixels -> screen.
    fun rx(v: Float) = originX + v * scale
    fun ry(v: Float) = originY + v * scale

    // ── Monitors: each screen glows on its own while a file waits and flickers during the glitch ──
    if (story.monitorPulsing) {
        val a = 0.10f + 0.14f * (0.5f + 0.5f * sin(time * 2f * PI.toFloat() * 1.4f))
        for (m in SCREENS) drawRect(Color(0xFF4CE0A0).copy(alpha = a), Offset(rx(m.x), ry(m.y)), Size(m.w * scale, m.h * scale))
    }
    if (story.glitching) {
        val step = (time * 24f).toInt()
        for ((n, m) in SCREENS.withIndex()) {
            drawRect(Color(0xFF020A06), Offset(rx(m.x), ry(m.y)), Size(m.w * scale, m.h * scale))
            if (story.glitchMessageVisible) {
                // Settled: every screen shows the same steady lines while the message is on.
                for (k in 0 until 4) {
                    drawRect(Color(0xFF4CE0A0), Offset(rx(m.x + 3f), ry(m.y + 3f + k * 5f)), Size((m.w - 6f - (k % 2) * 8f) * scale, 1.5f * scale))
                }
            } else {
                // Static: green and cyan bars jumping about, the colours of the monitors themselves.
                for (k in 0 until 4) {
                    val r = ((step * 31 + k * 17 + n * 7) % 97) / 97f
                    val r2 = ((step * 13 + k * 29 + n * 11) % 89) / 89f
                    val w = 8f + r * (m.w - 8f)
                    drawRect(
                        (if (k % 2 == 0) Color(0xFF4CE0A0) else Color(0xFF30C8E8)).copy(alpha = 0.7f),
                        Offset(rx(m.x + r2 * (m.w - w)), ry(m.y + r * (m.h - 3f))),
                        Size(w * scale, (1f + r2 * 2f) * scale)
                    )
                }
            }
        }
    }

    // ── The main (middle) monitor shows the ransom clock once the computer is infected ──
    if (story.infected && !story.glitching) drawMonitorCountdown(SCREENS[1], story.paymentDoublesIn, time, scale, originX, originY)

    // ── The mobile phone on the nightstand ──
    val ringing = story.phoneRinging
    val shake = if (ringing) (if ((time * 30f).toInt() % 2 == 0) 1f else -1f) else 0f
    if (ringing) {
        val a = 0.25f + 0.25f * (0.5f + 0.5f * sin(time * 2f * PI.toFloat() * 2.5f))
        drawCircle(Color(0xFF58C7FF).copy(alpha = a), radius = 16f * scale, center = Offset(rx(PHONE_X + 6f), ry(PHONE_Y + 3f)))
    }
    drawRect(Color(0xFF120A12), Offset(rx(PHONE_X - 1f + shake), ry(PHONE_Y - 1f)), Size(14f * scale, 9f * scale))
    drawRect(
        if (ringing) Color(0xFF9BE3FF) else Color(0xFF28324A),
        Offset(rx(PHONE_X + shake), ry(PHONE_Y)), Size(12f * scale, 7f * scale)
    )

    val cellW = art.characterCellW
    val cellH = art.characterCellH
    val column = if (world.isMoving)
        1 + (world.walkClock * WALK_FRAMES_PER_SECOND).toInt() % (SpriteAtlas.PLAYER_COLUMNS - 1)
    else 0

    // The sprite cell's bottom edge is the feet line; world.x / world.y are the feet.
    val feetScreenX = rx(world.x)
    val feetScreenY = ry(world.y)
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

    // ── Rain on the window ──
    for (i in 0 until 16) {
        val x = WINDOW.x + 4f + (i * 37 % 82)
        val y = WINDOW.y + 4f + ((time * (55f + i % 4 * 12f) + i * 23f) % (WINDOW.h - 8f))
        drawLine(Color(0x99A8C8FF), Offset(rx(x), ry(y)), Offset(rx(x - 1.2f), ry(y + 5f)), strokeWidth = 1f * scale)
    }

    // ── Dark corners, so the room feels like a small lit island at night ──
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x99000000)),
            center = Offset(originX + roomW / 2f, originY + roomH / 2f),
            radius = roomW * 0.62f
        ),
        topLeft = Offset(originX, originY), size = Size(roomW, roomH)
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

// Digits of the monitor clock, 3x5 pixels each ('#' is lit).
private val DIGITS = mapOf(
    '0' to "###" + "#.#" + "#.#" + "#.#" + "###", '1' to ".#." + "##." + ".#." + ".#." + "###",
    '2' to "###" + "..#" + "###" + "#.." + "###", '3' to "###" + "..#" + "###" + "..#" + "###",
    '4' to "#.#" + "#.#" + "###" + "..#" + "..#", '5' to "###" + "#.." + "###" + "..#" + "###",
    '6' to "###" + "#.." + "###" + "#.#" + "###", '7' to "###" + "..#" + "..#" + "..#" + "..#",
    '8' to "###" + "#.#" + "###" + "#.#" + "###", '9' to "###" + "#.#" + "###" + "..#" + "###"
)

/** Draws "HH:MM:SS" in red pixel digits on [screen] (room pixels), on top of the monitor's own picture. */
private fun DrawScope.drawMonitorCountdown(screen: RoomLayout.Box, seconds: Long, time: Float, scale: Float, originX: Float, originY: Float) {
    fun rect(x: Float, y: Float, w: Float, h: Float, c: Color) =
        drawRect(c, Offset(originX + x * scale, originY + y * scale), Size(w * scale, h * scale))

    rect(screen.x, screen.y, screen.w, screen.h, Color(0xFF2A070B))
    rect(screen.x, screen.y, screen.w, 2f, Color(0xFFB02A2A))                          // title strip
    // a small padlock on the left of the strip
    rect(screen.x + 3f, screen.y + 4f, 5f, 1f, Color(0xFFF1E6D0))
    rect(screen.x + 2f, screen.y + 5f, 7f, 5f, Color(0xFFB02A2A))
    rect(screen.x + 5f, screen.y + 6f, 1f, 3f, Color(0xFF120A12))

    val text = com.kotlinsurvivors.features.room.domain.Countdown.formatHours(seconds)
    val width = text.sumOf { if (it == ':') 3 else 4 } - 1
    var x = screen.x + (screen.w - width) / 2f
    val y = screen.y + 14f
    val pulse = if ((time * 2f).toInt() % 2 == 0) Color(0xFFFFD36B) else Color(0xFFFF7A6A)
    for (ch in text) {
        if (ch == ':') {
            val c = if ((time * 2f).toInt() % 2 == 0) pulse else Color(0xFF7A2A2A)
            rect(x + 1f, y + 1f, 1f, 1f, c); rect(x + 1f, y + 3f, 1f, 1f, c)
            x += 3f
        } else {
            val bits = DIGITS.getValue(ch)
            for (i in bits.indices) if (bits[i] == '#') rect(x + i % 3, y + i / 3, 1f, 1f, pulse)
            x += 4f
        }
    }
    // the bar under the digits, emptying as the payment gets closer
    val fraction = (seconds.toFloat() / com.kotlinsurvivors.features.room.domain.Countdown.PAYMENT_DOUBLES_SECONDS).coerceIn(0f, 1f)
    rect(screen.x + 3f, screen.y + screen.h - 4f, screen.w - 6f, 1f, Color(0xFF5A1A1E))
    rect(screen.x + 3f, screen.y + screen.h - 4f, (screen.w - 6f) * fraction, 1f, Color(0xFF58D86A))
}
