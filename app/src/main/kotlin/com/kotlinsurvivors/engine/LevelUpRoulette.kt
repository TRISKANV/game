package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import kotlin.random.Random

/**
 * The level-up roulette, like opening a case: a long strip of upgrades runs past a marker
 * and slows down until it stops on the prize. The player does not choose, the strip decides.
 *
 * The prize is drawn here, by the engine, in one go; the screen only animates the strip
 * and stops it on [Spin.winnerIndex]. The rarer the colour, the less likely it is to come up.
 */
object LevelUpRoulette {

    /** Most roulettes the player may spin at the same time. */
    const val MAX_ROULETTES = 3

    /** The level-up that raises every weapon one level; see [LevelUpRoulette.spinMany]. */
    private const val ARSENAL_ID = "legend_arsenal"

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

    /**
     * One roulette. The strip is drawn from the whole [pool]; the prize it stops on is drawn only
     * from [winnable], which is the whole pool unless the caller has a reason to hold some back.
     */
    fun spin(pool: List<LevelUpOption>, random: Random = Random.Default, winnable: List<LevelUpOption> = pool): Spin {
        require(pool.isNotEmpty()) { "The roulette needs at least one upgrade to offer" }
        require(winnable.isNotEmpty()) { "The roulette needs at least one prize it may stop on" }
        val winner = draw(winnable, random)
        // The rest of the strip is drawn the same way, so it looks like the odds do
        // (and the same item may come by more than once, as in any case opening).
        val strip = MutableList(STRIP_LENGTH) { draw(pool, random) }
        val winnerIndex = WINNER_RANGE.first + random.nextInt(WINNER_RANGE.last - WINNER_RANGE.first + 1)
        strip[winnerIndex] = winner
        return Spin(strip, winnerIndex)
    }

    /**
     * [count] roulettes spun at once, all of them to be handed over together. Because the prizes are
     * applied one after another, a prize that would make no sense after the ones before it cannot come up:
     * a new weapon or a legendary already won on another roulette, and a weapon upgrade that would take
     * the weapon past [maxWeaponLevel] (counting the levels it gets from earlier prizes). Stat boosts may repeat.
     */
    fun spinMany(pool: List<LevelUpOption>, count: Int, maxWeaponLevel: Int, random: Random = Random.Default): List<Spin> {
        require(count in 1..MAX_ROULETTES) { "Between 1 and $MAX_ROULETTES roulettes at a time" }
        val taken = HashSet<String>()
        val gained = HashMap<WeaponType, Int>()
        val spins = ArrayList<Spin>(count)
        repeat(count) {
            val winnable = pool.filter { canWin(it, pool, taken, gained, maxWeaponLevel) }
            val spin = spin(pool, random, winnable.ifEmpty { pool })
            spins.add(spin)
            val prize = spin.winner
            if (prize.type == UpgradeType.NEW_WEAPON || prize.rarity == UpgradeRarity.LEGENDARY) taken.add(prize.id)
            if (prize.type == UpgradeType.WEAPON_UPGRADE) prize.weaponType?.let { gained.merge(it, 1, Int::plus) }
            if (prize.id == ARSENAL_ID) upgradable(pool).forEach { it.weaponType?.let { type -> gained.merge(type, 1, Int::plus) } }
        }
        return spins
    }

    private fun upgradable(pool: List<LevelUpOption>) = pool.filter { it.type == UpgradeType.WEAPON_UPGRADE }

    private fun canWin(
        option: LevelUpOption, pool: List<LevelUpOption>, taken: Set<String>,
        gained: Map<WeaponType, Int>, maxWeaponLevel: Int
    ): Boolean {
        if (option.id in taken) return false
        return when {
            option.type == UpgradeType.WEAPON_UPGRADE ->
                option.level + (gained[option.weaponType] ?: 0) <= maxWeaponLevel
            option.id == ARSENAL_ID ->
                upgradable(pool).any { it.level + (gained[it.weaponType] ?: 0) <= maxWeaponLevel }
            else -> true
        }
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
