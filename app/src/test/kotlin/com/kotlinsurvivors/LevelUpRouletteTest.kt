package com.kotlinsurvivors

import com.kotlinsurvivors.engine.LevelUpRoulette
import com.kotlinsurvivors.engine.UpgradeRarity
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
    fun wheelHasDistinctSlicesAndStopsOnTheWinner() {
        repeat(200) { seed ->
            val spin = LevelUpRoulette.spin(pool, Random(seed))
            assertEquals(LevelUpRoulette.SEGMENTS, spin.segments.size)
            assertEquals("slices must be different", spin.segments.size, spin.segments.map { it.id }.toSet().size)
            assertTrue(spin.winnerIndex in spin.segments.indices)
            assertEquals(spin.segments[spin.winnerIndex], spin.winner)
        }
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
    fun smallPoolsStillSpin() {
        val one = LevelUpRoulette.spin(listOf(option("only", UpgradeRarity.COMMON)))
        assertEquals(1, one.segments.size)
        assertEquals("only", one.winner.id)
        val three = LevelUpRoulette.spin(pool.take(3), Random(1))
        assertEquals(3, three.segments.size)
    }
}
