package com.kotlinsurvivors

import com.kotlinsurvivors.engine.WeaponOffers
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.CollisionSystem
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.engine.ecs.systems.WeaponSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChestTest {

    private val world  = World()
    private val pid    = EntityFactory.createPlayer(world, 500f, 500f)
    private val xp     = ExperienceSystem()
    private val weaponSystem = WeaponSystem()

    private fun chests() = world.pickups.values.filter { it.type == PickupType.CHEST }

    private fun killBoss() {
        val id = EntityFactory.createEnemy(world, EnemyType.BOSS_GOLEM, 100f, 100f)
        world.healths[id]!!.isDead = true
        xp.processEvents(world, mutableListOf(GameEvent.EnemyKilled(id, EnemyType.BOSS_GOLEM, 50, 0f)))
        world.flushDestroyed()
    }

    @Test fun aBossLeavesAChestAboutHalfTheTime() {
        repeat(400) { killBoss() }
        val n = chests().size
        assertTrue("chests dropped: $n of 400", n in 140..260)
    }

    @Test fun normalEnemiesNeverLeaveAChest() {
        repeat(200) {
            val id = EntityFactory.createEnemy(world, EnemyType.TANK, 100f, 100f)
            world.healths[id]!!.isDead = true
            xp.processEvents(world, mutableListOf(GameEvent.EnemyKilled(id, EnemyType.TANK, 5, 0f)))
            world.flushDestroyed()
        }
        assertTrue(chests().isEmpty())
    }

    @Test fun theChestWaitsLongAndIsNotPulledByTheMagnet() {
        val id = EntityFactory.createPickup(world, PickupType.CHEST, 600f, 500f)
        assertTrue(world.pickups[id]!!.lifetime >= 60f)
        world.players[pid]!!.magnetRadius = 400f
        val x0 = world.transforms[id]!!.x
        CollisionSystem().update(world, 1f / 60f, mutableListOf())
        assertFalse(world.pickups[id]!!.magnetized)
        assertEquals(x0, world.transforms[id]!!.x, 0.001f)
    }

    @Test fun touchingTheChestOpensItAndThenItPaysOutOnce() {
        val id = EntityFactory.createPickup(world, PickupType.CHEST, 500f, 500f)
        world.transforms[id]!!.x = 505f; world.transforms[id]!!.y = 500f
        val events = mutableListOf<GameEvent>()
        CollisionSystem().update(world, 1f / 60f, events)
        assertTrue(world.pickups[id]!!.openTimer >= 0f)
        assertTrue(events.none { it is GameEvent.PickupCollected })

        val all = mutableListOf<GameEvent>()
        repeat(80) {                                            // 1.28 s > the 1.1 s animation
            all.addAll(xp.processEvents(world, mutableListOf(), 0.016f))
            world.flushDestroyed()
        }
        val opened = all.count { it is GameEvent.ChestOpened }
        assertEquals(1, opened)
        assertTrue(chests().isEmpty())
    }

    @Test fun xpCurveGrowsFortyFivePercentPerLevel() {
        val player = world.players[pid]!!
        var expected = 10.0
        repeat(6) {
            val orb = EntityFactory.createPickup(world, PickupType.EXPERIENCE_SMALL, 500f, 500f)
            xp.processEvents(world, mutableListOf(GameEvent.PickupCollected(orb, PickupType.EXPERIENCE_SMALL, 1_000_000)), 0.016f)
            expected *= 1.45
            assertEquals("after level ${player.level}", expected.toInt(), player.experienceToNextLevel)
        }
    }

    @Test fun chestOnlyWeaponsAreNeverOfferedOnLevelUp() {
        repeat(200) {
            val offers = WeaponOffers.pickNewWeapons(emptySet())
            assertTrue(offers.none { it.isChestOnly })
        }
        assertEquals(3, WeaponType.values().count { it.isChestOnly })
    }

    // ── The three weapons ──

    private fun give(type: WeaponType, damage: Int, count: Int = 1) {
        world.weapons[pid]!!.clear()
        world.weapons[pid]!!.add(WeaponComponent(type, damage, 99f, projectileCount = count))   // fires once
    }
    private fun enemyAt(x: Float, y: Float) = EntityFactory.createEnemy(world, EnemyType.BASIC, x, y)
    private fun hurt(id: Int) = world.healths[id]!!.current < world.healths[id]!!.max
    private fun effects(t: ParticleType) = world.particles.values.count { it.type == t }

    @Test fun ddosHurtsEveryoneAroundAndSkipsTheFarOnes() {
        give(WeaponType.DDOS, 30)
        val near = listOf(enemyAt(560f, 500f), enemyAt(440f, 520f), enemyAt(500f, 380f))
        val far  = enemyAt(900f, 900f)
        weaponSystem.update(world, 0.016f)
        near.forEach { assertTrue(hurt(it)) }
        assertFalse(hurt(far))
        assertEquals(1, effects(ParticleType.RING))
    }

    @Test fun ddosWaitsWhenNobodyIsClose() {
        give(WeaponType.DDOS, 30)
        enemyAt(900f, 900f)
        repeat(300) { weaponSystem.update(world, 0.016f) }
        assertEquals(0, effects(ParticleType.RING))
    }

    @Test fun zeroDayGoesThroughEveryEnemyInLine() {
        give(WeaponType.ZERO_DAY, 28)
        val a = enemyAt(600f, 500f); val b = enemyAt(750f, 503f); val c = enemyAt(880f, 497f)
        val aside = enemyAt(700f, 620f)
        weaponSystem.update(world, 0.016f)
        listOf(a, b, c).forEach { assertTrue(hurt(it)) }
        assertFalse(hurt(aside))
        assertEquals(1, effects(ParticleType.BEAM))
    }

    @Test fun logicBombExplodesAfterTheFuseAndNotBefore() {
        give(WeaponType.LOGIC_BOMB, 60)
        val target = enemyAt(650f, 500f)
        val friend = enemyAt(700f, 540f)
        weaponSystem.update(world, 0.016f)
        assertEquals(1, world.blasts.size)
        assertFalse(hurt(target))
        repeat(60) { weaponSystem.update(world, 0.016f); world.flushDestroyed() }    // ~1 s, fuse is 1.3 s
        assertFalse("fuse not over", hurt(target))
        repeat(25) { weaponSystem.update(world, 0.016f); world.flushDestroyed() }
        assertTrue(hurt(target)); assertTrue(hurt(friend))
        assertTrue(world.blasts.isEmpty())
    }
}
