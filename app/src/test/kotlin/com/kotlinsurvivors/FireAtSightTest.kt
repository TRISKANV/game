package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.WeaponSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shooting weapons only fire when an enemy is in sight. */
class FireAtSightTest {

    private fun shots(world: World) = world.getProjectileSnapshot().size

    private fun playerWithWand(world: World) {
        val pid = EntityFactory.createPlayer(world, 500f, 500f)
        world.weapons[pid]!!.clear()
        world.weapons[pid]!!.add(WeaponComponent(WeaponType.MAGIC_WAND, 5, 0.5f))
    }

    @Test fun nothingIsFiredWithoutAnEnemyAndTheWeaponStaysReady() {
        val world = World(); val system = WeaponSystem()
        playerWithWand(world)
        repeat(120) { system.update(world, 0.05f) }
        assertEquals(0, shots(world))
        EntityFactory.createEnemy(world, EnemyType.BASIC, 700f, 500f)
        system.update(world, 0.05f)
        assertTrue("it fires as soon as one shows up", shots(world) > 0)
    }

    @Test fun anEnemyFarAwayDoesNotCount() {
        val world = World(); val system = WeaponSystem()
        playerWithWand(world)
        EntityFactory.createEnemy(world, EnemyType.BASIC, 500f + 2000f, 500f)
        repeat(120) { system.update(world, 0.05f) }
        assertEquals(0, shots(world))
    }
}
