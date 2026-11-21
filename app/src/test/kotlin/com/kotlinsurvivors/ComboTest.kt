package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.components.PlayerComponent
import com.kotlinsurvivors.engine.ecs.systems.ComboTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboTest {

    private val combo = ComboTracker()

    @Test fun startsEmptyAndHidden() {
        assertEquals(0, combo.count)
        assertFalse(combo.isVisible)
        assertEquals(0f, combo.fraction, 0f)
    }

    @Test fun eachKillCountsAndTiersAreReachedAtTheirThresholds() {
        var ups = 0
        repeat(100) { if (combo.onKills(1)) ups++ }
        assertEquals(100, combo.count)
        assertEquals(4, combo.tier)
        assertEquals("one tier-up per tier", 4, ups)
    }

    @Test fun severalKillsInOneFrameCanSkipSeveralTiers() {
        assertTrue(combo.onKills(30))
        assertEquals(2, combo.tier)
    }

    @Test fun aKillRestartsTheCountdown() {
        combo.onKills(1)
        combo.update(ComboTracker.WINDOW_SECONDS - 0.5f)
        combo.onKills(1)
        assertEquals(1f, combo.fraction, 0.001f)
        assertFalse(combo.update(ComboTracker.WINDOW_SECONDS - 0.1f))
        assertEquals(2, combo.count)
    }

    @Test fun theStreakIsLostWhenTheWindowRunsOut() {
        combo.onKills(12)
        assertEquals(1, combo.tier)
        assertTrue("losing a tier is reported", combo.update(ComboTracker.WINDOW_SECONDS + 0.1f))
        assertEquals(0, combo.count)
        assertEquals(0, combo.tier)
    }

    @Test fun losingAStreakBelowTierOneIsNotReported() {
        combo.onKills(4)
        assertFalse(combo.update(ComboTracker.WINDOW_SECONDS + 0.1f))
        assertEquals(0, combo.count)
    }

    @Test fun visibleFromThreeKills() {
        combo.onKills(2); assertFalse(combo.isVisible)
        combo.onKills(1); assertTrue(combo.isVisible)
    }

    @Test fun bonusesAreTemporaryAndNeverTouchTheRealStats() {
        val p = PlayerComponent(attackSpeedMultiplier = 1.3f, xpMultiplier = 1.2f, magnetRadius = 150f)
        combo.onKills(100)
        combo.applyTo(p)
        assertEquals(1.5f, p.comboXpMultiplier, 0f)
        assertEquals(1.3f, p.comboAttackSpeedMultiplier, 0f)
        assertEquals(1.5f, p.comboMagnetMultiplier, 0f)
        assertEquals(1.3f, p.attackSpeedMultiplier, 0f)
        assertEquals(1.2f, p.xpMultiplier, 0f)
        assertEquals(150f, p.magnetRadius, 0f)
        combo.reset(); combo.applyTo(p)
        assertEquals(1f, p.comboXpMultiplier, 0f)
        assertEquals(1f, p.comboAttackSpeedMultiplier, 0f)
        assertEquals(1f, p.comboMagnetMultiplier, 0f)
    }

    @Test fun tiersOnlyGetBetter() {
        for (i in 1 until ComboTracker.TIERS.size) {
            val a = ComboTracker.TIERS[i - 1]; val b = ComboTracker.TIERS[i]
            assertTrue(b.minKills > a.minKills)
            assertTrue(b.xp >= a.xp && b.attackSpeed >= a.attackSpeed && b.magnet >= a.magnet)
        }
    }
}
