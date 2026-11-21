package com.kotlinsurvivors

import com.kotlinsurvivors.engine.AchievementRules
import com.kotlinsurvivors.engine.WeaponOffers
import com.kotlinsurvivors.engine.WeaponUpgrades
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import com.kotlinsurvivors.features.game.domain.model.AchievementDefinitions
import com.kotlinsurvivors.features.game.domain.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WeaponOffersTest {

    @Test
    fun `offers two weapons the player does not own`() {
        val owned = listOf(WeaponType.MAGIC_WAND)
        val offered = WeaponOffers.pickNewWeapons(owned, Random(1))
        assertEquals(2, offered.size)
        assertEquals(offered.size, offered.toSet().size)
        assertTrue(offered.none { it in owned })
    }

    @Test
    fun `nothing is offered at the weapon limit`() {
        val owned = WeaponType.values().take(WeaponOffers.MAX_WEAPONS)
        assertTrue(WeaponOffers.pickNewWeapons(owned).isEmpty())
    }

    @Test
    fun `offers fewer than two when only one weapon is left`() {
        val owned = WeaponType.values().toList().dropLast(1)
        // Limit applies first, so make sure this case is reachable only below the cap.
        if (owned.size >= WeaponOffers.MAX_WEAPONS) {
            assertTrue(WeaponOffers.pickNewWeapons(owned).isEmpty())
        } else {
            assertEquals(1, WeaponOffers.pickNewWeapons(owned).size)
        }
    }

    @Test
    fun `every weapon can be offered eventually`() {
        val seen = HashSet<WeaponType>()
        val random = Random(42)
        repeat(300) { seen += WeaponOffers.pickNewWeapons(listOf(WeaponType.MAGIC_WAND), random) }
        // the chest-only weapons are never offered on level-up
        val expected = WeaponType.values().filter { it != WeaponType.MAGIC_WAND && !it.isChestOnly }.toSet()
        assertEquals(expected, seen)
    }
}

class WeaponUpgradesTest {

    private lateinit var world: World
    private var playerId = -1

    @org.junit.Before
    fun setup() {
        world = World()
        playerId = EntityFactory.createPlayer(world, 0f, 0f)
    }

    private fun weapon(type: WeaponType, level: Int) = WeaponComponent(type = type, damage = 0, cooldown = 999f, level = level)

    @Test
    fun `garlic upgrade raises aura damage and radius`() {
        EntityFactory.addAuraWeapon(world, playerId, WeaponType.GARLIC, 100f, 8, 2f)
        WeaponUpgrades.applyToAttachedComponents(world, playerId, weapon(WeaponType.GARLIC, 2))
        val aura = world.auras[playerId]!!.single()
        assertEquals(10, aura.damage)
        assertEquals(110f, aura.radius, 0.01f)
    }

    @Test
    fun `bible upgrade raises damage of every book`() {
        EntityFactory.addOrbitalWeapon(world, playerId, WeaponType.BIBLE, 3, 120f, 1.8f, 20, 14f)
        WeaponUpgrades.applyToAttachedComponents(world, playerId, weapon(WeaponType.BIBLE, 2))
        val books = world.orbitals[playerId]!!
        assertEquals(3, books.size)
        assertTrue(books.all { it.damage == 24 })
    }

    @Test
    fun `bible gains a book every third level and keeps them evenly spaced`() {
        EntityFactory.addOrbitalWeapon(world, playerId, WeaponType.BIBLE, 3, 120f, 1.8f, 20, 14f)
        WeaponUpgrades.applyToAttachedComponents(world, playerId, weapon(WeaponType.BIBLE, 3))
        val books = world.orbitals[playerId]!!
        assertEquals(4, books.size)
        val step = (2f * Math.PI.toFloat()) / 4
        for (i in 1 until books.size) {
            assertEquals(step, books[i].currentAngle - books[i - 1].currentAngle, 0.001f)
        }
    }

    @Test
    fun `projectile weapons are left alone`() {
        EntityFactory.addAuraWeapon(world, playerId, WeaponType.GARLIC, 100f, 8, 2f)
        WeaponUpgrades.applyToAttachedComponents(world, playerId, weapon(WeaponType.CROSS, 2))
        assertEquals(8, world.auras[playerId]!!.single().damage)
    }

