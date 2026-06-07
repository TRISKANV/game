package com.kotlinsurvivors.engine.rendering

import androidx.compose.ui.geometry.Offset
import com.kotlinsurvivors.engine.ecs.components.*

/**
 * RenderSnapshot
 *
 * An immutable copy of all visual data needed to draw one frame.
 * Created by the ENGINE THREAD at the end of each tick, then handed
 * to the UI thread via GameState. The UI thread only ever sees this
 * snapshot — it never touches the live World HashMap data.
 *
 * This eliminates the ConcurrentModificationException that was caused
 * by the UI thread iterating enemies.keys / projectiles.keys (live views)
 * while the engine thread called enemies.remove() / enemies[id] = ...
 */
data class RenderSnapshot(
    val cameraTargetX : Float = 0f,
    val cameraTargetY : Float = 0f,
    val entities      : List<RenderEntity> = emptyList()
)

enum class RenderEntityKind {
    PLAYER, ENEMY, BOSS, PROJECTILE, PICKUP, PARTICLE, DAMAGE_NUMBER, ORBITAL
}

data class RenderEntity(
    val kind           : RenderEntityKind,
    val x              : Float,
    val y              : Float,
    val rotation       : Float       = 0f,
    // Visual properties
    val shape          : RenderShape = RenderShape.CIRCLE,
    val color          : Long        = 0xFFFFFFFF,
    val secondaryColor : Long        = 0xFFAAAAAA,
    val width          : Float       = 16f,
    val height         : Float       = 16f,
    val glowRadius     : Float       = 0f,
    val glowColor      : Long        = 0x00000000,
    val alpha          : Float       = 1f,
    val flashTimer     : Float       = 0f,
    val isFlashing     : Boolean     = false,
    // Health bar (enemies)
    val hpPercent      : Float       = 1f,
    val showHealthBar  : Boolean     = false,
    // Player-specific
    val invincibleTimer: Float       = 0f,
    val hasAura        : Boolean     = false,
    val auraRadius     : Float       = 0f,
    // Orbital-specific
    val orbitAngle     : Float       = 0f,
    val orbitRadius    : Float       = 0f,
    val orbitSize      : Float       = 0f,
    // Particle-specific
    val lifetime       : Float       = 1f,
    val maxLifetime    : Float       = 1f,
    // Damage number
    val damageValue    : Int         = 0,
    val isCritical     : Boolean     = false
)
