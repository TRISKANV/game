package com.kotlinsurvivors.features.game.presentation.screen

import androidx.compose.runtime.saveable.rememberSaveable
import com.kotlinsurvivors.features.settings.presentation.screen.PauseSettingsPanel
import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.kotlinsurvivors.R
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.engine.rendering.GameRenderer
import com.kotlinsurvivors.engine.rendering.LevelArt
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelNotice
import com.kotlinsurvivors.features.game.domain.model.StationPhase
import com.kotlinsurvivors.engine.StationSiege
import com.kotlinsurvivors.engine.map.Levels
import com.kotlinsurvivors.features.game.presentation.components.*
import com.kotlinsurvivors.features.game.presentation.viewmodel.GameViewModel
import com.kotlinsurvivors.features.settings.presentation.viewmodel.SettingsViewModel
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * GameScreen
 *
 * KEY CHANGE: The Canvas now renders gameState.renderSnapshot instead of
 * gameState.world. The renderSnapshot is an immutable list of plain value
 * objects built by the engine thread — the UI thread never touches any
 * live World HashMap, eliminating the ConcurrentModificationException.
 */
@Composable
fun GameScreen(
    onNavigateToMenu: () -> Unit,
    /** True in the laboratory: nothing spawns by itself, nothing is saved and the test panel is shown. */
    laboratory: Boolean = false,
    viewModel: GameViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val density  = LocalDensity.current
    val context  = LocalContext.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val vibrator = remember(context) { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A14))
    ) {
        val viewportWidth  = with(density) { maxWidth.toPx() }
        val viewportHeight = with(density) { maxHeight.toPx() }

        LaunchedEffect(viewportWidth, viewportHeight) {
            if (viewportWidth > 0f && viewportHeight > 0f) {
                viewModel.initEngine(viewportWidth, viewportHeight, laboratory)
            }
        }

        val gameState by (viewModel.gameState?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(GameState()) })

        LaunchedEffect(gameState.isGameOver) {
            if (gameState.isGameOver && !laboratory) {
                viewModel.saveRunResult(gameState)
            }
        }

        val textMeasurer = rememberTextMeasurer()
        val renderer     = remember(textMeasurer) { GameRenderer(textMeasurer) }

        // Sprites are decoded once, off the main thread. Until they arrive the
        // renderer draws plain shapes, so the game never waits on the assets.
        var sprites by remember { mutableStateOf<SpriteAtlas?>(null) }
        LaunchedEffect(context) {
            sprites = withContext(Dispatchers.IO) { SpriteAtlas.load(context.applicationContext) }
        }

        // Overlay images are decoded now, while the game starts, not when the first one opens.
        LaunchedEffect(context) { UiImageCache.preloadGameOverlays(context.applicationContext) }

        // The picture of the level, decoded the same way. Without it the old ground is drawn.
        var levelArt by remember { mutableStateOf<LevelArt?>(null) }
        // It follows the level the engine is in: when the run crosses over, the picture of the next
        // one is decoded here while the screen is black.
        val levelNumber = gameState.levelNumber
        var artAttemptedFor by remember { mutableIntStateOf(1) }
        LaunchedEffect(context, levelNumber) {
            levelArt = withContext(Dispatchers.IO) { LevelArt.load(context.applicationContext, Levels.of(levelNumber)) }
            artAttemptedFor = levelNumber
        }

        // Settings that act on the game. Plain assignments, safe to repeat every frame.
        val copShout = stringResource(R.string.cop_shout)
        val chiefShout = stringResource(R.string.chief_shout)
        SideEffect {
            renderer.showDamageNumbers = settings.showDamageNumbers
            renderer.showFps           = settings.showFps
            renderer.atlas             = sprites
            renderer.levelArt          = levelArt
            renderer.exitOpen          = gameState.exitOpen
            renderer.copShout          = copShout
            renderer.chiefShout        = chiefShout
            renderer.stationPhase      = gameState.stationPhase
            viewModel.joystick?.setScale(settings.joystickSize)
        }

        // Feedback when the player takes a hit.
        LaunchedEffect(gameState.hitCount) {
            if (gameState.hitCount == 0) return@LaunchedEffect
            if (settings.screenShake) renderer.triggerShake()
            if (settings.vibration) {
                vibrator?.takeIf { it.hasVibrator() }
                    ?.vibrate(VibrationEffect.createOneShot(HIT_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        val onPause = remember(viewModel) { { viewModel.pauseGame() } }

        // Pause automatically when the app goes to the background or the screen
        // turns off. A pending level-up and game over already freeze the game.
        val lifecycleOwner = LocalLifecycleOwner.current
        val latestState by rememberUpdatedState(gameState)
        DisposableEffect(lifecycleOwner, viewModel) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE &&
                    !latestState.isGameOver && !latestState.isPendingLevelUp
                ) {
                    viewModel.pauseForBackground()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Every notice (levels, events, achievements) waits in one queue and is drawn one at a time.
        val notices = remember { NoticeQueue() }
        val sounds = LocalSoundEffects.current
        LaunchedEffect(viewModel) {
            viewModel.newAchievements.collect { achievement ->
                sounds.play(Sound.ACHIEVEMENT)
                notices.post(achievementNotice(context, achievement))
            }
        }
        LaunchedEffect(gameState.levelNotice) { gameState.levelNotice?.let { notices.post(levelNotice(context, it)) } }
        LaunchedEffect(gameState.eventBanner) { gameState.eventBanner?.let { notices.post(eventNotice(context, it)) } }

        // ── Game Canvas ───────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .joystickInput(viewModel, viewportWidth)
        ) {
            // renderSnapshot is an immutable value built on the engine thread.
            // Safe to read here on the UI thread — no World reference involved.
            renderer.render(
                drawScope      = this,
                snapshot       = gameState.renderSnapshot,
                viewportWidth  = size.width,
                viewportHeight = size.height,
                dt             = 0.016f
            )
        }

        // ── HUD ───────────────────────────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            GameHUD(
                hp             = gameState.playerHp,
                maxHp          = gameState.playerMaxHp,
                xp             = gameState.playerXp,
                xpToNext       = gameState.playerXpToNext,
                level          = gameState.playerLevel,
                kills          = gameState.killCount,
                enemies        = gameState.enemyCount,
                coins          = gameState.playerCoins,
                elapsedSeconds = gameState.elapsedSeconds,
                onPause        = onPause,
                comboCount     = gameState.comboCount,
                comboTier      = gameState.comboTier,
                comboFraction  = gameState.comboFraction
            )
        }

        // ── Notifications: one slot under the health bars; the siege objective shows in it between notices ──
        NotificationHost(
            queue     = notices,
            objective = objectiveNotice(context, gameState.stationPhase, gameState.copsDefeated, StationSiege.GOAL),
            visible   = !gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp && gameState.chestReward == null,
            modifier  = Modifier.align(Alignment.TopStart)
        )

        // ── Laboratory panel ──────────────────────────────────────────────────
        if (laboratory && !gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            LabPanel(
                onCommand = viewModel::sendLabCommand,
                modifier  = Modifier.align(Alignment.CenterEnd)
            )
        }

        // ── Black fade over a change of level (and while the next picture is still being decoded) ──
        val blackness = if (artAttemptedFor == levelNumber) gameState.levelFade else 1f
        if (blackness > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackness)))
        }

        // ── A boss chest was opened: the game waits while the player sees what it holds ──
        gameState.chestReward?.let { reward ->
            ChestOverlay(reward = reward, onFinished = viewModel::confirmChest)
        }

        // ── The car: pick a lock, get in, get out, and the health of the car being driven ──
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            CarButton(
                prompt    = gameState.carPrompt,
                progress  = gameState.carProgress,
                carHealth = gameState.carHealth,
                onButton  = viewModel::onCarButton,
                modifier  = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 36.dp)
            )
        }

        // ── Level-up waiting: a button the player opens when ready ───────────
        val canShowBadge = !gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp
        if (canShowBadge && gameState.levelUpsWaiting > 0) {
            LevelUpBadge(
                count    = gameState.levelUpsWaiting,
                onOpen   = { viewModel.openLevelUp() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
            )
        }
        var lastWaiting by remember { mutableIntStateOf(0) }
        LaunchedEffect(gameState.levelUpsWaiting) {
            val grew = gameState.levelUpsWaiting > lastWaiting
            lastWaiting = gameState.levelUpsWaiting
            if (grew && settings.vibration) {
                vibrator?.takeIf { it.hasVibrator() }
                    ?.vibrate(VibrationEffect.createOneShot(LEVEL_UP_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        // ── Virtual Joystick ──────────────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            VirtualJoystickOverlay(viewModel = viewModel)
        }

        // ── Pause ─────────────────────────────────────────────────────────
        // The settings panel opened from the pause menu. Saved so a language change, which
        // recreates the activity, brings the player back to it instead of to the pause menu.
        var showPauseSettings by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(gameState.isPaused) { if (!gameState.isPaused) showPauseSettings = false }
        if (gameState.isPaused && !gameState.isGameOver) {
            if (showPauseSettings) {
                PauseSettingsPanel(viewModel = settingsViewModel, onClose = { showPauseSettings = false })
            } else {
                PauseMenuOverlay(
                    onResume   = { viewModel.resumeGame() },
                    onRestart  = { viewModel.restartGame() },
                    onSettings = { showPauseSettings = true },
                    onMainMenu = onNavigateToMenu
                )
            }
        }

        // ── Level-Up ──────────────────────────────────────────────────────
        if (gameState.isPendingLevelUp) {
            LevelUpOverlay(
                spins       = gameState.levelUpSpins,
                playerLevel = gameState.playerLevel,
                // levels still waiting once the ones on screen are taken
                remaining   = gameState.levelUpsWaiting - gameState.levelUpSpins.size,
                onTake      = { thenOpen -> viewModel.takeLevelUp(thenOpen) }
            )
        }

        // ── Game Over ─────────────────────────────────────────────────────
        if (gameState.isGameOver) {
            GameOverOverlay(
                gameState  = gameState,
                onRestart  = { viewModel.restartGame() },
                onMainMenu = onNavigateToMenu
            )
        }
    }
}

private const val HIT_VIBRATION_MS = 40L
private const val LEVEL_UP_VIBRATION_MS = 70L

private fun Modifier.joystickInput(
    viewModel    : GameViewModel,
    viewportWidth: Float
): Modifier = this.pointerInput(Unit) {
    val joystick = viewModel.joystick ?: return@pointerInput

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
