package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.systems.RiotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The riot officer: the shield takes the first hits, and only without it does he run and hurt. */
class RiotCopTest {

    private fun riot(world: World = World()): Pair<World, Int> = world to EntityFactory.createEnemy(world, EnemyType.RIOT, 0f, 0f)

    @Test fun startsWithTheShieldOnTopOfHisBody() {
        val (w, id) = riot()
        val e = w.enemies[id]!!; val h = w.healths[id]!!
        assertTrue(e.shieldUp)
        assertEquals(e.bodyHp + RiotRules.shieldHp(1f), h.max)
        assertEquals(h.max, h.current)
    }

    @Test fun theShieldHoldsWhileDamageStaysInsideIt() {
        val (w, id) = riot()
        val e = w.enemies[id]!!; val h = w.healths[id]!!
        h.current -= RiotRules.shieldHp(1f) - 1
        assertFalse(RiotRules.settle(e, h))
        assertTrue(e.shieldUp)
    }

    @Test fun aBigHitStopsAtTheBodyAndBreaksTheShieldOnce() {
        val (w, id) = riot()
        val e = w.enemies[id]!!; val h = w.healths[id]!!
        h.current = 1                                   // a huge hit lands in one frame
        assertTrue(RiotRules.settle(e, h))
        assertEquals(e.bodyHp, h.current)
        assertFalse(e.shieldUp)
        assertFalse("it breaks only once", RiotRules.settle(e, h))
    }

    @Test fun whileTheShieldHoldsHeDoesNoHarm() {
        val (w, id) = riot()
        assertTrue(w.enemies[id]!!.shieldUp)            // CollisionSystem skips enemies with shieldUp
        assertTrue(RiotRules.RUN_SPEED > RiotRules.WALK_SPEED * 2)
    }
}
