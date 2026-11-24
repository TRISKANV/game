package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.AIState
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.engine.ecs.systems.EventDirector
import com.kotlinsurvivors.engine.ecs.systems.EventKind
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.ecs.systems.PatrolSystem
import com.kotlinsurvivors.engine.ecs.systems.SpawnSystem
import com.kotlinsurvivors.engine.input.JoystickState
import kotlin.math.PI
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The K9 patrol: a guard with a dog on a leash who has not noticed the player yet. */
class PatrolTest {

    private val world    = World()
    private val pid      = EntityFactory.createPlayer(world, 2000f, 2000f)
    private val movement = MovementSystem(4096f, 4096f)
    private val dt       = 1f / 30f

    private fun run(seconds: Float) = repeat((seconds / dt).toInt()) { movement.update(world, dt, JoystickState()) }

    /** A patrol [distance] px to the right of the player; the guard looks at [heading]. */
    private fun patrol(distance: Float, heading: Float): Pair<Int, Int> {
        val guard = EntityFactory.createPatrol(world, 2000f + distance, 2000f, 2000f, 2000f)
        val dog   = world.enemies[guard]!!.partner
        world.enemies[guard]!!.patrolDir = heading
        world.enemies[guard]!!.patrolTimer = 100f           // no random turns in these tests
        return guard to dog
    }

    private fun dist(a: Int, b: Int): Float {
        val ta = world.transforms[a]!!; val tb = world.transforms[b]!!
        return hypot(ta.x - tb.x, ta.y - tb.y)
    }

    @Test fun aPatrolIsAGuardAndADogJoinedByTheLeash() {
        val (g, d) = patrol(900f, 0f)
        assertEquals(EnemyType.BASIC, world.enemies[g]!!.type)
        assertEquals(EnemyType.DOG, world.enemies[d]!!.type)
        assertEquals(d, world.enemies[g]!!.partner)
        assertEquals(g, world.enemies[d]!!.partner)
        assertEquals(PatrolState.WALK, world.enemies[g]!!.patrol)
        assertEquals(PatrolState.WALK, world.enemies[d]!!.patrol)
    }

    @Test fun aFarAwayPatrolStrollsAndTheDogNeverBreaksTheLeash() {
        val (g, d) = patrol(900f, 0f)                       // walking away from the player
        val startX = world.transforms[g]!!.x
        repeat(300) {
            run(dt)
            assertTrue("leash stretched to ${dist(g, d)}", dist(g, d) <= PatrolSystem.LEASH_MAX + 1f)
        }
        assertTrue(world.transforms[g]!!.x > startX + 100f)
        assertEquals(PatrolState.WALK, world.enemies[g]!!.patrol)
        assertEquals(0, movement.consumeAlarms())
    }

    @Test fun theGuardSeesThePlayerInFrontOfHimAndLetsTheDogGo() {
        val (g, d) = patrol(250f, PI.toFloat())              // looking straight at him
        run(dt * 2)
        assertEquals(PatrolState.ALERT, world.enemies[g]!!.patrol)
        assertEquals(PatrolState.NONE, world.enemies[d]!!.patrol)
        assertEquals(-1, world.enemies[g]!!.partner)
        assertEquals(-1, world.enemies[d]!!.partner)
        assertEquals(1, movement.consumeAlarms())
        assertEquals(0, movement.consumeAlarms())
    }

    @Test fun theLooseDogRunsAtThePlayerAndTheGuardFollowsAfterTheAlert() {
        val (g, d) = patrol(250f, PI.toFloat())
        run(dt * 2)
        val dogV = world.velocities[d]!!
        assertTrue("dog speed ${hypot(dogV.vx, dogV.vy)}", hypot(dogV.vx, dogV.vy) > 150f)
        assertTrue(dogV.vx < 0f)                              // towards the player, who is on its left
        val guardV = world.velocities[g]!!
        assertEquals(0f, hypot(guardV.vx, guardV.vy), 0.001f)  // the guard is stopped for his "!"
        run(PatrolSystem.ALERT_SECONDS + 0.3f)
        assertEquals(PatrolState.NONE, world.enemies[g]!!.patrol)
        assertEquals(AIState.CHASE, world.enemies[g]!!.aiState)
        assertTrue(world.velocities[g]!!.vx < 0f)
    }

