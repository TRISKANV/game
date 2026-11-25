package com.kotlinsurvivors.engine.lab

import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.ecs.systems.EventKind

/**
 * What the tester can ask of the laboratory. The screen sends these to the engine, which applies
 * them on its own thread at the start of the next tick, so nothing outside the engine ever touches
 * the world.
 */
sealed interface LabCommand {
    /** [count] enemies of one kind, in a ring around the player where they can be seen. */
    data class SpawnEnemy(val type: EnemyType, val count: Int = 1) : LabCommand

    /** A mini-boss: a strong, tinted version of an ordinary enemy. */
    data class SpawnMiniBoss(val type: EnemyType) : LabCommand

    /** One pickup on the ground near the player. */
    data class DropPickup(val type: PickupType) : LabCommand

    /** A special wave or mini-boss, started now. */
    data class StartEvent(val kind: EventKind) : LabCommand

    /** The weapon, or one level more of it when the player already has it. */
    data class GiveWeapon(val type: WeaponType) : LabCommand

    /** The player gains one level at once. */
    object LevelUp : LabCommand

    /** Adds this many kills to the combo. */
    data class AddCombo(val kills: Int) : LabCommand

    /** Opens the barrier at the end of the street, as if the first boss had fallen. */
    object OpenExit : LabCommand

    /** Goes to Level 2 now, with the usual fade. */
    object GoToLevel2 : LabCommand

    /** Goes into the police station now (from Level 2), with the usual fade. */
    object GoToStation : LabCommand

    object ClearEnemies : LabCommand
    object HealFull : LabCommand

    data class SetInvincible(val on: Boolean) : LabCommand
}
