package com.kotlinsurvivors.features.game.presentation.components

import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import androidx.compose.ui.res.stringResource
import com.kotlinsurvivors.R
import com.kotlinsurvivors.core.presentation.language.localTitle
import com.kotlinsurvivors.core.presentation.language.localDescription
import com.kotlinsurvivors.core.presentation.language.rarityText
import com.kotlinsurvivors.core.presentation.language.languageSuffix
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlinsurvivors.engine.LevelUpRoulette
import com.kotlinsurvivors.engine.UpgradeRarity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
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
    onSettings : () -> Unit,
    onMainMenu : () -> Unit
) {
    val panel = rememberUiImage("ui_panel_pause.png")
    val title = rememberUiImage("ui_title_paused${languageSuffix()}.png")
    if (panel == null || title == null) {
        PlainPauseMenu(onResume, onRestart, onSettings, onMainMenu)
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
                PixelImage(title, stringResource(R.string.pause_title), targetWidth = unit * title.width)
                Spacer(Modifier.height(unit * PAUSE_TITLE_GAP))
                PixelButton("resume",   stringResource(R.string.pause_resume), unit * PAUSE_BUTTON_WIDTH, onResume)   { PauseButton(stringResource(R.string.pause_resume), Color(0xFF7CE04A), onResume) }
                Spacer(Modifier.height(unit * PAUSE_BUTTON_GAP))
                PixelButton("restart",  stringResource(R.string.pause_restart), unit * PAUSE_BUTTON_WIDTH, onRestart)  { PauseButton(stringResource(R.string.pause_restart), Color(0xFFFFCA28), onRestart) }
                Spacer(Modifier.height(unit * PAUSE_BUTTON_GAP))
                PixelButton("settings", stringResource(R.string.menu_settings), unit * PAUSE_BUTTON_WIDTH, onSettings) { PauseButton(stringResource(R.string.menu_settings), Color(0xFFB0B8B0), onSettings) }
                Spacer(Modifier.height(unit * PAUSE_BUTTON_GAP))
                PixelButton("mainmenu", stringResource(R.string.pause_main_menu), unit * PAUSE_BUTTON_WIDTH, onMainMenu) { PauseButton(stringResource(R.string.pause_main_menu), Color(0xFFEF5350), onMainMenu) }
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
    onSettings : () -> Unit,
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
                Text(stringResource(R.string.pause_title), color = Color(0xFF7CE04A), fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                HorizontalDivider(color = Color(0xFF7CE04A).copy(alpha = 0.2f))
                PauseButton(stringResource(R.string.pause_resume), Color(0xFF7CE04A), onResume)
                PauseButton(stringResource(R.string.pause_restart), Color(0xFFFFCA28), onRestart)
                PauseButton(stringResource(R.string.menu_settings), Color(0xFFB0B8B0), onSettings)
                PauseButton(stringResource(R.string.pause_main_menu), Color(0xFFEF5350), onMainMenu)
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
private const val SPIN_MS = 5200

/** Each extra roulette runs this much longer than the one before, so they stop one after another. */
private const val STAGGER_MS = 1100

/**
 * How one strip is drawn depending on how many run at once: the more there are, the lower and
 * tighter each one is, so three still fit on a landscape phone.
 */
private class ReelLook(
    val tileWidth: Dp, val height: Dp, val icon: Dp, val font: TextUnit, val lineHeight: TextUnit,
    val tilePadding: Dp, val resultHeight: Dp, val stacked: Boolean, val gap: Dp
)

private fun reelLook(count: Int) = when (count) {
    1    -> ReelLook(84.dp, 108.dp, 36.dp, 10.sp, 12.sp, 8.dp, 56.dp, true,  8.dp)
    2    -> ReelLook(80.dp,  84.dp, 30.dp,  9.sp, 10.sp, 4.dp, 20.dp, false, 6.dp)
    else -> ReelLook(76.dp,  62.dp, 20.dp,  9.sp, 10.sp, 3.dp, 16.dp, false, 4.dp)
}

/**
 * Level up like opening a case: a strip of upgrades runs past the marker in the middle, slows down and
 * stops on the prize the engine drew. With several level-ups waiting the player can open two or three
 * at once: the strips run together, stop one after another, and the prizes are taken in one tap.
 * Tapping a strip while they run jumps all of them to their result.
 */
@Composable
fun LevelUpOverlay(
    spins       : List<LevelUpRoulette.Spin>,
    playerLevel : Int,
    onTake      : () -> Unit
) {
    // Read inside graphicsLayer only, so the pulse redraws the title without recomposing the overlay.
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label         = "glowPulse"
    )
    val titleImage = rememberUiImage("ui_title_levelup${languageSuffix()}.png")

    // The screen fades in and grows slightly instead of appearing all at once.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(LEVEL_UP_FADE_MS, easing = FastOutSlowInEasing)) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            // ModulateAlpha fades each thing as it is drawn instead of rendering the whole
            // screen into an extra full-size buffer first, which cost a visible hitch.
            .graphicsLayer { alpha = appear.value; compositingStrategy = CompositingStrategy.ModulateAlpha }
            .background(Color(0xCC000014)),
        contentAlignment = Alignment.Center
    ) {
        if (spins.isEmpty()) {
            CircularProgressIndicator(color = Color(0xFFFFD740), modifier = Modifier.size(32.dp))
            return@Box
        }
        val count = spins.size
        val look  = reelLook(count)
        var landedCount by remember(spins) { mutableIntStateOf(0) }
        var skipTaps    by remember(spins) { mutableIntStateOf(0) }
        val allLanded = landedCount >= count

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(look.gap),
            modifier            = Modifier
                .graphicsLayer {
                    val s = 0.9f + 0.1f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .padding(horizontal = 24.dp)
                .widthIn(max = REEL_MAX_WIDTH)
        ) {
            if (count == 1 && titleImage != null) {
                PixelImage(titleImage, stringResource(R.string.level_up_title), targetWidth = 200.dp, modifier = Modifier.graphicsLayer { alpha = pulse.value })
            } else {
                Text(
                    stringResource(R.string.level_up_title),
                    color         = Color(0xFFFFD740),
                    fontSize      = if (count == 1) 28.sp else 20.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )
            }
            if (count == 1) {
                // Kept in place after the strip stops so nothing jumps.
                Text(
                    stringResource(R.string.level_up_opening, playerLevel),
                    color    = Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    modifier = Modifier.alpha(if (allLanded) 0f else 1f)
                )
            }

            spins.forEachIndexed { index, spin ->
                Reel(
                    spin     = spin,
                    index    = index,
                    look     = look,
                    // the longest strip keeps ticking until the last one stops
                    ticks    = index == count - 1,
                    skipTaps = skipTaps,
                    onSkip   = { skipTaps++ },
                    onLanded = { landedCount++ }
                )
            }

            // Kept at the same height while the strips run so nothing jumps when they stop.
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.fillMaxWidth().heightIn(min = TAKE_AREA_HEIGHT)
            ) {
                if (allLanded) {
                    TakeButton(label = stringResource(if (count == 1) R.string.level_up_take else R.string.level_up_take_all), onClick = onTake)
                } else {
                    Text(stringResource(R.string.level_up_skip), color = Color(0xFF777777), fontSize = 11.sp)
                }
            }
        }
    }
}

