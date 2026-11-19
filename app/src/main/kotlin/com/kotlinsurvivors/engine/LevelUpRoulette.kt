package com.kotlinsurvivors.engine

import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import kotlin.random.Random

/**
 * The level-up roulette: the upgrade is not chosen by the player but won by spinning.
 *
 * The result is drawn here, by the engine, in one go; the screen only animates a wheel
 * that lands on it. The rarer the colour, the less likely it is to come up.
 */
object LevelUpRoulette {

    /** Slices on the wheel (fewer when the pool is smaller). */
    const val SEGMENTS = 8

    /** Chance of each rarity in percent. A rarity with nothing to offer is skipped and the rest share its chance. */
    fun weightOf(rarity: UpgradeRarity): Int = when (rarity) {
        UpgradeRarity.COMMON    -> 70
        UpgradeRarity.RARE      -> 22
        UpgradeRarity.EPIC      -> 7
        UpgradeRarity.LEGENDARY -> 1
    }

    /** What the wheel shows, in the order it is drawn, and which slice it stops on. */
    data class Spin(val segments: List<LevelUpOption>, val winnerIndex: Int) {
        val winner: LevelUpOption get() = segments[winnerIndex]
    }

    fun spin(pool: List<LevelUpOption>, random: Random = Random.Default): Spin {
        require(pool.isNotEmpty()) { "The roulette needs at least one upgrade to offer" }
        val remaining = pool.toMutableList()
        val winner = draw(remaining, random)
        // The other slices are drawn the same way, so the wheel looks like the odds do.
        val slices = mutableListOf<LevelUpOption>()
        while (slices.size < SEGMENTS - 1 && remaining.isNotEmpty()) slices.add(draw(remaining, random))
        val winnerIndex = random.nextInt(slices.size + 1)
        slices.add(winnerIndex, winner)
        return Spin(slices, winnerIndex)
    }

    /** Takes one option out of [options]: first a rarity by its chance, then any option of that rarity. */
    private fun draw(options: MutableList<LevelUpOption>, random: Random): LevelUpOption {
        var total = 0
        for (rarity in UpgradeRarity.values()) {
            if (options.any { it.rarity == rarity }) total += weightOf(rarity)
        }
        var roll = random.nextInt(total)
        var picked = options.first().rarity
        for (rarity in UpgradeRarity.values()) {
            if (options.none { it.rarity == rarity }) continue
            if (roll < weightOf(rarity)) { picked = rarity; break }
            roll -= weightOf(rarity)
        }
        val candidates = options.filter { it.rarity == picked }
        val option = candidates[random.nextInt(candidates.size)]
        options.remove(option)
        return option
    }
}
