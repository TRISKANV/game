package com.kotlinsurvivors.features.game.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.UpgradeRarity
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import com.kotlinsurvivors.features.game.presentation.viewmodel.GameViewModel

// ── Rarity colors ────────────────────────────────────────────────────────────

private fun rarityColor(rarity: UpgradeRarity) = when (rarity) {
    UpgradeRarity.COMMON    -> Color(0xFFAAAAAA)
    UpgradeRarity.RARE      -> Color(0xFF4FC3F7)
    UpgradeRarity.EPIC      -> Color(0xFFCE93D8)
    UpgradeRarity.LEGENDARY -> Color(0xFFFFD740)
}

private fun rarityLabel(rarity: UpgradeRarity) = when (rarity) {
    UpgradeRarity.COMMON    -> "COMMON"
    UpgradeRarity.RARE      -> "RARE"
    UpgradeRarity.EPIC      -> "EPIC"
    UpgradeRarity.LEGENDARY -> "LEGENDARY"
}

// ── Virtual Joystick ─────────────────────────────────────────────────────────

@Composable
fun VirtualJoystickOverlay(viewModel: GameViewModel) {
    val joystick = viewModel.joystick ?: return
    Box(modifier = Modifier.fillMaxSize()) {
        JoystickWidget(
            joystick = joystick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 40.dp)
        )
    }
}

@Composable
fun JoystickWidget(joystick: VirtualJoystick, modifier: Modifier = Modifier) {
    val baseRadius = joystick.maxRadius.dp
    val isActive   = joystick.isActive
    val knobOffset = joystick.getKnobOffset()
    val padImage   = rememberUiImage("ui_joystick_pad.png")
    val nubImage   = rememberUiImage("ui_joystick_nub.png")

    Box(modifier = modifier) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(baseRadius * 2)
                .align(Alignment.Center)
        ) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val r      = size.minDimension / 2f
            val nubCenter = if (isActive)
                androidx.compose.ui.geometry.Offset(center.x + knobOffset.x, center.y + knobOffset.y)
            else center

            if (padImage != null && nubImage != null) {
                // The images are white; multiplying by green tints them to match the theme.
                val tint = ColorFilter.tint(Color(0xFF7CE04A), BlendMode.Modulate)
                drawImage(
                    image         = padImage,
                    dstSize       = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha         = if (isActive) 0.55f else 0.35f,
                    colorFilter   = tint,
                    filterQuality = FilterQuality.Medium
                )
                val nubSize = (r * NUB_DIAMETER_FACTOR).toInt()
                drawImage(
                    image         = nubImage,
                    dstOffset     = IntOffset((nubCenter.x - nubSize / 2f).toInt(), (nubCenter.y - nubSize / 2f).toInt()),
                    dstSize       = IntSize(nubSize, nubSize),
                    alpha         = if (isActive) 0.95f else 0.6f,
                    colorFilter   = tint,
                    filterQuality = FilterQuality.Medium
                )
                return@Canvas
            }

            // Fallback while the images load, or if they are missing.
            drawCircle(Color(0x22FFFFFF), r, center)
            drawCircle(
                Color(0x44FFFFFF), r, center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )
            drawCircle(Color(if (isActive) 0x887CE04A else 0x447CE04A), r * 0.45f, nubCenter)
        }
    }
}

/** Diameter of the joystick knob relative to the radius of its base. */
private const val NUB_DIAMETER_FACTOR = 0.95f

// ── Pause Menu ───────────────────────────────────────────────────────────────

/**
 * Pause menu drawn from pixel art (panel, title and three buttons). The layout is
 * measured in image pixels ([unit] dp each) to match ui_panel_pause.png; if the images
 * are not there it shows the plain Material version.
 */
