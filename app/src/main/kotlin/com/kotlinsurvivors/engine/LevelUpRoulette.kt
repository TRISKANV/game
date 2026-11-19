package com.kotlinsurvivors.engine

import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import kotlin.random.Random

/**
 * The level-up roulette, like opening a case: a long strip of upgrades runs past a marker
 * and slows down until it stops on the prize. The player does not choose, the strip decides.
 *
 * The prize is drawn here, by the engine, in one go; the screen only animates the strip
 * and stops it on [Spin.winnerIndex]. The rarer the colour, the less likely it is to come up.
 */
object LevelUpRoulette {

    /** Items on the strip. */
    const val STRIP_LENGTH = 40

    /**
     * The prize always sits in this part of the strip, far enough from both ends for the
     * screen to centre it. It varies so the run-up is not always the same length.
     */
    val WINNER_RANGE = 30..34

    /** Chance of each rarity in percent. A rarity with nothing to offer is skipped and the rest share its chance. */
    fun weightOf(rarity: UpgradeRarity): Int = when (rarity) {
        UpgradeRarity.COMMON    -> 70
        UpgradeRarity.RARE      -> 22
        UpgradeRarity.EPIC      -> 7
        UpgradeRarity.LEGENDARY -> 1
    }

    /** The strip, in the order it is shown, and the item it stops on. */
    data class Spin(val segments: List<LevelUpOption>, val winnerIndex: Int) {
        val winner: LevelUpOption get() = segments[winnerIndex]
    }

    fun spin(pool: List<LevelUpOption>, random: Random = Random.Default): Spin {
        require(pool.isNotEmpty()) { "The roulette needs at least one upgrade to offer" }
        val winner = draw(pool, random)
        // The rest of the strip is drawn the same way, so it looks like the odds do
        // (and the same item may come by more than once, as in any case opening).
        val strip = MutableList(STRIP_LENGTH) { draw(pool, random) }
        val winnerIndex = WINNER_RANGE.first + random.nextInt(WINNER_RANGE.last - WINNER_RANGE.first + 1)
        strip[winnerIndex] = winner
        return Spin(strip, winnerIndex)
    }

    /** One item: first a rarity by its chance, then any option of that rarity. */
    private fun draw(options: List<LevelUpOption>, random: Random): LevelUpOption {
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
        return candidates[random.nextInt(candidates.size)]
    }
}
