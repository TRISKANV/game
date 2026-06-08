package com.kotlinsurvivors.engine

import android.util.Log
import com.kotlinsurvivors.core.data.local.AchievementEntity
import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.features.game.domain.model.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementEngine @Inject constructor(
    private val db: GameDatabase
) {
    companion object {
        private const val TAG = "KS_Achievements"
    }

    private val unlockedIds = mutableSetOf<String>()
    private var initialized = false

    suspend fun init() {
        if (initialized) return
        initialized = true
        try {
            val stored = db.achievementDao().getUnlocked()
            unlockedIds.addAll(stored.map { it.id })
            Log.d(TAG, "Loaded ${unlockedIds.size} unlocked achievements")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load achievements from DB", e)
        }
    }

    fun evaluate(state: GameState, scope: CoroutineScope) {
        scope.launch {
            try {
                check("ach_first_kill",   state.killCount >= 1)
                check("ach_survive_1",    state.elapsedTime >= 60f)
                check("ach_survive_5",    state.elapsedTime >= 300f)
                check("ach_survive_10",   state.elapsedTime >= 600f)
                check("ach_survive_20",   state.elapsedTime >= 1200f)
                check("ach_kill_100",     state.killCount >= 100)
                check("ach_kill_500",     state.killCount >= 500)
                check("ach_kill_1000",    state.killCount >= 1000)
                check("ach_level_10",     state.playerLevel >= 10)
                check("ach_level_20",     state.playerLevel >= 20)
                check("ach_coins_500",    state.playerCoins >= 500)
                if (state.elapsedTime > 180f && state.killCount > 0) {
                    check("ach_boss_kill", true)
                }
                check("ach_max_weapons", state.playerLevel >= 10)
            } catch (e: Exception) {
                Log.e(TAG, "CRASH in evaluate()", e)
            }
        }
    }

    private suspend fun check(id: String, condition: Boolean) {
        if (!condition || id in unlockedIds) return
        unlockedIds.add(id)
        try {
            db.achievementDao().upsert(
                AchievementEntity(id = id, isUnlocked = true, unlockedAt = System.currentTimeMillis())
            )
            Log.d(TAG, "Achievement unlocked: $id")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save achievement $id", e)
        }
    }
}
