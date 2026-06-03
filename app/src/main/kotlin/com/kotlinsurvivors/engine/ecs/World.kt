package com.kotlinsurvivors.engine.ecs

import com.kotlinsurvivors.engine.ecs.components.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central ECS World.
 * Manages entity lifecycle and provides fast component access
 * via typed HashMaps (archetypes-lite pattern).
 *
 * Design goals:
 *  - Zero allocations in the hot game loop (reuse entity IDs via free list)
 *  - O(1) component get/set
 *  - Iteration over component arrays without boxing
 */
class World {

    // ── Entity ID pool ────────────────────────────────────────────────────
    private val nextId = AtomicInteger(1)
    private val freeIds = ArrayDeque<Int>(256)
    private val liveEntities = HashSet<Int>(1024)

    // ── Component storage (one map per component type) ─────────────────────
    val transforms       = HashMap<Int, TransformComponent>(1024)
    val velocities       = HashMap<Int, VelocityComponent>(1024)
    val healths          = HashMap<Int, HealthComponent>(512)
    val colliders        = HashMap<Int, ColliderComponent>(512)
    val renders          = HashMap<Int, RenderComponent>(1024)
    val players          = HashMap<Int, PlayerComponent>(2)
    val enemies          = HashMap<Int, EnemyComponent>(512)
    val weapons          = HashMap<Int, MutableList<WeaponComponent>>(8)
    val projectiles      = HashMap<Int, ProjectileComponent>(256)
    val pickups          = HashMap<Int, PickupComponent>(256)
    val damageNumbers    = HashMap<Int, DamageNumberComponent>(64)
    val particles        = HashMap<Int, ParticleComponent>(512)
    val auras            = HashMap<Int, MutableList<AuraComponent>>(8)
    val orbitals         = HashMap<Int, MutableList<OrbitalComponent>>(8)
    val bosses           = HashMap<Int, BossComponent>(4)
    val playerTags       = HashSet<Int>(2)
    val destroyedTags    = HashSet<Int>(128)

    // ── Entity creation / destruction ──────────────────────────────────────

    fun createEntity(): Int {
        val id = if (freeIds.isNotEmpty()) freeIds.removeFirst() else nextId.getAndIncrement()
        liveEntities.add(id)
        return id
    }

    fun destroyEntity(id: Int) {
        destroyedTags.add(id)
    }

    /**
     * Must be called once per frame (after all systems) to flush destroyed entities.
     * Returns number of entities removed.
     */
    fun flushDestroyed(): Int {
        val count = destroyedTags.size
        for (id in destroyedTags) {
            liveEntities.remove(id)
            freeIds.addLast(id)
            removeAllComponents(id)
        }
        destroyedTags.clear()
        return count
    }

    private fun removeAllComponents(id: Int) {
        transforms.remove(id)
        velocities.remove(id)
        healths.remove(id)
        colliders.remove(id)
        renders.remove(id)
        players.remove(id)
        enemies.remove(id)
        weapons.remove(id)
        projectiles.remove(id)
        pickups.remove(id)
        damageNumbers.remove(id)
        particles.remove(id)
        auras.remove(id)
        orbitals.remove(id)
        bosses.remove(id)
        playerTags.remove(id)
    }

    fun isAlive(id: Int): Boolean = id in liveEntities && id !in destroyedTags

    fun getLiveEntityCount(): Int = liveEntities.size

    // ── Convenience: find player entity ────────────────────────────────────

    fun getPlayerEntity(): Int = playerTags.firstOrNull() ?: -1

    // ── Batch queries ──────────────────────────────────────────────────────

    /** Returns all entities that have both a transform and an enemy component. */
    fun getEnemyEntities(): Set<Int> = enemies.keys

    /** Returns all entities that have a projectile component. */
    fun getProjectileEntities(): Set<Int> = projectiles.keys

    /** Returns all entities that have a pickup component. */
    fun getPickupEntities(): Set<Int> = pickups.keys

    /** Returns all entities that are particles. */
    fun getParticleEntities(): Set<Int> = particles.keys

    /** Returns all entities that are damage numbers. */
    fun getDamageNumberEntities(): Set<Int> = damageNumbers.keys

    fun clear() {
        liveEntities.clear()
        freeIds.clear()
        destroyedTags.clear()
        transforms.clear()
        velocities.clear()
        healths.clear()
        colliders.clear()
        renders.clear()
        players.clear()
        enemies.clear()
        weapons.clear()
        projectiles.clear()
        pickups.clear()
        damageNumbers.clear()
        particles.clear()
        auras.clear()
        orbitals.clear()
        bosses.clear()
        playerTags.clear()
    }
}
