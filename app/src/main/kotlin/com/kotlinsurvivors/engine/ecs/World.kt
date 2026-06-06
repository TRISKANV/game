package com.kotlinsurvivors.engine.ecs

import com.kotlinsurvivors.engine.ecs.components.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central ECS World.
 *
 * Key design rules:
 *  1. All snapshot methods clear and reuse pre-allocated ArrayLists — zero
 *     per-frame allocation on the normal path.
 *  2. Map-entry snapshots use IntArray + parallel component arrays instead
 *     of Pair<Int,T> to completely eliminate object allocation.
 *  3. Live-view accessors (getEnemyEntities etc.) are kept only for the
 *     read-only renderer — never use them in systems that mutate the world.
 */
class World {

    private val nextId       = AtomicInteger(1)
    private val freeIds      = ArrayDeque<Int>(256)
    private val liveEntities = HashSet<Int>(1024)

    val transforms    = HashMap<Int, TransformComponent>(1024)
    val velocities    = HashMap<Int, VelocityComponent>(1024)
    val healths       = HashMap<Int, HealthComponent>(512)
    val colliders     = HashMap<Int, ColliderComponent>(512)
    val renders       = HashMap<Int, RenderComponent>(1024)
    val players       = HashMap<Int, PlayerComponent>(2)
    val enemies       = HashMap<Int, EnemyComponent>(512)
    val weapons       = HashMap<Int, MutableList<WeaponComponent>>(8)
    val projectiles   = HashMap<Int, ProjectileComponent>(256)
    val pickups       = HashMap<Int, PickupComponent>(256)
    val damageNumbers = HashMap<Int, DamageNumberComponent>(64)
    val particles     = HashMap<Int, ParticleComponent>(512)
    val auras         = HashMap<Int, MutableList<AuraComponent>>(8)
    val orbitals      = HashMap<Int, MutableList<OrbitalComponent>>(8)
    val bosses        = HashMap<Int, BossComponent>(4)
    val playerTags    = HashSet<Int>(2)
    val destroyedTags = HashSet<Int>(128)

    // ── Zero-allocation snapshot buffers ───────────────────────────────────
    // Each buffer is cleared and refilled in-place every call.
    // Using plain ArrayList<Int> for ID-only snapshots (no boxing beyond
    // what HashMap.keys already has).

    private val _enemyIds      = ArrayList<Int>(512)
    private val _projectileIds = ArrayList<Int>(256)
    private val _pickupIds     = ArrayList<Int>(256)
    private val _particleIds   = ArrayList<Int>(512)
    private val _damageNumIds  = ArrayList<Int>(64)
    private val _velocityIds   = ArrayList<Int>(1024)
    private val _transformIds  = ArrayList<Int>(1024)
    private val _renderIds     = ArrayList<Int>(1024)
    private val _healthIds     = ArrayList<Int>(512)

    // ── Entity lifecycle ────────────────────────────────────────────────────

    fun createEntity(): Int {
        val id = if (freeIds.isNotEmpty()) freeIds.removeFirst() else nextId.getAndIncrement()
        liveEntities.add(id)
        return id
    }

    fun destroyEntity(id: Int) { destroyedTags.add(id) }

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
        transforms.remove(id);    velocities.remove(id)
        healths.remove(id);       colliders.remove(id)
        renders.remove(id);       players.remove(id)
        enemies.remove(id);       weapons.remove(id)
        projectiles.remove(id);   pickups.remove(id)
        damageNumbers.remove(id); particles.remove(id)
        auras.remove(id);         orbitals.remove(id)
        bosses.remove(id);        playerTags.remove(id)
    }

    fun isAlive(id: Int): Boolean = id in liveEntities && id !in destroyedTags
    fun getLiveEntityCount(): Int  = liveEntities.size
    fun getPlayerEntity(): Int     = playerTags.firstOrNull() ?: -1

    // ── Snapshot methods (zero Pair allocation) ────────────────────────────
    // Each method clears the reusable list and adds all current keys.
    // Systems MUST use these instead of .keys to prevent CME.

    fun getEnemySnapshot(): List<Int> {
        _enemyIds.clear(); _enemyIds.addAll(enemies.keys); return _enemyIds
    }
    fun getProjectileSnapshot(): List<Int> {
        _projectileIds.clear(); _projectileIds.addAll(projectiles.keys); return _projectileIds
    }
    fun getPickupSnapshot(): List<Int> {
        _pickupIds.clear(); _pickupIds.addAll(pickups.keys); return _pickupIds
    }
    fun getParticleSnapshot(): List<Int> {
        _particleIds.clear(); _particleIds.addAll(particles.keys); return _particleIds
    }
    fun getDamageNumberSnapshot(): List<Int> {
        _damageNumIds.clear(); _damageNumIds.addAll(damageNumbers.keys); return _damageNumIds
    }
    fun getVelocitySnapshot(): List<Int> {
        _velocityIds.clear(); _velocityIds.addAll(velocities.keys); return _velocityIds
    }
    fun getTransformSnapshot(): List<Int> {
        _transformIds.clear(); _transformIds.addAll(transforms.keys); return _transformIds
    }
    fun getRenderSnapshot(): List<Int> {
        _renderIds.clear(); _renderIds.addAll(renders.keys); return _renderIds
    }
    fun getHealthSnapshot(): List<Int> {
        _healthIds.clear(); _healthIds.addAll(healths.keys); return _healthIds
    }

    // ── Map-entry iteration helpers (iterate by ID, look up component) ─────
    // These replace the Pair-allocating getXxxMapSnapshot() methods.
    // Pattern: for (id in world.getPickupSnapshot()) { val c = world.pickups[id] ?: continue }

    // No Pair snapshots needed anymore — all callers updated to use ID snapshots.

    // ── Legacy read-only accessors for the renderer only ──────────────────
    fun getEnemyEntities(): Set<Int>        = enemies.keys
    fun getProjectileEntities(): Set<Int>   = projectiles.keys
    fun getPickupEntities(): Set<Int>       = pickups.keys
    fun getParticleEntities(): Set<Int>     = particles.keys
    fun getDamageNumberEntities(): Set<Int> = damageNumbers.keys

    fun clear() {
        liveEntities.clear(); freeIds.clear(); destroyedTags.clear()
        transforms.clear();   velocities.clear(); healths.clear()
        colliders.clear();    renders.clear();    players.clear()
        enemies.clear();      weapons.clear();    projectiles.clear()
        pickups.clear();      damageNumbers.clear(); particles.clear()
        auras.clear();        orbitals.clear();   bosses.clear()
        playerTags.clear()
    }
}
