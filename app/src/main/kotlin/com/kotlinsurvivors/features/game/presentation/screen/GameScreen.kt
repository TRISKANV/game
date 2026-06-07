package com.kotlinsurvivors.features.game.presentation.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
 * KEY CHANGE: The Canvas now renders gameState.renderSnapshot instead of
 * gameState.world. The renderSnapshot is an immutable list of plain value
 * objects built by the engine thread — the UI thread never touches any
 * live World HashMap, eliminating the ConcurrentModificationException.
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
            GameHUD(gameState = gameState, onPause = { viewModel.pauseGame() })
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
