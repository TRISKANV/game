package com.kotlinsurvivors.features.game.presentation.viewmodel

import com.kotlinsurvivors.core.audio.SoundManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlinsurvivors.core.crash.CrashLogger
import com.kotlinsurvivors.engine.AchievementEngine
import com.kotlinsurvivors.engine.GameEngine
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.map.LevelMapLoader
import com.kotlinsurvivors.engine.map.Levels
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.RunResult
import com.kotlinsurvivors.features.game.domain.repository.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameRepository: GameRepository,
    private val achievementEngine: AchievementEngine,
    private val soundManager: SoundManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "KS_ViewModel"
    }

    private var engine: GameEngine? = null

    val gameState: StateFlow<GameState>?
        get() = engine?.gameState

    val joystick get() = engine?.joystick

    /** Achievements unlocked during play, for the on-screen banner. */
    val newAchievements = achievementEngine.newlyUnlocked

    fun initEngine(viewportWidth: Float, viewportHeight: Float, laboratory: Boolean = false) {
        if (engine != null) return
        Log.d(TAG, "Initialising engine: ${viewportWidth}x${viewportHeight}")
        val newEngine = GameEngine(
            viewportWidth, viewportHeight,
            loadPermanentLevels = { gameRepository.getPermanentUpgradeLevels() },
            loadLevelMap        = { LevelMapLoader.loadLevel1(appContext) },
            loadNextLevelMap    = { LevelMapLoader.load(appContext, Levels.TWO) },
            loadStationMap      = { LevelMapLoader.load(appContext, Levels.STATION) },
            sounds              = soundManager,
            laboratory          = laboratory
        )
        engine = newEngine
        newEngine.start(viewModelScope)
        trackAchievements(newEngine)
    }

    /** Checks the achievement rules once per second of play, and once more at game over. */
    private fun trackAchievements(source: GameEngine) {
        viewModelScope.launch {
            achievementEngine.load()
            var lastChecked = -1
            source.gameState.collect { state ->
                if (state.elapsedSeconds != lastChecked || state.isGameOver) {
                    lastChecked = state.elapsedSeconds
                    achievementEngine.evaluate(state)
                }
            }
        }
    }

    /** The player saw what the boss chest holds and tapped to go on: the reward is handed over. */
    fun confirmChest() {
        engine?.confirmChest()
    }

    /** The tester asked for something in the laboratory. Ignored by a real run. */
    fun sendLabCommand(command: LabCommand) {
        engine?.sendLabCommand(command)
    }

    fun pauseGame()  {
        Log.d(TAG, "pauseGame()")
        engine?.pause()
    }

    /** Pauses when the app leaves the foreground so enemies do not keep attacking unseen. */
    fun pauseForBackground() {
        val running = engine ?: return
        CrashLogger.log("AUTO-PAUSE: app left the foreground")
        running.pause()
    }

    fun resumeGame() {
        Log.d(TAG, "resumeGame()")
        engine?.resume()
    }

    fun restartGame() {
        Log.d(TAG, "restartGame()")
        engine?.restart(viewModelScope)
    }

    fun openLevelUp() {
        Log.d(TAG, "openLevelUp()")
        engine?.openLevelUp()
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
        Log.d(TAG, "ViewModel cleared — releasing engine")
        engine?.release()
        engine = null
    }
}