/** One strip with its marker, and under it the prize once it has stopped. */
@Composable
private fun Reel(
    spin     : LevelUpRoulette.Spin,
    index    : Int,
    look     : ReelLook,
    ticks    : Boolean,
    skipTaps : Int,
    onSkip   : () -> Unit,
    onLanded : () -> Unit
) {
    val options = spin.segments
    val winner  = spin.winner

    // How far the strip must travel to put the prize under the marker. It stops
    // somewhere inside the prize's tile, not always dead centre, like the real thing.
    val tilePx       = with(LocalDensity.current) { look.tileWidth.toPx() }
    var viewportPx   by remember { mutableIntStateOf(0) }
    val stopInTile   = remember(spin) { 0.5f + (Random.nextFloat() - 0.5f) * 0.7f }
    val target       = spin.winnerIndex * tilePx + stopInTile * tilePx - viewportPx / 2f
    val targetNow    by rememberUpdatedState(target)

    val listState = rememberLazyListState()
    val scroll    = remember { Animatable(0f) }
    val landed by remember { derivedStateOf { viewportPx > 0 && scroll.value >= targetNow - 0.5f } }
    val sounds = LocalSoundEffects.current

    // The strip is a lazy list scrolled by hand: only the tiles in view are ever composed.
    // A tick sounds every time a new tile passes under the marker.
    LaunchedEffect(listState) {
        var applied = 0f
        var lastTile = -1
        snapshotFlow { scroll.value }.collect { value ->
            listState.dispatchRawDelta(value - applied)
            applied = value
            if (ticks && tilePx > 0f && viewportPx > 0) {
                val tile = ((value + viewportPx / 2f) / tilePx).toInt()
                if (tile != lastTile) {
                    if (lastTile != -1) sounds.play(Sound.ROULETTE_TICK)
                    lastTile = tile
                }
            }
        }
    }
    LaunchedEffect(landed) {
        if (landed) { sounds.play(Sound.ROULETTE_WIN); onLanded() }
    }
    LaunchedEffect(viewportPx) {
        if (viewportPx == 0) return@LaunchedEffect
        delay(LEVEL_UP_FADE_MS.toLong())
        scroll.animateTo(target, tween(SPIN_MS + index * STAGGER_MS, easing = CubicBezierEasing(0.08f, 0.55f, 0.12f, 1f)))
    }
    LaunchedEffect(skipTaps) {
        if (skipTaps > 0 && viewportPx > 0) scroll.snapTo(target)
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        // The strip, with the marker in the middle.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(look.height)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF0B0814))
                .onSizeChanged { viewportPx = it.width }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    enabled           = !landed,
                    onClick           = onSkip
                )
        ) {
            LazyRow(
                state             = listState,
                userScrollEnabled = false,
                modifier          = Modifier.fillMaxSize()
            ) {
                items(options.size) { tile -> ReelTile(options[tile], look) }
            }
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(40.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0B0814), Color.Transparent)))
            )
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(40.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF0B0814))))
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gold = Color(0xFFFFD740)
                val dark = Color(0xFF0E0A1A)
                val arrow = if (look.height < 70.dp) 5.dp.toPx() else 8.dp.toPx()
                drawLine(gold, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 3.dp.toPx())
                val top = Path().apply {
                    moveTo(center.x - arrow, 0f); lineTo(center.x + arrow, 0f); lineTo(center.x, arrow * 1.4f); close()
                }
                val bottom = Path().apply {
                    moveTo(center.x - arrow, size.height); lineTo(center.x + arrow, size.height)
                    lineTo(center.x, size.height - arrow * 1.4f); close()
                }
                drawPath(top, gold); drawPath(top, dark, style = Stroke(width = 2.dp.toPx()))
                drawPath(bottom, gold); drawPath(bottom, dark, style = Stroke(width = 2.dp.toPx()))
            }
        }

        // The prize, in the space kept for it under the strip.
        Box(Modifier.fillMaxWidth().height(look.resultHeight), contentAlignment = Alignment.Center) {
            if (landed) ReelResult(winner, look)
        }
    }
}

