package com.kotlinsurvivors

import com.kotlinsurvivors.engine.PermanentBonuses
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermanentBonusesTest {

    private lateinit var world: World
    private var playerId = -1

    @Before
    fun setup() {
        world    = World()
        playerId = EntityFactory.createPlayer(world, 0f, 0f)
    }

    private val player get() = world.players[playerId]!!
    private val health get() = world.healths[playerId]!!

    @Test
    fun `no levels leaves the player untouched`() {
        val applied = PermanentBonuses.applyTo(world, playerId, emptyMap())
        assertTrue(applied.isEmpty())
        assertEquals(100, health.max)
        assertEquals(220f, player.speed, 0.001f)
        assertEquals(1f, player.damageMultiplier, 0.001f)
    }

    @Test
    fun `health raises max and fills current`() {
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_hp" to 3))
        assertEquals(160, health.max)
        assertEquals(160, health.current)
    }

    @Test
    fun `speed damage and area scale per level`() {
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_speed" to 4, "perm_damage" to 5, "perm_area" to 5))
        assertEquals(220f * 1.20f, player.speed, 0.01f)
        assertEquals(1.5f, player.damageMultiplier, 0.001f)
        assertEquals(1.4f, player.areaMultiplier, 0.001f)
    }

    @Test
    fun `cooldown reduction shortens the weapon timer by the stated percentage`() {
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_cooldown" to 4))
        // 1.0 s cooldown with -20% becomes 0.8 s.
        assertEquals(0.8f, 1.0f / player.attackSpeedMultiplier, 0.001f)
    }

    @Test
    fun `maximum cooldown level halves cooldowns`() {
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_cooldown" to 10))
        assertEquals(0.5f, 1.0f / player.attackSpeedMultiplier, 0.001f)
    }

    @Test
    fun `armor regen and magnet`() {
        val magnet = player.magnetRadius
        val pickup = player.pickupRadius
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_armor" to 3, "perm_regen" to 4, "perm_magnet" to 5))
        assertEquals(3, player.armor)
        assertEquals(2f, player.regenPerSecond, 0.001f)
        assertEquals(magnet * 2f, player.magnetRadius, 0.01f)
        assertEquals(pickup * 2f, player.pickupRadius, 0.01f)
    }

    @Test
    fun `luck and xp set their multipliers`() {
        PermanentBonuses.applyTo(world, playerId, mapOf("perm_luck" to 5, "perm_xp" to 10))
        assertEquals(1.25f, player.coinDropMultiplier, 0.001f)
        assertEquals(2.0f, player.xpMultiplier, 0.001f)
    }

    @Test
    fun `levels above the maximum are clamped`() {
        val applied = PermanentBonuses.applyTo(world, playerId, mapOf("perm_armor" to 99, "perm_cooldown" to 500))
        assertEquals(5, player.armor)
        assertEquals(5, applied["perm_armor"])
        assertEquals(10, applied["perm_cooldown"])
    }

    @Test
    fun `negative levels and unknown ids are ignored`() {
        val applied = PermanentBonuses.applyTo(world, playerId, mapOf("perm_hp" to -3, "perm_unknown" to 4))
        assertTrue(applied.isEmpty())
        assertEquals(100, health.max)
    }

    @Test
    fun `every shop upgrade has an effect`() {
        // Buying an upgrade must change something about the player.
        for (definition in PermanentUpgrades.all) {
            val fresh   = World()
            val id      = EntityFactory.createPlayer(fresh, 0f, 0f)
            val before  = fresh.players[id]!!.copy()
            val hpBefore = fresh.healths[id]!!.max
            PermanentBonuses.applyTo(fresh, id, mapOf(definition.id to 1))
            val changed = fresh.players[id]!! != before || fresh.healths[id]!!.max != hpBefore
            assertTrue("${definition.id} changed nothing", changed)
        }
    }
}

class MultiplierSystemTest {

    private lateinit var world: World
    private lateinit var system: ExperienceSystem
    private var playerId = -1

    @Before
    fun setup() {
        world    = World()
        system   = ExperienceSystem()
        playerId = EntityFactory.createPlayer(world, 0f, 0f)
    }

    @Test
    fun `xp multiplier increases experience gained`() {
        world.players[playerId]!!.xpMultiplier = 1.5f
        system.processEvents(world, mutableListOf(GameEvent.PickupCollected(99, PickupType.EXPERIENCE_SMALL, 4)))
        assertEquals(6, world.players[playerId]!!.experience)
    }

    @Test
    fun `coin drop multiplier raises the chance above the enemy value`() {
        // Chance 0.5 with a multiplier of 2.0 is certain, so every kill drops coins.
        world.players[playerId]!!.coinDropMultiplier = 2f
        repeat(20) {
            val id = EntityFactory.createEnemy(world, EnemyType.BASIC, 10f, 10f)
            world.healths[id]!!.isDead = true
            val before = world.getPickupEntities().size
            system.processEvents(world, mutableListOf(GameEvent.EnemyKilled(id, EnemyType.BASIC, 1, 0.5f)))
            world.flushDestroyed()
            val coins = world.pickups.values.count { it.type == PickupType.COIN || it.type == PickupType.COIN_BAG }
            assertTrue("kill $it dropped no coin (pickups before=$before)", coins > 0)
            world.pickups.keys.toList().forEach { world.destroyEntity(it) }
            world.flushDestroyed()
        }
    }

    @Test
    fun `max health upgrades raise the maximum`() {
        val health = world.healths[playerId]!!
        health.max += 30
        health.current += 30
        assertEquals(130, health.max)
        assertEquals(1f, health.percentage, 0.001f)
    }
}
