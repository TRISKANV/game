package com.kotlinsurvivors.features.room.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import com.kotlinsurvivors.features.game.presentation.components.pixelFrame
import com.kotlinsurvivors.features.game.presentation.components.pixelUnitOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.R
import com.kotlinsurvivors.features.room.domain.DialogueRunner
import com.kotlinsurvivors.features.room.domain.Speaker
import kotlinx.coroutines.delay

private val PanelColor  = Color(0xEE0A0A14)
private val Protagonist = Color(0xFFFFD36B)
private val Assistant   = Color(0xFF6BD3FF)

/**
 * Bottom window of the story: the speaker's name in the title bar, the text typed letter by letter
 * and, for DEAUTH, her portrait so the player knows who is talking. A tap anywhere moves on.
 */
@Composable
fun StoryDialogueBox(
    runner: DialogueRunner, onTap: () -> Unit, modifier: Modifier = Modifier,
    portrait: ImageBitmap? = null
) {
    // The whole screen catches the tap, not only the box: the player never has to aim.
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(runner) { detectTapGestures { onTap() } }
    ) {
        val speaker = runner.speaker ?: return@Box          // a silence: nothing on screen
        val name = stringResource(
            if (speaker == Speaker.PROTAGONIST) R.string.story_speaker_protagonist else R.string.story_speaker_assistant
        )
        val deauth = speaker == Speaker.ASSISTANT
        StoryWindow(
            name = name,
            accent = if (deauth) StoryStyle.Purple else StoryStyle.Green,
            light = if (deauth) StoryStyle.PurpleLight else StoryStyle.GreenLight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp, start = 24.dp, end = 28.dp)
                .fillMaxWidth(0.84f)
        ) {
            Row(
                modifier = Modifier.heightIn(min = 68.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (deauth) StoryPortrait(portrait, Modifier.size(width = 62.dp, height = 67.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = runner.visibleText, color = StoryStyle.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    if (runner.isLineComplete) {
                        Text(
                            text = stringResource(R.string.story_tap_continue), color = StoryStyle.Hint, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        }
    }
}

/** A remark about an object. It does not take the screen: the player keeps walking. */
@Composable
fun StoryBubble(text: String, modifier: Modifier = Modifier) {
    StoryRemark(modifier = modifier.widthIn(max = 380.dp)) {
        Text(text = text, color = StoryStyle.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
    }
}

/**
 * The mobile phone during a call: contact name, incoming-call state with a vibration,
 * the answer button, the "on call" state and the hang-up. There is no picture of the caller.
 */
@Composable
fun PhoneCallOverlay(
    incoming: Boolean,
    ending: Boolean,
    contactName: String,
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: ImageBitmap? = null
) {
    val latestOnAnswer by rememberUpdatedState(onAnswer)
    val vibration = rememberInfiniteTransition(label = "vibration")
    val shake by vibration.animateFloat(
        initialValue = -2.5f, targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(70), RepeatMode.Reverse), label = "shake"
    )
    val pulse by vibration.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse"
    )
    val fade by animateFloatAsState(if (ending) 0f else 1f, tween(1000), label = "hangup")
    val shrink by animateFloatAsState(if (ending) 0.86f else 1f, tween(1000), label = "hangupScale")

    Box(modifier = modifier.fillMaxSize().background(Color(0x990A0C14)), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxHeight(0.62f)
                .aspectRatio(0.62f)
                .graphicsLayer {
                    rotationZ = if (incoming) shake else 0f
                    alpha = fade
                    scaleX = shrink; scaleY = shrink
                }
                .background(Color(0xFF12141F), RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFF3A3F5C), RoundedCornerShape(14.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = stringResource(
                    when {
                        ending   -> R.string.story_call_ended
                        incoming -> R.string.story_incoming
                        else     -> R.string.story_in_call
                    }
                ),
                color = Color(0xFFB8BED8), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.graphicsLayer { alpha = if (incoming) pulse else 1f }
            )
            StoryPortrait(portrait, Modifier.size(width = 60.dp, height = 65.dp))
            Text(
                text = contactName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
            )
            if (incoming) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF2EA84F), RoundedCornerShape(24.dp))
                        .pointerInput(Unit) { detectTapGestures { latestOnAnswer() } }
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.story_answer), color = Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** The notification that pops up on the computer when a file arrives. */
@Composable
fun StoryNotification(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 240.dp)
            .background(PanelColor, RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFF4CE0A0), RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, color = Color(0xFF4CE0A0), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(body, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** NO_ABRIR.txt in a terminal-like window; the text is typed out and CLOSE returns to the room. */
@Composable
fun StoryFileViewer(
    fileName: String, text: String, onClose: () -> Unit, modifier: Modifier = Modifier,
    typing: Boolean = true
) {
    val latestOnClose by rememberUpdatedState(onClose)
    var shown by remember(text) { mutableStateOf(0) }
    // While something is on top of the window (the chat pop-up) the text waits, so it starts typing
    // only once the player can read it.
    LaunchedEffect(text, typing) {
        if (!typing) return@LaunchedEffect
        while (shown < text.length) {
            delay(24)
            shown += 2
        }
    }
    Box(modifier = modifier.fillMaxSize().background(Color(0xCC03060A)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.86f)
                .fillMaxWidth(0.72f)
                .background(Color(0xFF05100C), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF2FAF6F), RoundedCornerShape(4.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(fileName, color = Color(0xFF4CE0A0), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = text.take(shown), color = Color(0xFFB6FFD8), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 16.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .border(1.dp, Color(0xFF2FAF6F), RoundedCornerShape(3.dp))
                    .pointerInput(Unit) { detectTapGestures { latestOnClose() } }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text(stringResource(R.string.story_file_close), color = Color(0xFF4CE0A0), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** The current objective, top left, and the banner announcing it. */
@Composable
fun StoryMission(objective: String, showBanner: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(PanelColor, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFFFFD36B), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.story_mission_label), color = Color(0xFFFFD36B), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(objective, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        if (showBanner) {
            Text(
                text = stringResource(R.string.story_mission_updated),
                color = Color(0xFFFFD36B), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(PanelColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/** The USE and SKIP buttons, drawn with the same pixel frame as the game's own panels. */
@Composable
fun StoryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, strong: Boolean = false) {
    // The screen is recomposed every frame, which hands over a new onClick each time. Keyed on it,
    // the tap detector would restart before the finger lifts and the tap would never arrive.
    val latestOnClick by rememberUpdatedState(onClick)
    val unit = pixelUnitOf(if (strong) 3.dp else 2.dp)
    Box(
        modifier = modifier
            .pixelFrame(
                unit  = unit,
                frame = if (strong) Color(0xFF2EA84F) else Color(0xFF5A6086),
                body  = if (strong) Color(0xFF123A20) else Color(0xFF14172A),
                light = if (strong) Color(0xFF9CFFB4) else Color(0xFFA9B0DA)
            )
            .pointerInput(Unit) { detectTapGestures { latestOnClick() } }
            .padding(
                horizontal = unit * (if (strong) 9 else 6),
                vertical   = unit * (if (strong) 6 else 3)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (strong) Color(0xFFD8FFE2) else Color(0xFFD0D5F0),
            fontSize = if (strong) 15.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(shadow = Shadow(Color(0xFF06050A), Offset(2f, 2f), 0f))
        )
    }
}

/** The system message that flashes while the monitors glitch. */
@Composable
fun StoryGlitchMessage(text: String, modifier: Modifier = Modifier) {
    val unit = pixelUnitOf(2.dp)
    Text(
        text = text, color = Color(0xFF7CFFB0), fontSize = 13.sp, fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
        modifier = modifier
            .pixelFrame(unit, Color(0xFF2FAF6F), Color(0xFF04120B), Color(0xFF7CFFB0))
            .padding(horizontal = unit * 8, vertical = unit * 5)
    )
}

/** The contact's last message, as a phone notification; a tap moves the story on. */
@Composable
fun StoryAssistantMessage(
    name: String, text: String, onTap: () -> Unit, modifier: Modifier = Modifier,
    portrait: ImageBitmap? = null
) {
    val latestOnTap by rememberUpdatedState(onTap)
    Box(
        modifier = modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { latestOnTap() } },
        contentAlignment = Alignment.TopCenter
    ) {
        StoryWindow(
            name = name, accent = StoryStyle.Purple, light = StoryStyle.PurpleLight,
            modifier = Modifier.padding(top = 16.dp, end = 4.dp).widthIn(max = 400.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StoryPortrait(portrait, Modifier.size(width = 50.dp, height = 54.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text, color = StoryStyle.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        stringResource(R.string.story_tap_continue), color = StoryStyle.Hint, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/**
 * A chat request that pops up over the file before it opens. "NO" dismisses it; "YES" does
 * nothing yet (that branch of the story is still to be written).
 */
@Composable
fun StoryChatPopup(
    name: String, request: String, question: String, yesLabel: String, noLabel: String,
    onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val art by produceState<Pair<ImageBitmap, ImageBitmap>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            try {
                SpriteAtlas.bitmap(context, "popup_mandy.png").asImageBitmap() to
                    SpriteAtlas.bitmap(context, "popup_chat.png").asImageBitmap()
            } catch (e: Exception) { null }
        }
    }
    val unit = pixelUnitOf(2.dp)
    Box(modifier = modifier.fillMaxSize().background(Color(0xAA03060A)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.68f)   // inside the green window (0.72 wide), never wider
                .pixelFrame(unit, Color(0xFF3A2F3A), Color(0xFFEDE3D8), Color(0xFFFFF6EA))
                .padding(horizontal = unit * 8, vertical = unit * 6),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatImage(art?.first, Modifier.size(width = 72.dp, height = 90.dp), FilterQuality.Low)   // the portrait as supplied, only scaled
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, color = Color(0xFF1B1420), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(request, color = Color(0xFF2E2433), fontSize = 12.sp)
                Text(question, color = Color(0xFF1B1420), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                    ChatChoice(yesLabel, Color(0xFFE8D860), Color(0xFFF6EE9A), onYes)
                    ChatChoice(noLabel, Color(0xFF9A9AA2), Color(0xFFC4C4CC), onNo)
                }
            }
            ChatImage(art?.second, Modifier.size(36.dp), FilterQuality.None)
        }
    }
}

@Composable
private fun ChatImage(image: ImageBitmap?, modifier: Modifier, quality: FilterQuality) {
    Canvas(modifier = modifier) {
        if (image != null) drawImage(
            image = image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = quality
        )
    }
}

@Composable
private fun ChatChoice(label: String, body: Color, light: Color, onClick: () -> Unit) {
    val latestOnClick by rememberUpdatedState(onClick)
    val unit = pixelUnitOf(2.dp)
    Box(
        modifier = Modifier
            .pixelFrame(unit, Color(0xFF3A2F3A), body, light)
            .pointerInput(Unit) { detectTapGestures { latestOnClick() } }
            .padding(horizontal = unit * 12, vertical = unit * 4),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFF1B1420), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

/** A key item of the story, shown as a small tag once the protagonist has it (the USB with the evidence). */
@Composable
fun StoryKeyItem(label: String, name: String, modifier: Modifier = Modifier) {
    val unit = pixelUnitOf(2.dp)
    Column(
        modifier = modifier
            .pixelFrame(unit, Color(0xFFB8943C), Color(0xFF1A1508), Color(0xFFFFE08A))
            .padding(horizontal = unit * 6, vertical = unit * 3)
    ) {
        Text(label, color = Color(0xFFFFD36B), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(name, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
