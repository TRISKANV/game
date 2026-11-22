package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.components.WeaponComponent
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import kotlin.random.Random

/** What a boss chest gives, decided the moment it is touched and handed over when the player has seen it. */
data class ChestReward(
    val kind: Kind,
    /** The weapon given or improved; null when the chest only heals. */
    val weapon: WeaponType?,
    /** Level the weapon ends up at: 1 for a new one. */
    val level: Int
) {
    enum class Kind {
        /** An exclusive weapon the player did not have. */
        NEW_WEAPON,
        /** The player has them all: one of them gets a level. */
        LEVEL_UP,
        /** Everything is at the top: full health instead. */
        HEAL
    }
}

/** The rules for what a boss chest gives. No game state is touched here, so it can be tested alone. */
object ChestRules {

    /**
     * One of the chest-only weapons the player does not have yet; once he has them all, one level
     * more for one of them; if every one is at [maxLevel], a full heal.
     */
    fun roll(owned: List<WeaponComponent>, maxLevel: Int, random: Random = Random.Default): ChestReward {
        val have = owned.map { it.type }.toSet()
        val unowned = WeaponType.values().filter { it.isChestOnly && it !in have }
        if (unowned.isNotEmpty()) {
            return ChestReward(ChestReward.Kind.NEW_WEAPON, unowned[random.nextInt(unowned.size)], 1)
        }
        val upgradable = owned.filter { it.type.isChestOnly && it.level < maxLevel }
        if (upgradable.isNotEmpty()) {
            val weapon = upgradable[random.nextInt(upgradable.size)]
            return ChestReward(ChestReward.Kind.LEVEL_UP, weapon.type, weapon.level + 1)
        }
        return ChestReward(ChestReward.Kind.HEAL, null, 0)
    }
}