@Composable
fun PauseMenuOverlay(
    onResume   : () -> Unit,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    val panel = rememberUiImage("ui_panel_pause.png")
    val title = rememberUiImage("ui_title_paused.png")
    if (panel == null || title == null) {
        PlainPauseMenu(onResume, onRestart, onMainMenu)
        return
    }

    val unit = pixelUnit(240.dp, panel.width)
    Box(
        modifier         = Modifier.fillMaxSize().background(Color(0xBB000000)),
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            PixelImage(panel, null, targetWidth = unit * panel.width)
            Column(
                modifier            = Modifier.padding(top = unit * PAUSE_TITLE_TOP),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PixelImage(title, "Paused", targetWidth = unit * title.width)
                Spacer(Modifier.height(unit * PAUSE_TITLE_GAP))
                PixelButton("resume",   "Resume",    unit * PAUSE_BUTTON_WIDTH, onResume)   { PauseButton("▶  Resume",    Color(0xFF7CE04A), onResume) }
                Spacer(Modifier.height(unit * PAUSE_BUTTON_GAP))
                PixelButton("restart",  "Restart",   unit * PAUSE_BUTTON_WIDTH, onRestart)  { PauseButton("↺  Restart",   Color(0xFFFFCA28), onRestart) }
                Spacer(Modifier.height(unit * PAUSE_BUTTON_GAP))
                PixelButton("mainmenu", "Main menu", unit * PAUSE_BUTTON_WIDTH, onMainMenu) { PauseButton("⌂  Main Menu", Color(0xFFEF5350), onMainMenu) }
            }
        }
    }
}

// Positions inside ui_panel_pause.png, in image pixels (see gen_menu_ui.py).
private const val PAUSE_TITLE_TOP    = 7
private const val PAUSE_TITLE_GAP    = 11
private const val PAUSE_BUTTON_WIDTH = 96
private const val PAUSE_BUTTON_GAP   = 3

@Composable
private fun PlainPauseMenu(
    onResume   : () -> Unit,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    Box(
        modifier         = Modifier.fillMaxSize().background(Color(0xBB000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(300.dp),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape    = RoundedCornerShape(3.dp),
            border   = BorderStroke(1.dp, Color(0xFF7CE04A).copy(alpha = 0.4f))
        ) {
            Column(
                modifier            = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("PAUSED", color = Color(0xFF7CE04A), fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                HorizontalDivider(color = Color(0xFF7CE04A).copy(alpha = 0.2f))
                PauseButton("▶  Resume",    Color(0xFF7CE04A), onResume)
                PauseButton("↺  Restart",   Color(0xFFFFCA28), onRestart)
                PauseButton("⌂  Main Menu", Color(0xFFEF5350), onMainMenu)
            }
        }
    }
}

@Composable
private fun PauseButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.15f),
            contentColor   = color
        ),
        shape  = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ── Level-Up Overlay ─────────────────────────────────────────────────────────

private const val LEVEL_UP_FADE_MS = 400

