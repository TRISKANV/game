package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.WeaponSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhipTest {

    private val world = World()
    private val pid   = EntityFactory.createPlayer(world, 500f, 500f)
    private val system = WeaponSystem()

    private fun enemyAt(x: Float, y: Float) = EntityFactory.createEnemy(world, EnemyType.BASIC, x, y)
    private fun hurt(id: Int) = world.healths[id]!!.current < world.healths[id]!!.max
    private fun lashes() = world.particles.values.filter { it.type == ParticleType.LASH }
    private fun whip() = world.weapons[pid]!![0]

    @Test fun doesNotCrackWithNobodyInReach() {
        enemyAt(500f, 200f)                       // far above
        enemyAt(900f, 500f)                       // far to the right
        repeat(120) { system.update(world, 0.016f) }
        assertTrue(lashes().isEmpty())
    }

    @Test fun staysReadyAndCracksAsSoonAsSomeoneArrives() {
        repeat(200) { system.update(world, 0.016f) }
        assertTrue(whip().timer <= 0f)
        val e = enemyAt(560f, 505f)
        system.update(world, 0.016f)
        assertTrue(hurt(e))
        assertEquals(1, lashes().size)
    }

    @Test fun cracksTowardEachOfTheFourSides() {
        val right = enemyAt(600f, 500f); val left = enemyAt(400f, 500f)
        val down  = enemyAt(500f, 600f); val up   = enemyAt(500f, 400f)
        system.update(world, 0.016f)
        listOf(right, left, down, up).forEach { assertTrue(hurt(it)) }
        assertEquals(4, lashes().size)
        val angles = lashes().map { Math.round(Math.atan2(it.vy.toDouble(), it.vx.toDouble()) / (Math.PI / 2)).toInt() }.toSet()
        assertEquals(4, angles.size)
    }

    @Test fun onlyTheLaneWithAnEnemyCracks() {
        val up    = enemyAt(500f, 420f)
        val other = enemyAt(700f, 700f)            // diagonal: in no lane
        system.update(world, 0.016f)
        assertTrue(hurt(up))
        assertTrue(!hurt(other))
        assertEquals(1, lashes().size)
    }

    @Test fun cooldownApplies() {
        val e = enemyAt(570f, 500f)
        system.update(world, 0.016f)
        val after = world.healths[e]!!.current
        repeat(30) { system.update(world, 0.016f) }       // 0.5 s < cooldown
        assertEquals(after, world.healths[e]!!.current)
    }

    @Test fun lashesDisappearAfterTheyPlay() {
        EntityFactory.createWhipLash(world, 0f, 0f, 0f, 120f, 0.1f)
        val xp = ExperienceSystem()
        repeat(40) { xp.processEvents(world, mutableListOf(), 0.016f); world.flushDestroyed() }
        assertTrue(lashes().isEmpty())
    }
}
