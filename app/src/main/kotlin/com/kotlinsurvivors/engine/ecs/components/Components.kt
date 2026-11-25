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
    var max: Int,
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
    var experienceToNextLevel: Int = 25,
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
    var survivalTime: Float = 0f,
    var bossKills: Int = 0,
    var xpMultiplier: Float = 1f,
    var coinDropMultiplier: Float = 1f,
    // Temporary bonuses from the kill streak (see ComboTracker); 1 = none.
    var comboXpMultiplier: Float = 1f,
    var comboAttackSpeedMultiplier: Float = 1f,
    var comboMagnetMultiplier: Float = 1f
) : Component

// ─────────────────────────────────────────────
// AI / ENEMY
// ─────────────────────────────────────────────

enum class EnemyType {
    BASIC, FAST, TANK, RANGED, EXPLODER, SWARM, BOSS_SHADOW, BOSS_GOLEM, BOSS_NECROMANCER,
    /** The guard's police dog: fast and fragile, it bites. Appears on a leash in the K9 patrol. */
    DOG,
    /** A police officer inside the station: walks a beat until he sees the player or the alarm goes, then fights like the guard. */
    COP;

    val isBoss: Boolean
        get() = this == BOSS_SHADOW || this == BOSS_GOLEM || this == BOSS_NECROMANCER

    /** Drones fly: buildings, trees and cars are no obstacle to them. */
    val isFlying: Boolean
        get() = this == FAST
}

/** Where an enemy is in the K9 patrol: [NONE] is an ordinary enemy. */
enum class PatrolState { NONE, WALK, ALERT }

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
    var coinDropAmount: IntRange = 1..3,
    /** True while the enemy is going round an obstacle by the level's flow field instead of straight at the player. */
    var followingPath: Boolean = false,
    var pathCheckTimer: Float = 0f,
    /** A tougher enemy that appears between bosses; it can leave a chest. */
    var isMiniBoss: Boolean = false,
    /** Which special wave this enemy belongs to (0 = none); the wave ends when none with its tag is left. */
    var waveTag: Int = 0,
    /** K9 patrol: see [PatrolState]. */
    var patrol: PatrolState = PatrolState.NONE,
    /** The other half of a guard-and-dog pair while the leash holds, otherwise -1. */
    var partner: Int = -1,
    var patrolDir: Float = 0f,
    var patrolTimer: Float = 0f,
    var alertTimer: Float = 0f,
    /** Next point of the beat (an index into the route the patrol system holds for this officer). */
    var routeIndex: Int = 0,
    /** The officer who shouts when the alarm goes in the station; he gets a speech bubble. */
    var shouting: Boolean = false
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

    // Exclusive weapons: they only come out of a boss chest, never from a level-up.
    DDOS,            // shock wave around the player
    ZERO_DAY,        // long beam through everything in line
    LOGIC_BOMB;      // bomb dropped on the nearest enemy, explodes after a fuse

    /** True for the weapons that only a boss chest can give. */
    val isChestOnly: Boolean
        get() = this == DDOS || this == ZERO_DAY || this == LOGIC_BOMB
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
    COIN, COIN_BAG, HEALTH_SMALL, HEALTH_LARGE, MAGNET, BOMB,
    /** Dropped by bosses; opens by itself when the player touches it. Keep last: pickups.png has one cell per type before it. */
    CHEST
}

/**
 * Pickups that never fly to the player, neither with the magnet radius nor with the magnet pickup:
 * the chest and the energy cans wait where they fell until the player walks over them.
 */
val PickupType.staysPut: Boolean
    get() = this == PickupType.CHEST || this == PickupType.HEALTH_SMALL || this == PickupType.HEALTH_LARGE

/**
 * Data for collectible items on the ground.
 */
data class PickupComponent(
    val type: PickupType,
    var value: Int = 1,
    var lifetime: Float = 15f, // disappears after 15 seconds
    var magnetized: Boolean = false,
    var magnetSpeed: Float = 0f,
    /** Chests only: -1 while closed, otherwise seconds since the player touched it. */
    var openTimer: Float = -1f
) : Component

// ─────────────────────────────────────────────
// DAMAGE NUMBER (floating text)
// ─────────────────────────────────────────────

/**
 * A bomb waiting to go off: when [timeLeft] runs out it damages every enemy within
 * [radius] of its position. Its picture is a particle of type BOMB on the same entity.
 */
data class BlastComponent(
    var timeLeft: Float,
    val damage: Int,
    val radius: Float,
    val isCritical: Boolean
) : Component

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
    BLOOD, EXPLOSION, SPARK, EXPERIENCE, COIN, LEVEL_UP, HEAL,
    /** Not a spark: the picture of the network cable lashing out, see [com.kotlinsurvivors.engine.ecs.EntityFactory.createWhipLash]. */
    LASH,
    /** An expanding ring: [ParticleComponent.size] is the radius it reaches. */
    RING,
    /** A laser beam: velocity holds the direction, size the length. */
    BEAM,
    /** A lit bomb: size is its blast radius, drawn as a warning ring. */
    BOMB
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
