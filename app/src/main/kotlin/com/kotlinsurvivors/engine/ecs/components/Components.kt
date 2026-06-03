package com.kotlinsurvivors.engine.ecs.components

import androidx.compose.ui.geometry.Offset

/**
 * Base marker interface for all ECS Components.
 * Components are pure data containers — no logic.
 */
interface Component

// ─────────────────────────────────────────────
// TRANSFORM
// ─────────────────────────────────────────────

/**
 * World-space position and rotation of an entity.
 */
data class TransformComponent(
    var x: Float = 0f,
    var y: Float = 0f,
    var rotation: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f
) : Component {
    val position: Offset get() = Offset(x, y)
}

// ─────────────────────────────────────────────
// VELOCITY
// ─────────────────────────────────────────────

/**
 * Linear velocity in world units per second.
 */
data class VelocityComponent(
    var vx: Float = 0f,
    var vy: Float = 0f
) : Component

// ─────────────────────────────────────────────
// HEALTH
// ─────────────────────────────────────────────

/**
 * Health points and damage state.
 */
data class HealthComponent(
    var current: Int,
    val max: Int,
    var isDead: Boolean = false,
    var invincibleTimer: Float = 0f,       // seconds of invincibility after hit
    var invincibleDuration: Float = 0.15f  // default iframes duration
) : Component {
    val percentage: Float get() = current.toFloat() / max.toFloat()
    val isAlive: Boolean get() = !isDead
}

// ─────────────────────────────────────────────
// COLLIDER
// ─────────────────────────────────────────────

enum class ColliderLayer {
    PLAYER, ENEMY, PROJECTILE, PICKUP, BOSS
}

/**
 * Circular collider used for broad-phase collision detection.
 * Using circles avoids expensive polygon collision and works well
 * for top-down games.
 */
data class ColliderComponent(
    var radius: Float,
    val layer: ColliderLayer,
    val collidesWithLayers: Set<ColliderLayer>
) : Component

// ─────────────────────────────────────────────
// RENDER
// ─────────────────────────────────────────────

enum class RenderShape { CIRCLE, RECT, TRIANGLE }

/**
 * Visual representation data for the Canvas renderer.
 * All visuals are generated procedurally — no external assets required.
 */
data class RenderComponent(
    val shape: RenderShape = RenderShape.CIRCLE,
    var color: Long = 0xFFFFFFFF,
    var secondaryColor: Long = 0xFFAAAAAA,
    var width: Float = 32f,
    var height: Float = 32f,
    var glowRadius: Float = 0f,
    var glowColor: Long = 0x00FFFFFF,
    var alpha: Float = 1f,
    var animFrame: Int = 0,
    var animTimer: Float = 0f,
    var animSpeed: Float = 8f, // frames per second
    var flashTimer: Float = 0f // white flash on hit
) : Component

// ─────────────────────────────────────────────
// PLAYER
// ─────────────────────────────────────────────

/**
 * Player-specific stats and progression data.
 */
data class PlayerComponent(
    var speed: Float = 220f,
    var level: Int = 1,
    var experience: Int = 0,
    var experienceToNextLevel: Int = 10,
    var coins: Int = 0,
    var damageMultiplier: Float = 1.0f,
    var attackSpeedMultiplier: Float = 1.0f,
    var projectileSpeedMultiplier: Float = 1.0f,
    var projectileSizeMultiplier: Float = 1.0f,
    var areaMultiplier: Float = 1.0f,
    var durationMultiplier: Float = 1.0f,
    var criticalChance: Float = 0.05f,
    var criticalMultiplier: Float = 2.0f,
    var magnetRadius: Float = 150f,
    var pickupRadius: Float = 80f,
    var armor: Int = 0,
    var lifeSteal: Float = 0f,
    var regenPerSecond: Float = 0f,
    var killCount: Int = 0,
    var survivalTime: Float = 0f
) : Component

// ─────────────────────────────────────────────
// AI / ENEMY
// ─────────────────────────────────────────────

enum class EnemyType {
    BASIC, FAST, TANK, RANGED, EXPLODER, SWARM, BOSS_SHADOW, BOSS_GOLEM, BOSS_NECROMANCER
}

enum class AIState {
    CHASE, WANDER, ATTACK, FLEE, IDLE, CHARGE, CIRCLE_STRAFE
}

/**
 * Enemy AI data and configuration.
 */
data class EnemyComponent(
    val type: EnemyType,
    var aiState: AIState = AIState.CHASE,
    var detectionRadius: Float = 800f,
    var attackRadius: Float = 50f,
    var attackDamage: Int = 10,
    var attackCooldown: Float = 1.0f,
    var attackTimer: Float = 0f,
    var wanderTimer: Float = 0f,
    var wanderDirection: Float = 0f,
    var stateTimer: Float = 0f,
    var experienceValue: Int = 5,
    var coinDropChance: Float = 0.1f,
    var coinDropAmount: IntRange = 1..3
) : Component

// ─────────────────────────────────────────────
// WEAPON
// ─────────────────────────────────────────────

