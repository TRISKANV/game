package com.kotlinsurvivors

import com.kotlinsurvivors.engine.LevelUpRoulette
import com.kotlinsurvivors.engine.UpgradeRarity
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LevelUpRouletteTest {

    private fun option(id: String, rarity: UpgradeRarity) =
        LevelUpOption(id, id, id, UpgradeType.STAT, rarity = rarity)

    /** A pool shaped like the real one: plenty of common and rare, a few epic, a few legendary. */
    private val pool = listOf(
        UpgradeRarity.COMMON to 6, UpgradeRarity.RARE to 5, UpgradeRarity.EPIC to 3, UpgradeRarity.LEGENDARY to 3
    ).flatMap { (rarity, count) -> (1..count).map { option("${rarity.name}_$it", rarity) } }

    @Test
    fun stripHasTheRightLengthAndStopsInsideTheWinnerRange() {
        val seen = HashSet<Int>()
        repeat(300) { seed ->
            val spin = LevelUpRoulette.spin(pool, Random(seed))
            assertEquals(LevelUpRoulette.STRIP_LENGTH, spin.segments.size)
            assertTrue(spin.winnerIndex in LevelUpRoulette.WINNER_RANGE)
            assertEquals(spin.segments[spin.winnerIndex], spin.winner)
            seen.add(spin.winnerIndex)
        }
        assertEquals("the stopping point varies", LevelUpRoulette.WINNER_RANGE.count(), seen.size)
    }

    @Test
    fun rarerColoursComeUpLessOften() {
        val random = Random(2024)
        val wins = HashMap<UpgradeRarity, Int>()
        val spins = 40_000
        repeat(spins) {
            val w = LevelUpRoulette.spin(pool, random).winner
            wins[w.rarity] = (wins[w.rarity] ?: 0) + 1
        }
        fun percent(r: UpgradeRarity) = 100.0 * (wins[r] ?: 0) / spins
        assertEquals(70.0, percent(UpgradeRarity.COMMON), 1.5)
        assertEquals(22.0, percent(UpgradeRarity.RARE), 1.5)
        assertEquals(7.0, percent(UpgradeRarity.EPIC), 1.0)
        assertEquals(1.0, percent(UpgradeRarity.LEGENDARY), 0.5)
    }

    @Test
    fun aRarityWithNothingToOfferGivesItsChanceToTheOthers() {
        val noLegend = pool.filter { it.rarity != UpgradeRarity.LEGENDARY }
        val random = Random(7)
        repeat(2000) {
            assertTrue(LevelUpRoulette.spin(noLegend, random).winner.rarity != UpgradeRarity.LEGENDARY)
        }
    }

    @Test
    fun smallPoolsStillFillTheStrip() {
        val one = LevelUpRoulette.spin(listOf(option("only", UpgradeRarity.COMMON)))
        assertEquals(LevelUpRoulette.STRIP_LENGTH, one.segments.size)
        assertTrue(one.segments.all { it.id == "only" })
        val three = LevelUpRoulette.spin(pool.take(3), Random(1))
        assertEquals(LevelUpRoulette.STRIP_LENGTH, three.segments.size)
        assertTrue(three.segments.all { it in pool.take(3) })
    }

    @Test
    fun theStripLooksLikeTheOdds() {
        val random = Random(99)
        var legendary = 0; var common = 0; var total = 0
        repeat(500) {
            for (item in LevelUpRoulette.spin(pool, random).segments) {
                total++
                if (item.rarity == UpgradeRarity.LEGENDARY) legendary++
                if (item.rarity == UpgradeRarity.COMMON) common++
            }
        }
        assertEquals(70.0, 100.0 * common / total, 2.0)
        assertTrue("legendary should be rare on the strip", 100.0 * legendary / total < 3.0)
    }

    // ── Several roulettes at once ────────────────────────────────────────────

    private fun upgrade(type: WeaponType, level: Int, rarity: UpgradeRarity = UpgradeRarity.COMMON) =
        LevelUpOption("upgrade_${type.name}", "up", "up", UpgradeType.WEAPON_UPGRADE, weaponType = type, rarity = rarity, level = level)

    private fun newWeapon(type: WeaponType) =
        LevelUpOption("new_${type.name}", "new", "new", UpgradeType.NEW_WEAPON, weaponType = type, rarity = UpgradeRarity.RARE)

    @Test
    fun spinManyGivesOneSpinPerRoulette() {
        for (count in 1..LevelUpRoulette.MAX_ROULETTES) {
            val spins = LevelUpRoulette.spinMany(pool, count, 8, Random(count))
            assertEquals(count, spins.size)
            spins.forEach {
                assertEquals(LevelUpRoulette.STRIP_LENGTH, it.segments.size)
                assertTrue(it.winnerIndex in LevelUpRoulette.WINNER_RANGE)
            }
        }
    }

    @Test
    fun aNewWeaponOrLegendaryNeverWinsTwiceInTheSameBatch() {
        val offers = listOf(
            newWeapon(WeaponType.KNIFE), newWeapon(WeaponType.AXE), newWeapon(WeaponType.CROSS),
            option("legend_a", UpgradeRarity.LEGENDARY), option("legend_b", UpgradeRarity.LEGENDARY),
            option("stat_a", UpgradeRarity.COMMON)
        )
        repeat(400) { seed ->
            val ids = LevelUpRoulette.spinMany(offers, 3, 8, Random(seed)).map { it.winner.id }
            val unique = ids.filter { it.startsWith("new_") || it.startsWith("legend_") }
            assertEquals("seed $seed: $ids", unique.toSet().size, unique.size)
        }
    }

    @Test
    fun aWeaponIsNeverUpgradedPastTheLimitByTheSameBatch() {
        // One level short of the cap: only one of the three roulettes may upgrade it.
        val offers = listOf(upgrade(WeaponType.KNIFE, 8), option("stat", UpgradeRarity.COMMON))
        repeat(300) { seed ->
            val wins = LevelUpRoulette.spinMany(offers, 3, 8, Random(seed)).count { it.winner.type == UpgradeType.WEAPON_UPGRADE }
            assertTrue("seed $seed: $wins upgrades", wins <= 1)
        }
        // Two levels short: up to two.
        val offers2 = listOf(upgrade(WeaponType.KNIFE, 7), option("stat", UpgradeRarity.COMMON))
        repeat(300) { seed ->
            val wins = LevelUpRoulette.spinMany(offers2, 3, 8, Random(seed)).count { it.winner.type == UpgradeType.WEAPON_UPGRADE }
            assertTrue("seed $seed: $wins upgrades", wins <= 2)
        }
    }

    @Test
    fun arsenalCountsAsALevelForEveryWeapon() {
        val arsenal = LevelUpOption("legend_arsenal", "a", "a", UpgradeType.STAT, rarity = UpgradeRarity.LEGENDARY)
        val offers = listOf(arsenal, upgrade(WeaponType.KNIFE, 8), option("stat", UpgradeRarity.COMMON))
        repeat(500) { seed ->
            val winners = LevelUpRoulette.spinMany(offers, 3, 8, Random(seed)).map { it.winner }
            val levelsGained = winners.count { it.id == "legend_arsenal" } + winners.count { it.type == UpgradeType.WEAPON_UPGRADE }
            assertTrue("seed $seed: $winners", levelsGained <= 1)
        }
    }

    @Test
    fun statBoostsMayRepeatAndTheBatchAlwaysResolves() {
        val onlyStats = listOf(option("stat", UpgradeRarity.COMMON))
        val spins = LevelUpRoulette.spinMany(onlyStats, 3, 8, Random(1))
        assertEquals(listOf("stat", "stat", "stat"), spins.map { it.winner.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun fourRoulettesAreRefused() {
        LevelUpRoulette.spinMany(pool, 4, 8)
    }
}
