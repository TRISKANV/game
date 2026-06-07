package com.kotlinsurvivors.features.game.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.RunResult
import com.kotlinsurvivors.features.game.domain.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * GameViewModel
 *
 * Fix: removed getWorld() — World is now private to GameEngine.
 * Nothing outside the engine should access World directly.
 */
@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {

    private var engine: GameEngine? = null

    val gameState: StateFlow<GameState>?
        get() = engine?.gameState

    val joystick get() = engine?.joystick

    fun initEngine(viewportWidth: Float, viewportHeight: Float) {
        if (engine != null) return
        engine = GameEngine(viewportWidth, viewportHeight).also {
            it.start(viewModelScope)
        }
    }

    fun pauseGame()  { engine?.pause()  }
    fun resumeGame() { engine?.resume() }

    fun restartGame() {
        engine?.restart(viewModelScope)
    }

    fun applyLevelUpChoice(option: LevelUpOption) {
        engine?.applyLevelUpChoice(option)
    }

    fun saveRunResult(state: GameState) {
        val result = RunResult(
            survivalTime = state.elapsedTime,
            killCount    = state.killCount,
            coinsEarned  = state.playerCoins,
            maxLevel     = state.playerLevel
        )
        viewModelScope.launch {
            gameRepository.saveRunResult(result)
        }
    }

    // getWorld() removed — World is private to GameEngine (thread-safety fix)

    override fun onCleared() {
        super.onCleared()
        engine?.stop()
        engine = null
    }
}
