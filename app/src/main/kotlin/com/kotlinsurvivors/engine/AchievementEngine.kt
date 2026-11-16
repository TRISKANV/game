package com.kotlinsurvivors.engine

import android.util.Log
import com.kotlinsurvivors.core.data.local.AchievementEntity
import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.features.game.domain.model.Achievement
import com.kotlinsurvivors.features.game.domain.model.AchievementDefinitions
import com.kotlinsurvivors.features.game.domain.model.GameState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unlocks achievements while a run is in progress and stores them in Room.
 *
 * Call [load] once, then [evaluate] with the current game state whenever it
 * is worth checking (the game checks once per second of play). Newly
 * unlocked achievements are announced on [newlyUnlocked].
 *
 * Not thread-safe: all calls must come from the same coroutine.
 */
@Singleton
class AchievementEngine @Inject constructor(
    private val db: GameDatabase
) {
    private companion object {
        const val TAG = "KS_Achievements"
    }

    private val unlockedIds = HashSet<String>()
    private var loaded = false

    private val _newlyUnlocked = MutableSharedFlow<Achievement>(extraBufferCapacity = 8)
    val newlyUnlocked: SharedFlow<Achievement> = _newlyUnlocked.asSharedFlow()

    suspend fun load() {
        if (loaded) return
        try {
            unlockedIds.addAll(db.achievementDao().getUnlocked().map { it.id })
            loaded = true
            Log.d(TAG, "Loaded ${unlockedIds.size} unlocked achievements")
        } catch (e: Exception) {
            // Stay unloaded so the next call retries instead of re-unlocking everything.
            Log.e(TAG, "Failed to load achievements from DB", e)
        }
    }

    suspend fun evaluate(state: GameState) {
        if (!loaded) return
        for (id in AchievementRules.satisfiedBy(state)) {
            if (id in unlockedIds) continue
            unlock(id)
        }
    }

    private suspend fun unlock(id: String) {
        try {
            db.achievementDao().upsert(
                AchievementEntity(id = id, isUnlocked = true, unlockedAt = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            // Not saved, so it is not unlocked: it will be tried again on the next check.
            Log.e(TAG, "Failed to save achievement $id", e)
            return
        }
        unlockedIds.add(id)
        Log.d(TAG, "Achievement unlocked: $id")
        AchievementDefinitions.all.firstOrNull { it.id == id }?.let { _newlyUnlocked.tryEmit(it) }
    }
}
