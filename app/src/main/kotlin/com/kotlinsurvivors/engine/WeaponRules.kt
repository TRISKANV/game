package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Which new weapons the level-up screen may offer.
 */
object WeaponOffers {

    const val MAX_WEAPONS = 6
    const val NEW_WEAPON_CHOICES = 2

    /**
     * Picks up to [NEW_WEAPON_CHOICES] weapons the player does not own yet,
     * chosen at random from all of them. Nothing is offered once the player
     * already carries [MAX_WEAPONS].
     */
    fun pickNewWeapons(owned: Collection<WeaponType>, random: Random = Random.Default): List<WeaponType> {
        if (owned.size >= MAX_WEAPONS) return emptyList()
        return WeaponType.values()
            .filter { it !in owned }
            .shuffled(random)
            .take(NEW_WEAPON_CHOICES)
    }
}

/**
 * Upgrade effects for weapons that are not fired as projectiles.
 *
 * Garlic and Bible live in AuraComponent / OrbitalComponent, so raising the
 * level of their WeaponComponent changes nothing by itself. This applies the
 * same kind of growth to the components that actually deal the damage.
 */
object WeaponUpgrades {

    private const val DAMAGE_GROWTH = 1.2f
    private const val AURA_AREA_GROWTH = 1.1f
    private const val EXTRA_ORBITAL_EVERY = 3

    /** Call after [weapon]'s level has been raised. */
    fun applyToAttachedComponents(world: World, playerId: Int, weapon: WeaponComponent) {
        when (weapon.type) {
            WeaponType.GARLIC -> growAuras(world, playerId)
            WeaponType.BIBLE  -> growOrbitals(world, playerId, weapon.level)
            else              -> Unit
        }
    }

    private fun grownDamage(current: Int): Int =
        (current * DAMAGE_GROWTH).roundToInt().coerceAtLeast(current + 1)

    private fun growAuras(world: World, playerId: Int) {
        val auras = world.auras[playerId] ?: return
        for (aura in auras) {
            if (aura.weaponType != WeaponType.GARLIC) continue
            aura.damage  = grownDamage(aura.damage)
            aura.radius *= AURA_AREA_GROWTH
        }
    }

    private fun growOrbitals(world: World, playerId: Int, weaponLevel: Int) {
        val orbitals = world.orbitals[playerId] ?: return
        val books = orbitals.filter { it.weaponType == WeaponType.BIBLE }
        if (books.isEmpty()) return

        for (book in books) book.damage = grownDamage(book.damage)

        if (weaponLevel % EXTRA_ORBITAL_EVERY != 0) return

        // One more book, then spread all of them evenly around the circle.
        val template = books.first()
        EntityFactory.addOrbitalWeapon(
            world, playerId, WeaponType.BIBLE, 1,
            template.orbitRadius, template.orbitSpeed, template.damage, template.size
        )
        val all  = orbitals.filter { it.weaponType == WeaponType.BIBLE }
        val base = template.currentAngle
        val step = (2f * Math.PI.toFloat()) / all.size
        all.forEachIndexed { index, book -> book.currentAngle = base + step * index }
    }
}
