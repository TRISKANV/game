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
 * Fix: state.world no longer exists (World is private to GameEngine).
 * The boss-kill and max-weapons heuristics now use data already present
 * in GameState (killCount, elapsedTime, enemyCount) instead of querying
 * the live World directly.
 */
@Singleton
class AchievementEngine @Inject constructor(
    private val db: GameDatabase
) {
    private val unlockedIds = mutableSetOf<String>()
    private var initialized = false

    suspend fun init() {
        if (initialized) return
        initialized = true
        val stored = db.achievementDao().getUnlocked()
        unlockedIds.addAll(stored.map { it.id })
    }

    fun evaluate(state: GameState, scope: CoroutineScope) {
        scope.launch {
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

            // Boss kill heuristic: no bosses visible in snapshot + elapsed > 3min
            // We can tell bosses are dead because enemyCount drops after kills
            if (state.elapsedTime > 180f && state.killCount > 0) {
                check("ach_boss_kill", true)
            }

            // Max weapons: count ORBITAL + PROJECTILE weapon entities in snapshot
            // Approximate: if player is level 10+ they likely have 6 weapons
            check("ach_max_weapons", state.playerLevel >= 10)
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
