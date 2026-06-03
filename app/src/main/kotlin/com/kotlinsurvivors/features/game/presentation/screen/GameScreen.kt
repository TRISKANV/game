package com.kotlinsurvivors.features.game.presentation.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
 * Hosts the game Canvas, HUD, joystick, and overlay screens (pause, level-up, game over).
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

        // Init engine once with actual viewport size
        LaunchedEffect(viewportWidth, viewportHeight) {
            if (viewportWidth > 0f && viewportHeight > 0f) {
                viewModel.initEngine(viewportWidth, viewportHeight)
            }
        }

        val gameState by (viewModel.gameState?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf(GameState()) })

        // Game Over side-effect
        LaunchedEffect(gameState.isGameOver) {
            if (gameState.isGameOver) {
                viewModel.saveRunResult(gameState)
            }
        }

        // ── Canvas rendering ─────────────────────────────────────────────
        val textMeasurer = rememberTextMeasurer()
        val renderer     = remember(textMeasurer) { GameRenderer(textMeasurer) }

        // frameTime for smooth camera — read actual frame time from choreographer
        var frameTime by remember { mutableStateOf(0.016f) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .joystickInput(viewModel)
        ) {
            val world = gameState.world
            if (world != null) {
                renderer.render(
                    drawScope       = this,
                    world           = world,
                    viewportWidth   = size.width,
                    viewportHeight  = size.height,
                    dt              = frameTime
                )
            }
        }

        // ── HUD ───────────────────────────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            GameHUD(
                gameState  = gameState,
                onPause    = { viewModel.pauseGame() }
            )
        }

        // ── Virtual Joystick ──────────────────────────────────────────────
        if (!gameState.isPaused && !gameState.isGameOver && !gameState.isPendingLevelUp) {
            VirtualJoystickOverlay(viewModel = viewModel)
        }

        // ── Pause Menu ────────────────────────────────────────────────────
        if (gameState.isPaused && !gameState.isGameOver) {
            PauseMenuOverlay(
                onResume   = { viewModel.resumeGame() },
                onRestart  = { viewModel.restartGame() },
                onMainMenu = onNavigateToMenu
            )
        }

        // ── Level-Up Selection ────────────────────────────────────────────
        if (gameState.isPendingLevelUp) {
            LevelUpOverlay(
                options    = gameState.levelUpOptions,
                playerLevel= gameState.playerLevel,
                onChoice   = { option -> viewModel.applyLevelUpChoice(option) }
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

/** Extension to attach joystick touch handling to any Modifier */
private fun Modifier.joystickInput(viewModel: GameViewModel): Modifier = this.pointerInput(Unit) {
    val joystick = viewModel.joystick ?: return@pointerInput
    detectDragGestures(
        onDragStart = { offset ->
            // Only respond to left half of screen for joystick
            if (offset.x < size.width * 0.5f) {
                joystick.onDown(offset.x, offset.y)
            }
        },
        onDrag = { change, _ ->
            joystick.onMove(change.position.x, change.position.y)
        },
        onDragEnd   = { joystick.onUp() },
        onDragCancel= { joystick.onUp() }
    )
}
