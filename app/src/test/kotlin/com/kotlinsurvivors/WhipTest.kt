package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.ecs.systems.WeaponSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhipTest {

    private fun setup(): Pair<World, Int> {
        val world = World()
        val pid = EntityFactory.createPlayer(world, 500f, 500f)
        return world to pid
    }

    @Test fun firstLashHitsBothSidesAndDrawsTwoCables() {
        val (world, _) = setup()
        val right = EntityFactory.createEnemy(world, EnemyType.BASIC, 600f, 500f)
        val left  = EntityFactory.createEnemy(world, EnemyType.BASIC, 400f, 500f)
        val above = EntityFactory.createEnemy(world, EnemyType.BASIC, 500f, 300f)
        val hpBefore = world.healths[above]!!.current

        WeaponSystem().update(world, 0.016f)
        world.flushDestroyed()

        assertTrue(world.healths[right]!!.current < world.healths[right]!!.max)
        assertTrue(world.healths[left]!!.current  < world.healths[left]!!.max)
        assertEquals("an enemy far above is out of reach", hpBefore, world.healths[above]!!.current)

        val lashes = world.particles.values.filter { it.type == ParticleType.LASH }
        assertEquals(2, lashes.size)
        assertEquals(setOf(1f, -1f), lashes.map { it.vx }.toSet())
        assertTrue("the second lash starts later", lashes.map { it.maxLifetime }.distinct().size == 2)
    }

    @Test fun lashesDisappearAfterTheyPlay() {
        val (world, _) = setup()
        EntityFactory.createWhipLash(world, 0f, 0f, 1f, 120f, 0.1f)
        val system = com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem()
        repeat(40) { system.processEvents(world, mutableListOf(), 0.016f); world.flushDestroyed() }
        assertTrue(world.particles.values.none { it.type == ParticleType.LASH })
    }
}