/** What a stopped strip landed on: its rarity, its name and what it does. */
@Composable
private fun ReelResult(winner: LevelUpOption, look: ReelLook) {
    val rColor = rarityColor(winner.rarity)
    val badge = @Composable {
        Text(
            rarityText(winner.rarity),
            color         = rColor,
            fontSize      = if (look.stacked) 10.sp else 8.sp,
            fontWeight    = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
            modifier      = Modifier
                .background(rColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    if (look.stacked) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badge()
                Text(winner.localTitle(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(winner.localDescription(), color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            badge()
            Text(winner.localTitle(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(winner.localDescription(), color = Color(0xFFBBBBBB), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val REEL_MAX_WIDTH    = 560.dp
private val TAKE_AREA_HEIGHT  = 44.dp

/** One upgrade on the strip: its icon and name inside a frame in the colour of its rarity. */
@Composable
private fun ReelTile(option: LevelUpOption, look: ReelLook) {
    val rColor = rarityColor(option.rarity)
    Box(
        modifier = Modifier
            .width(look.tileWidth)
            .fillMaxHeight()
            .padding(3.dp)
            .pixelFrame(
                unit  = pixelUnitOf(2.dp),
                frame = rColor.copy(alpha = 0.9f),
                body  = lerp(rColor, Color.Black, 0.75f),
                light = lerp(rColor, Color.White, 0.45f)
            )
    ) {
        Column(
            modifier            = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = look.tilePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            PixelIcon(option.icon, look.icon)
            Text(
                option.localTitle(),
                color      = Color.White,
                fontSize   = look.font,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                lineHeight = look.lineHeight,
                maxLines   = 2
            )
        }
    }
}

@Composable
private fun TakeButton(label: String, onClick: () -> Unit) {
    val sounds = LocalSoundEffects.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .pixelFrame(
                unit  = pixelUnitOf(2.dp),
                frame = Color(0xFF7C4DFF),
                body  = Color(0xFF3A2278),
                light = Color(0xFFB39DFF)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                role              = Role.Button,
                onClick           = { sounds.play(Sound.LEVEL_CHOOSE); onClick() }
            )
            .padding(horizontal = 32.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}

// ── Game Over Overlay ────────────────────────────────────────────────────────

@Composable
fun GameOverOverlay(
    gameState  : GameState,
    onRestart  : () -> Unit,
    onMainMenu : () -> Unit
) {
    val titleImage = rememberUiImage("ui_title_gameover${languageSuffix()}.png")

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
                PixelImage(titleImage, stringResource(R.string.game_over_title), targetWidth = 280.dp)
            } else {
                Text(
                    stringResource(R.string.game_over_title),
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
                    stringResource(R.string.game_over_summary),
                    color         = Color(0xFF7CE04A),
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                HorizontalDivider(color = Color(0xFF7CE04A).copy(alpha = 0.2f))

                StatRow("clock", stringResource(R.string.game_over_survived), gameState.formattedTime)
                StatRow("skull", stringResource(R.string.game_over_kills), gameState.killCount.toString())
                StatRow("star",  stringResource(R.string.game_over_level), gameState.playerLevel.toString())
                StatRow("coin",  stringResource(R.string.game_over_coins), gameState.playerCoins.toString())

                // Performance rating
                HorizontalDivider(color = Color(0xFF333355))
                val rating = runRating(gameState)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.game_over_performance), color = Color(0xFFBBBBBB), fontSize = 13.sp)
                    Text(
                        rating.first,
                        color      = rating.second,
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            PixelButton("playagain", stringResource(R.string.game_over_play_again), 220.dp, onRestart) {
                Button(
                    onClick  = onRestart,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CE04A)),
                    shape    = RoundedCornerShape(3.dp)
                ) {
                    Text(stringResource(R.string.game_over_play_again), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
                }
            }

            PixelButton("mainmenu", stringResource(R.string.game_over_main_menu), 220.dp, onMainMenu) {
                OutlinedButton(
                    onClick  = onMainMenu,
                    modifier = Modifier.fillMaxWidth(0.6f),
                    border   = BorderStroke(1.dp, Color(0xFFAAAAAA)),
                    shape    = RoundedCornerShape(3.dp)
                ) {
                    Text(stringResource(R.string.game_over_main_menu), color = Color(0xFFAAAAAA), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun runRating(state: GameState): Pair<String, Color> = when {
    state.elapsedTime >= 600f || state.killCount >= 500 -> Pair("S  " + stringResource(R.string.rank_s), Color(0xFFFFD740))
    state.elapsedTime >= 300f || state.killCount >= 200 -> Pair("A  " + stringResource(R.string.rank_a), Color(0xFFCE93D8))
    state.elapsedTime >= 120f || state.killCount >= 100 -> Pair("B  " + stringResource(R.string.rank_b), Color(0xFF7CE04A))
    state.elapsedTime >= 60f  || state.killCount >= 50  -> Pair("C  " + stringResource(R.string.rank_c), Color(0xFF66BB6A))
    else                                                 -> Pair("D  " + stringResource(R.string.rank_d), Color(0xFFAAAAAA))
}

@Composable
private fun StatRow(iconKey: String, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelIcon(iconKey, 16.dp)
            Text(label, color = Color(0xFFBBBBBB), fontSize = 13.sp)
        }
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
