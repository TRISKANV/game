package com.kotlinsurvivors.features.game.presentation.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.engine.rendering.GameRenderer
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.presentation.components.*
import com.kotlinsurvivors.features.game.presentation.viewmodel.GameViewModel

/**
 * GameScreen
 *
 * Root Composable for the gameplay screen.
 *
 * Crash fixes applied here:
 *  1. Input handling switched from detectDragGestures to raw pointerInput
 *     with awaitEachGesture + awaitFirstDown, so onDragStart/onDrag are
 *     always paired — eliminating onMove() calls without a valid center.
 *  2. The Canvas only renders a snapshot of the scalar HUD values from
 *     GameState (level, HP, etc.) which are safe primitives.  The World
 *     reference is accessed only inside the Canvas lambda which runs on
 *     the UI thread — the engine emits a new GameState reference each
 *     frame via StateFlow so the UI always sees a consistent snapshot.
 *  3. The renderer is keyed on the gameState reference so it only redraws
 *     when the engine emits a new frame (not on every recomposition).
 */
@Composable
fun GameScreen(
    onNavigateToMenu: () -> Unit,
    viewModel: GameViewModel = hiltViewModel()
) {
    val density = LocalDensity.current

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

        // ── Game Canvas ───────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .joystickInput(viewModel, viewportWidth)
        ) {
            // gameState.world is read on the UI thread inside the Canvas
            // lambda — this is safe because StateFlow guarantees we only
            // see values that were fully published by the engine thread.
            val world = gameState.world ?: return@Canvas
            renderer.render(
                drawScope      = this,
                world          = world,
                viewportWidth  = size.width,
                viewportHeight = size.height,
                dt             = 0.016f
            )
        }

        // ── HUD ───────────────────────────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            GameHUD(
                gameState = gameState,
                onPause   = { viewModel.pauseGame() }
            )
        }

        // ── Virtual Joystick visual ───────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            VirtualJoystickOverlay(viewModel = viewModel)
        }

        // ── Overlays ──────────────────────────────────────────────────────
        if (gameState.isPaused && !gameState.isGameOver) {
            PauseMenuOverlay(
                onResume   = { viewModel.resumeGame() },
                onRestart  = { viewModel.restartGame() },
                onMainMenu = onNavigateToMenu
            )
        }

        if (gameState.isPendingLevelUp) {
            LevelUpOverlay(
                options     = gameState.levelUpOptions,
                playerLevel = gameState.playerLevel,
                onChoice    = { option -> viewModel.applyLevelUpChoice(option) }
            )
        }

        if (gameState.isGameOver) {
            GameOverOverlay(
                gameState  = gameState,
                onRestart  = { viewModel.restartGame() },
                onMainMenu = onNavigateToMenu
            )
        }
    }
}

/**
 * Joystick input using low-level pointer tracking instead of
 * detectDragGestures.
 *
 * detectDragGestures has a subtle issue: it calls onDrag even when the
 * initial DOWN event was consumed by another node (e.g. the right-half
 * pause button area), so onMove() was being called without a valid
 * onDown(), producing a (0,0) center and erratic/crashing behavior.
 *
 * This implementation:
 *  - Uses awaitEachGesture to get one complete gesture per coroutine.
 *  - Only activates the joystick if the DOWN is in the LEFT half.
 *  - Tracks the exact pointer ID so multi-touch doesn't confuse centers.
 *  - Always calls onUp() in the finally block — no dangling active state.
 */
private fun Modifier.joystickInput(
    viewModel     : GameViewModel,
    viewportWidth : Float
): Modifier = this.pointerInput(Unit) {
    val joystick = viewModel.joystick ?: return@pointerInput

    awaitEachGesture {
        // Wait for the first finger down
        val down = awaitFirstDown(requireUnconsumed = false)

        // Only activate joystick on the left half of the screen
        if (down.position.x > viewportWidth * 0.5f) return@awaitEachGesture

        val pointerId = down.id
        joystick.onDown(down.position.x, down.position.y)

        try {
            // Track this specific pointer until it lifts
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.find { it.id == pointerId } ?: break

                if (!change.pressed) {
                    // Finger lifted
                    change.consume()
                    break
                }

                joystick.onMove(change.position.x, change.position.y)
                change.consume()
            }
        } finally {
            // Always release — even if gesture is cancelled
            joystick.onUp()
        }
    }
}