    @Test
    fun `upgrading garlic does not touch other auras`() {
        EntityFactory.addAuraWeapon(world, playerId, WeaponType.GARLIC, 100f, 8, 2f)
        EntityFactory.addAuraWeapon(world, playerId, WeaponType.SANTA_WATER, 50f, 5, 1f)
        WeaponUpgrades.applyToAttachedComponents(world, playerId, weapon(WeaponType.GARLIC, 2))
        val other = world.auras[playerId]!!.first { it.weaponType == WeaponType.SANTA_WATER }
        assertEquals(5, other.damage)
    }
}

class AchievementRulesTest {

    private fun ids(state: GameState) = AchievementRules.satisfiedBy(state).toSet()

    @Test
    fun `a fresh run unlocks nothing`() {
        assertTrue(ids(GameState()).isEmpty())
    }

    @Test
    fun `every rule has a definition and every definition has a rule`() {
        val defined = AchievementDefinitions.all.map { it.id }.toSet()
        assertEquals(defined, AchievementRules.allIds.toSet())
        assertEquals(AchievementRules.allIds.size, AchievementRules.allIds.toSet().size)
    }

    @Test
    fun `survival achievements follow the clock`() {
        assertTrue("ach_survive_1" in ids(GameState(elapsedTime = 60f)))
        assertFalse("ach_survive_1" in ids(GameState(elapsedTime = 59.9f)))
        assertTrue("ach_survive_5" in ids(GameState(elapsedTime = 300f)))
        assertFalse("ach_survive_10" in ids(GameState(elapsedTime = 599f)))
    }

    @Test
    fun `kill and level thresholds`() {
        val s = ids(GameState(killCount = 500, playerLevel = 10))
        assertTrue(s.containsAll(listOf("ach_first_kill", "ach_kill_100", "ach_kill_500", "ach_level_10")))
        assertFalse("ach_kill_1000" in s)
        assertFalse("ach_level_20" in s)
    }

    @Test
    fun `boss slayer needs a real boss kill`() {
        // Surviving a long time with many kills must not be enough.
        assertFalse("ach_boss_kill" in ids(GameState(elapsedTime = 900f, killCount = 400, playerLevel = 15)))
        assertTrue("ach_boss_kill" in ids(GameState(bossKills = 1)))
    }

    @Test
    fun `armory needs six weapons not a high level`() {
        assertFalse("ach_max_weapons" in ids(GameState(playerLevel = 30, weaponCount = 5)))
        assertTrue("ach_max_weapons" in ids(GameState(weaponCount = 6)))
    }

    @Test
    fun `untouchable needs sixty seconds without a hit`() {
        assertFalse("ach_no_damage_60" in ids(GameState(secondsWithoutHit = 59)))
        assertTrue("ach_no_damage_60" in ids(GameState(secondsWithoutHit = 60)))
    }

    @Test
    fun `coins achievement`() {
        assertFalse("ach_coins_500" in ids(GameState(playerCoins = 499)))
        assertTrue("ach_coins_500" in ids(GameState(playerCoins = 500)))
    }
}

class BossKillCountTest {

    private lateinit var world: World
    private lateinit var system: ExperienceSystem
    private var playerId = -1

    @org.junit.Before
    fun setup() {
        world = World()
        system = ExperienceSystem()
        playerId = EntityFactory.createPlayer(world, 0f, 0f)
    }

    private fun kill(type: EnemyType) {
        val id = EntityFactory.createEnemy(world, type, 10f, 10f)
        world.healths[id]!!.isDead = true
        system.processEvents(world, mutableListOf(GameEvent.EnemyKilled(id, type, 5, 0f)))
        world.flushDestroyed()
    }

    @Test
    fun `boss kills are counted separately from normal kills`() {
        kill(EnemyType.BASIC)
        kill(EnemyType.BOSS_GOLEM)
        val player = world.players[playerId]!!
        assertEquals(2, player.killCount)
        assertEquals(1, player.bossKills)
    }

    @Test
    fun `every boss type counts as a boss`() {
        listOf(EnemyType.BOSS_SHADOW, EnemyType.BOSS_GOLEM, EnemyType.BOSS_NECROMANCER).forEach { assertTrue(it.isBoss) }
        assertFalse(EnemyType.TANK.isBoss)
    }
}
