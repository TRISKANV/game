package com.kotlinsurvivors.engine

import com.kotlinsurvivors.features.game.domain.model.GameState

/**
 * The conditions of every achievement, evaluated against the current run.
 *
 * Pure and free of Android, so it can be unit tested. Every id here must
 * exist in AchievementDefinitions. All numbers are for the run in progress.
 */
object AchievementRules {

    private class Rule(val id: String, val isMet: (GameState) -> Boolean)

    private val rules = listOf(
        Rule("ach_first_kill")   { it.killCount >= 1 },
        Rule("ach_survive_1")    { it.elapsedSeconds >= 60 },
        Rule("ach_survive_5")    { it.elapsedSeconds >= 5 * 60 },
        Rule("ach_survive_10")   { it.elapsedSeconds >= 10 * 60 },
        Rule("ach_survive_20")   { it.elapsedSeconds >= 20 * 60 },
        Rule("ach_kill_100")     { it.killCount >= 100 },
        Rule("ach_kill_500")     { it.killCount >= 500 },
        Rule("ach_kill_1000")    { it.killCount >= 1000 },
        Rule("ach_level_10")     { it.playerLevel >= 10 },
        Rule("ach_level_20")     { it.playerLevel >= 20 },
        Rule("ach_boss_kill")    { it.bossKills >= 1 },
        Rule("ach_max_weapons")  { it.weaponCount >= WeaponOffers.MAX_WEAPONS },
        Rule("ach_coins_500")    { it.playerCoins >= 500 },
        Rule("ach_no_damage_60") { it.secondsWithoutHit >= 60 },
    )

    /** Ids of every achievement whose condition holds for [state]. */
    fun satisfiedBy(state: GameState): List<String> =
        rules.filter { it.isMet(state) }.map { it.id }

    /** All ids this object can decide, for consistency checks. */
    val allIds: List<String> get() = rules.map { it.id }
}