    @Test fun nobodyNoticesAPlayerWhoSneaksUpFromBehind() {
        val (g, _) = patrol(250f, 0f)                         // looking away; the dog is further ahead still
        run(1f)
        assertEquals(PatrolState.WALK, world.enemies[g]!!.patrol)
        // but right behind his back he is noticed anyway
        world.transforms[pid]!!.x = world.transforms[g]!!.x - 60f
        run(dt * 2)
        assertEquals(PatrolState.ALERT, world.enemies[g]!!.patrol)
    }

    @Test fun theDogSmellsThePlayerEvenWhenTheGuardLooksTheOtherWay() {
        val (g, d) = patrol(260f, 0f)                          // guard looking away, far enough not to feel him behind
        run(dt * 2)
        assertEquals(PatrolState.WALK, world.enemies[g]!!.patrol)
        world.transforms[d]!!.x = 2000f + PatrolSystem.DOG_SMELL - 10f
        world.transforms[d]!!.y = 2000f
        run(dt * 2)
        assertEquals(PatrolState.ALERT, world.enemies[g]!!.patrol)
    }

    @Test fun hurtingTheDogRaisesTheAlarm() {
        val (g, d) = patrol(900f, 0f)
        run(dt * 2)
        assertEquals(PatrolState.WALK, world.enemies[g]!!.patrol)
        world.healths[d]!!.current -= 1
        run(dt * 2)
        assertEquals(PatrolState.ALERT, world.enemies[g]!!.patrol)
    }

    @Test fun hurtingTheGuardLetsTheDogGo() {
        val (g, d) = patrol(900f, 0f)
        world.healths[g]!!.current -= 1
        run(dt * 2)
        assertEquals(PatrolState.NONE, world.enemies[d]!!.patrol)
        assertEquals(AIState.CHASE, world.enemies[d]!!.aiState)
    }

    @Test fun losingTheDogAlertsTheGuard() {
        val (g, d) = patrol(900f, 0f)
        world.destroyEntity(d); world.flushDestroyed()
        run(dt * 2)
        assertEquals(PatrolState.ALERT, world.enemies[g]!!.patrol)
    }

    @Test fun losingTheGuardFreesTheDog() {
        val (g, d) = patrol(900f, 0f)
        world.destroyEntity(g); world.flushDestroyed()
        run(dt * 2)
        assertEquals(PatrolState.NONE, world.enemies[d]!!.patrol)
        assertEquals(-1, world.enemies[d]!!.partner)
    }

    @Test fun theK9PatrolEventPutsTaggedPairsOutsideTheScreen() {
        val director = EventDirector(SpawnSystem(1280f, 720f), 4096f, 1280f, 720f)
        val events = mutableListOf<GameEvent>()
        assertTrue(director.force(world, EventKind.K9_PATROL, 2000f, 2000f, 100f, events))
        val tagged = world.enemies.values.filter { it.waveTag != 0 }
        assertEquals(EventDirector.PATROL_PAIRS * 2, tagged.size)
        assertEquals(EventDirector.PATROL_PAIRS, tagged.count { it.type == EnemyType.DOG })
        assertTrue(tagged.all { it.patrol == PatrolState.WALK && it.partner >= 0 })
        assertTrue(events.any { it is GameEvent.EventStarted && it.kind == EventKind.K9_PATROL })
        for ((id, e) in world.enemies) {
            val t = world.transforms[id]!!
            assertTrue("patrol ${e.type} too close: ${hypot(t.x - 2000f, t.y - 2000f)}", hypot(t.x - 2000f, t.y - 2000f) > 700f)
        }
    }
}
