package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.components.PlayerComponent

/**
 * The kill streak. Every kill adds one and restarts a short countdown; if [WINDOW_SECONDS]
 * pass without a kill the streak is lost. Reaching a tier gives temporary bonuses that last
 * as long as the streak does, so it pays to keep moving and killing.
 *
 * The bonuses live in their own fields of [PlayerComponent] (the `combo*` ones) and are applied
 * on top of the player's normal stats where those are used, so they never mix with level-up
 * or shop bonuses and vanish cleanly when the streak ends.
 */
class ComboTracker {

    /** What a tier gives while it lasts. */
    class Tier(val minKills: Int, val xp: Float, val attackSpeed: Float, val magnet: Float)

    companion object {
        const val WINDOW_SECONDS = 3.5f

        /** Index 0 is "no streak". Each tier includes everything below it. */
        val TIERS = listOf(
            Tier(0,   1.00f, 1.00f, 1.0f),
            Tier(10,  1.15f, 1.00f, 1.0f),
            Tier(25,  1.15f, 1.10f, 1.0f),
            Tier(50,  1.30f, 1.20f, 1.0f),
            Tier(100, 1.50f, 1.30f, 1.5f)
        )

        /** The streak is not shown until it reaches this many kills. */
        const val MIN_VISIBLE = 3
    }

    var count = 0
        private set
    var tier = 0
        private set

    private var timer = 0f

    /** How much of the countdown is left, from 1 (just killed) down to 0 (streak lost). */
    val fraction: Float get() = if (count == 0) 0f else (timer / WINDOW_SECONDS).coerceIn(0f, 1f)

    val isVisible: Boolean get() = count >= MIN_VISIBLE

    /** Adds [kills] to the streak and restarts the countdown. Returns true if a higher tier was just reached. */
    fun onKills(kills: Int): Boolean {
        if (kills <= 0) return false
        count += kills
        timer  = WINDOW_SECONDS
        var reached = tier
        while (reached + 1 < TIERS.size && count >= TIERS[reached + 1].minKills) reached++
        val up = reached > tier
        tier = reached
        return up
    }

    /** Runs the countdown. Returns true if the streak was lost during this step. */
    fun update(dt: Float): Boolean {
        if (count == 0) return false
        timer -= dt
        if (timer > 0f) return false
        val hadTier = tier > 0
        reset()
        return hadTier
    }

    fun reset() {
        count = 0; tier = 0; timer = 0f
    }

    /** Copies the current tier's bonuses to the player. Cheap enough to call every tick. */
    fun applyTo(player: PlayerComponent) {
        val t = TIERS[tier]
        player.comboXpMultiplier          = t.xp
        player.comboAttackSpeedMultiplier = t.attackSpeed
        player.comboMagnetMultiplier      = t.magnet
    }
}
