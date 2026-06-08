package com.kotlinsurvivors.features.game.presentation.viewmodel

import android.util.Log
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

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository
) : ViewModel() {

    companion object {
        private const val TAG = "KS_ViewModel"
    }

    private var engine: GameEngine? = null

    val gameState: StateFlow<GameState>?
        get() = engine?.gameState

    val joystick get() = engine?.joystick

    fun initEngine(viewportWidth: Float, viewportHeight: Float) {
        if (engine != null) return
        Log.d(TAG, "Initialising engine: ${viewportWidth}x${viewportHeight}")
        engine = GameEngine(viewportWidth, viewportHeight).also {
            it.start(viewModelScope)
        }
    }

    fun pauseGame()  {
        Log.d(TAG, "pauseGame()")
        engine?.pause()
    }

    fun resumeGame() {
        Log.d(TAG, "resumeGame()")
        engine?.resume()
    }

    fun restartGame() {
        Log.d(TAG, "restartGame()")
        engine?.restart(viewModelScope)
    }

    fun applyLevelUpChoice(option: LevelUpOption) {
        Log.d(TAG, "applyLevelUpChoice: ${option.id}")
        engine?.applyLevelUpChoice(option)
    }

    fun saveRunResult(state: GameState) {
        Log.d(TAG, "saveRunResult: time=${state.formattedTime} kills=${state.killCount} level=${state.playerLevel}")
        val result = RunResult(
            survivalTime = state.elapsedTime,
            killCount    = state.killCount,
            coinsEarned  = state.playerCoins,
            maxLevel     = state.playerLevel
        )
        viewModelScope.launch {
            try {
                gameRepository.saveRunResult(result)
                Log.d(TAG, "Run result saved successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save run result", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared — stopping engine")
        engine?.stop()
        engine = null
    }
}
