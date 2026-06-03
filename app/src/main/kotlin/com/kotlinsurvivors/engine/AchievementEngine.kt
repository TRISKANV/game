package com.kotlinsurvivors.engine

import com.kotlinsurvivors.core.data.local.AchievementEntity
import com.kotlinsurvivors.core.data.local.GameDatabase
import com.kotlinsurvivors.features.game.domain.model.AchievementDefinitions
import com.kotlinsurvivors.features.game.domain.model.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AchievementEngine
 *
 * Evaluates achievement conditions against the current GameState each run
 * and persists newly unlocked achievements to Room.
 *
 * Called by GameViewModel after each significant state change (end of run,
 * level-up, boss kill, etc.).
 */
@Singleton
class AchievementEngine @Inject constructor(
    private val db: GameDatabase
) {
    private val unlockedIds = mutableSetOf<String>()
    private var initialized = false

    /** Call once at app start to pre-load already-unlocked IDs. */
    suspend fun init() {
        if (initialized) return
        initialized = true
        val stored = db.achievementDao().getUnlocked()
        unlockedIds.addAll(stored.map { it.id })
    }

    /**
     * Evaluates all achievement conditions against [state].
     * Must be called from a coroutine scope (e.g. viewModelScope).
     */
    fun evaluate(state: GameState, scope: CoroutineScope) {
        scope.launch {
            val newly = mutableListOf<String>()

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

            // Boss kill — tracked separately via events (simplified here)
            val world = state.world
            if (world != null && world.bosses.isEmpty() && state.killCount > 0) {
                // Heuristic: if no bosses alive and time > 3 min, assume one was killed
                if (state.elapsedTime > 180f) check("ach_boss_kill", true)
            }

            // Max weapons
            if (world != null) {
                val pid = world.getPlayerEntity()
                val wCount = world.weapons[pid]?.size ?: 0
                check("ach_max_weapons", wCount >= 6)
            }
        }
    }

    private suspend fun check(id: String, condition: Boolean) {
        if (!condition) return
        if (id in unlockedIds) return
        unlockedIds.add(id)
        db.achievementDao().upsert(
            AchievementEntity(
                id         = id,
                isUnlocked = true,
                unlockedAt = System.currentTimeMillis()
            )
        )
    }
}
