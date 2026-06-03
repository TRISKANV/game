package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType

/**
 * Sealed hierarchy for game events produced by ECS systems.
 * Events are collected each frame and processed by ExperienceSystem
 * and the GameEngine state machine.
 */
sealed class GameEvent {
    data class EnemyKilled(
        val entityId  : Int,
        val type      : EnemyType,
        val xp        : Int,
        val coinChance: Float
    ) : GameEvent()

    data class PlayerHit(val damage: Int) : GameEvent()

    object PlayerDied : GameEvent()

    data class PickupCollected(
        val entityId : Int,
        val type     : PickupType,
        val value    : Int
    ) : GameEvent()

    data class LevelUp(val newLevel: Int) : GameEvent()

    object BossSpawned : GameEvent()
}
