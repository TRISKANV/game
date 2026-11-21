package com.kotlinsurvivors.features.game.presentation.screen

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
                viewModel.initEngine(viewportWidth, viewportHeight)
            }
        }

        val gameState by (viewModel.gameState?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(GameState()) })

        LaunchedEffect(gameState.isGameOver) {
            if (gameState.isGameOver) {
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
        LaunchedEffect(context) {
            levelArt = withContext(Dispatchers.IO) { LevelArt.loadLevel1(context.applicationContext) }
        }

        // Settings that act on the game. Plain assignments, safe to repeat every frame.
        SideEffect {
            renderer.showDamageNumbers = settings.showDamageNumbers
            renderer.showFps           = settings.showFps
            renderer.atlas             = sprites
            renderer.levelArt          = levelArt
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

        // Banner for achievements unlocked during the run, one at a time.
        var unlockedBanner by remember { mutableStateOf<Achievement?>(null) }
        LaunchedEffect(viewModel) {
            viewModel.newAchievements.collect { achievement ->
                unlockedBanner = achievement
                delay(ACHIEVEMENT_BANNER_MS)
                unlockedBanner = null
            }
        }

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
                onPause        = onPause
            )
        }

        unlockedBanner?.let { achievement ->
            AchievementBanner(
                achievement = achievement,
                modifier    = Modifier.align(Alignment.TopCenter).padding(top = 84.dp)
            )
        }

        // ── A boss chest just gave an exclusive weapon ────────────────────────
        gameState.chestWeapon?.let { weapon ->
            ChestWeaponBanner(
                weapon   = weapon,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 148.dp)
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
        if (gameState.isPaused && !gameState.isGameOver) {
            PauseMenuOverlay(
                onResume   = { viewModel.resumeGame() },
                onRestart  = { viewModel.restartGame() },
                onMainMenu = onNavigateToMenu
            )
        }

        // ── Level-Up ──────────────────────────────────────────────────────
        if (gameState.isPendingLevelUp) {
            LevelUpOverlay(
                options     = gameState.levelUpOptions,
                winnerIndex = gameState.levelUpWinner,
                playerLevel = gameState.playerLevel,
                onChoice    = { option -> viewModel.applyLevelUpChoice(option) }
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
private const val ACHIEVEMENT_BANNER_MS = 3000L

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