@Composable
fun LevelUpOverlay(
    options     : List<LevelUpOption>,
    playerLevel : Int,
    onChoice    : (LevelUpOption) -> Unit
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "glowPulse"
    )
    val titleImage = rememberUiImage("ui_title_levelup.png")

    // The screen fades in and grows slightly instead of appearing all at once.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(LEVEL_UP_FADE_MS, easing = FastOutSlowInEasing)) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color(0xCC000014)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier
                .graphicsLayer {
                    val s = 0.9f + 0.1f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .padding(horizontal = 20.dp)
        ) {
            // Animated title
            if (titleImage != null) {
                PixelImage(titleImage, "Level up", targetWidth = 250.dp, modifier = Modifier.alpha(pulse))
            } else {
                Text(
                    "⬆ LEVEL UP! ⬆",
                    color       = Color(0xFFFFD740).copy(alpha = pulse),
                    fontSize    = 32.sp,
                    fontWeight  = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )
            }
            Text(
                "Level $playerLevel — Choose your power",
                color    = Color(0xFFBBBBBB),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                if (options.isEmpty()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFFD740), modifier = Modifier.size(32.dp))
                    }
                } else {
                    options.forEach { option ->
                        LevelUpCard(option = option, onClick = { onChoice(option) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelUpCard(option: LevelUpOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source  = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(stiffness = Spring.StiffnessHigh), label = "scale"
    )

    val rColor = rarityColor(option.rarity)
    val rLabel = rarityLabel(option.rarity)

    // Legendary gets a border that pulses
    val legendaryPulse by rememberInfiniteTransition(label = "legendaryPulse").animateFloat(
        initialValue  = 0.7f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "legendaryAlpha"
    )
    val frameColor = if (option.rarity == UpgradeRarity.LEGENDARY) rColor.copy(alpha = legendaryPulse) else rColor.copy(alpha = 0.8f)

    Box(
        modifier = modifier
            .scale(scale)
            .pixelFrame(
                unit  = pixelUnitOf(2.dp),
                frame = frameColor,
                body  = Color(0xFF14101F),
                light = lerp(rColor, Color.White, 0.45f)
            )
            .clickable(
                interactionSource = source,
                indication        = null,
                role              = Role.Button,
                onClick           = onClick
            )
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon
            Text(option.icon, fontSize = 36.sp)

            // Rarity badge
            Text(
                rLabel,
                color      = rColor,
                fontSize   = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp,
                modifier   = Modifier
                    .background(rColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )

            // Title
            Text(
                option.title,
                color      = Color.White,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                lineHeight = 16.sp
            )

            // Description
            Text(
                option.description,
                color     = Color(0xFF999999),
                fontSize  = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}

// ── Game Over Overlay ────────────────────────────────────────────────────────

@Composable
fun GameOverOverlay(
    gameState  : GameState,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    val titleImage = rememberUiImage("ui_title_gameover.png")

    Box(
        modifier         = Modifier.fillMaxSize().background(Color(0xDD000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier            = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
        ) {
            if (titleImage != null) {
                PixelImage(titleImage, "Game over", targetWidth = 280.dp)
            } else {
                Text(
                    "GAME OVER",
                    color       = Color(0xFFEF5350),
                    fontSize    = 38.sp,
                    fontWeight  = FontWeight.ExtraBold,
                    letterSpacing = 5.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // Stats panel
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .pixelFrame(
                        unit  = pixelUnitOf(2.dp),
                        frame = Color(0xFF3A2278),
                        body  = Color(0xFF15101F),
                        light = Color(0xFF7452C8)
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Text(
                    "RUN SUMMARY",
                    color         = Color(0xFF7CE04A),
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                HorizontalDivider(color = Color(0xFF7CE04A).copy(alpha = 0.2f))

                StatRow("⏱  Survived",       gameState.formattedTime)
                StatRow("☠  Kills",           gameState.killCount.toString())
                StatRow("⭐  Level Reached",  gameState.playerLevel.toString())
                StatRow("💰  Coins Earned",   gameState.playerCoins.toString())

                // Performance rating
                HorizontalDivider(color = Color(0xFF333355))
                val rating = runRating(gameState)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("Performance", color = Color(0xFFBBBBBB), fontSize = 13.sp)
                    Text(
                        rating.first,
                        color      = rating.second,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            PixelButton("playagain", "Play again", 220.dp, onRestart) {
                Button(
                    onClick  = onRestart,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CE04A)),
                    shape    = RoundedCornerShape(3.dp)
                ) {
                    Text("Play Again", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
                }
            }

            PixelButton("mainmenu", "Main menu", 220.dp, onMainMenu) {
                OutlinedButton(
                    onClick  = onMainMenu,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    border   = BorderStroke(1.dp, Color(0xFFAAAAAA)),
                    shape    = RoundedCornerShape(3.dp)
                ) {
                    Text("Main Menu", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                }
            }
        }
    }
}

private fun runRating(state: GameState): Pair<String, Color> = when {
    state.elapsedTime >= 600f || state.killCount >= 500 -> Pair("S  LEGENDARY", Color(0xFFFFD740))
    state.elapsedTime >= 300f || state.killCount >= 200 -> Pair("A  EXCELLENT",  Color(0xFFCE93D8))
    state.elapsedTime >= 120f || state.killCount >= 100 -> Pair("B  GREAT",      Color(0xFF7CE04A))
    state.elapsedTime >= 60f  || state.killCount >= 50  -> Pair("C  GOOD",       Color(0xFF66BB6A))
    else                                                 -> Pair("D  KEEP GOING", Color(0xFFAAAAAA))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFBBBBBB), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
