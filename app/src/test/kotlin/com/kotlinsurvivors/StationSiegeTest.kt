package com.kotlinsurvivors

import com.kotlinsurvivors.engine.StationSiege
import com.kotlinsurvivors.features.game.domain.model.StationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the siege of the police station, without the engine. */
class StationSiegeTest {

    @Test fun theAlarmGoesOnlyOnce() {
        val s = StationSiege()
        assertEquals(StationPhase.QUIET, s.phase)
        assertFalse(s.doorShut)
        assertTrue(s.alarm())
        assertFalse(s.alarm())
        assertEquals(StationPhase.SIEGE, s.phase)
        assertTrue(s.doorShut && s.alarmOn)
    }

    @Test fun officersOnlyCountAfterTheAlarmAndTheChiefWakesAtTheQuota() {
        val s = StationSiege()
        assertFalse(s.copKilled())
        assertEquals(0, s.kills)
        s.alarm()
        repeat(StationSiege.GOAL - 1) { assertFalse(s.copKilled()) }
        assertEquals(StationSiege.GOAL - 1, s.kills)
        assertTrue("the last one wakes the chief", s.copKilled())
        assertEquals(StationPhase.CHIEF, s.phase)
        assertFalse("later kills change nothing", s.copKilled())
    }

    @Test fun theDoorStaysShutAfterTheChiefFallsUntilTheKeyOpensIt() {
        val s = StationSiege()
        assertFalse(s.chiefKilled()); assertFalse(s.openWithKey())
        s.alarm()
        assertFalse("the chief is not up yet", s.chiefKilled())
        repeat(StationSiege.GOAL) { s.copKilled() }
        assertTrue(s.chiefKilled())
        assertEquals(StationPhase.KEY, s.phase)
        assertTrue("the door stays shut until the key opens it", s.doorShut)
        assertFalse("but the alarm is over", s.alarmOn)
        assertTrue(s.openWithKey())
        assertEquals(StationPhase.OPEN, s.phase)
        assertFalse(s.doorShut)
        assertFalse(s.openWithKey())
    }

    @Test fun theKeyOpensTheDoorDuringTheSiegeToo() {
        val s = StationSiege()
        assertFalse("nothing to open while the door is not shut", s.openWithKey())
        assertEquals(StationPhase.QUIET, s.phase)
        s.alarm()
        assertTrue(s.openWithKey())
        assertEquals(StationPhase.OPEN, s.phase)
        assertFalse(s.alarmOn)
        assertFalse("and the siege does not start again", s.alarm())
    }

    @Test fun aChiefWokenInHisSleepSkipsTheQuota() {
        val s = StationSiege()
        s.alarm()
        assertTrue(s.chiefWoke())
        assertEquals(StationPhase.CHIEF, s.phase)
        assertFalse(s.chiefWoke())
    }

    @Test fun reinforcementsComeInWavesAndNeverPassTheQuotaOrTheLimit() {
        val s = StationSiege()
        assertEquals("nothing before the alarm", 0, s.reinforcements(10f, 0))
        s.alarm()
        assertEquals("the first wave waits a moment", 0, s.reinforcements(1f, 8))
        var alive = 8
        var total = alive
        var guard = 0
        while (total < StationSiege.GOAL && guard++ < 10_000) {
            val n = s.reinforcements(0.5f, alive)
            assertTrue(alive + n <= StationSiege.MAX_ALIVE)
            alive += n; total += n
            // the player puts a few down between waves
            repeat(minOf(alive, 2)) { alive--; s.copKilled() }
        }
        assertEquals("exactly the officers the quota needs, no more", StationSiege.GOAL, total)
    }

    @Test fun noReinforcementsOnceTheChiefIsUp() {
        val s = StationSiege()
        s.alarm(); s.chiefWoke()
        assertEquals(0, s.reinforcements(100f, 0))
    }
}