enum class WeaponType {
    MAGIC_WAND,      // single projectile toward nearest enemy
    AXE,             // arc projectile, returns
    KNIFE,           // fast forward projectile
    CROSS,           // four-directional projectile
    FIRE_WAND,       // piercing fire projectile
    LIGHTNING,       // chain lightning between enemies
    GARLIC,          // aura damage around player
    SANTA_WATER,     // drops holy water pools
    WHIP,            // horizontal melee sweep
    BIBLE,           // orbiting projectiles
}

/**
 * Weapon data attached to player entity.
 */
data class WeaponComponent(
    val type: WeaponType,
    var damage: Int,
    var cooldown: Float,       // seconds between shots
    var timer: Float = 0f,
    var level: Int = 1,
    var projectileCount: Int = 1,
    var projectileSpeed: Float = 400f,
    var projectileSize: Float = 12f,
    var piercing: Int = 1,     // how many enemies a projectile can hit
    var duration: Float = 2f,  // projectile lifetime in seconds
    var area: Float = 1f,      // area multiplier
    var knockback: Float = 50f
) : Component

// ─────────────────────────────────────────────
// PROJECTILE
// ─────────────────────────────────────────────

/**
 * Data for active projectiles in flight.
 */
data class ProjectileComponent(
    val weaponType: WeaponType,
    var damage: Int,
    var lifetime: Float,      // seconds remaining
    var piercingLeft: Int,
    var hitEntities: MutableSet<Int> = mutableSetOf(), // entity IDs already hit
    var isAreaEffect: Boolean = false,
    var areaRadius: Float = 0f,
    var isCritical: Boolean = false,
    var knockback: Float = 50f,
    var homing: Boolean = false,
    var homingTarget: Int = -1
) : Component

// ─────────────────────────────────────────────
// EXPERIENCE ORB / PICKUP
// ─────────────────────────────────────────────

enum class PickupType {
    EXPERIENCE_SMALL, EXPERIENCE_MEDIUM, EXPERIENCE_LARGE,
    COIN, COIN_BAG, HEALTH_SMALL, HEALTH_LARGE, MAGNET, BOMB
}

/**
 * Data for collectible items on the ground.
 */
data class PickupComponent(
    val type: PickupType,
    var value: Int = 1,
    var lifetime: Float = 15f, // disappears after 15 seconds
    var magnetized: Boolean = false,
    var magnetSpeed: Float = 0f
) : Component

// ─────────────────────────────────────────────
// DAMAGE NUMBER (floating text)
// ─────────────────────────────────────────────

/**
 * Floating damage number visual effect.
 */
data class DamageNumberComponent(
    var value: Int,
    var lifetime: Float = 0.8f,
    var maxLifetime: Float = 0.8f,
    var vy: Float = -120f,
    var isCritical: Boolean = false
) : Component

// ─────────────────────────────────────────────
// PARTICLE EFFECT
// ─────────────────────────────────────────────

enum class ParticleType {
    BLOOD, EXPLOSION, SPARK, EXPERIENCE, COIN, LEVEL_UP, HEAL
}

/**
 * Particle visual effect component.
 */
data class ParticleComponent(
    val type: ParticleType,
    var lifetime: Float,
    val maxLifetime: Float,
    var vx: Float,
    var vy: Float,
    var size: Float,
    var color: Long,
    var drag: Float = 0.95f
) : Component

// ─────────────────────────────────────────────
// AURA WEAPON
// ─────────────────────────────────────────────

/**
 * Aura-type weapon that damages nearby enemies (e.g. Garlic).
 */
data class AuraComponent(
    val weaponType: WeaponType,
    var radius: Float,
    var damage: Int,
    var tickRate: Float, // damage ticks per second
    var tickTimer: Float = 0f
) : Component

// ─────────────────────────────────────────────
// ORBITAL WEAPON
// ─────────────────────────────────────────────

/**
 * Orbiting projectile weapon (e.g. Bible).
 */
data class OrbitalComponent(
    val weaponType: WeaponType,
    var orbitRadius: Float,
    var orbitSpeed: Float, // radians per second
    var currentAngle: Float,
    var damage: Int,
    var size: Float,
    var hitCooldown: Float = 0.5f,
    var hitTimer: Float = 0f,
    val hitEntities: MutableSet<Int> = mutableSetOf()
) : Component

// ─────────────────────────────────────────────
// BOSS
// ─────────────────────────────────────────────

/**
 * Boss-specific AI and phase data.
 */
data class BossComponent(
    val type: EnemyType,
    var phase: Int = 1,
    var maxPhases: Int = 3,
    var phaseThresholds: List<Float> = listOf(0.66f, 0.33f),
    var specialAttackTimer: Float = 0f,
    var specialAttackCooldown: Float = 5f,
    var enrageTimer: Float = 0f,
    var isEnraged: Boolean = false
) : Component

// ─────────────────────────────────────────────
// TAG COMPONENTS (zero-data markers)
// ─────────────────────────────────────────────

/** Marks the entity as the current player. */
object PlayerTag : Component

/** Marks entity for removal at end of frame. */
object DestroyedTag : Component

/** Marks entity as active in the current wave. */
object ActiveTag : Component
