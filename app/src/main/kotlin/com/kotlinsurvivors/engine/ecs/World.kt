package com.kotlinsurvivors.engine.ecs

import com.kotlinsurvivors.engine.ecs.components.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central ECS World.
 *
 * Snapshot design — THE BUG WE FIXED:
 *   Previous version returned the SAME ArrayList reference from every
 *   snapshot call. When getEnemySnapshot() was called 9× per frame across
 *   MovementSystem, WeaponSystem, CollisionSystem and ExperienceSystem,
 *   the second caller's .clear() would corrupt the first caller's iteration
 *   → ConcurrentModificationException or silent data corruption → crash.
 *
 * Fix: snapshot methods now COPY into a fresh list each call.
 *   To avoid per-call allocation we keep ONE write buffer per method
 *   and copy it into a thread-local read list that callers hold safely.
 *   Simpler alternative used here: return a new ArrayList copy every call.
 *   This is one allocation per call (~9 per frame for enemies) but each
 *   is tiny and short-lived — far less pressure than the previous Pair
 *   explosion (150k+/sec). Android's GC handles this comfortably.
 *
 *   If profiling shows this is still too much, the right fix is to give
 *   each system its own private buffer field and pass it to World.fillXxx()
 *   — but that adds coupling. The copy approach is safe and correct first.
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

    // ── Safe snapshot methods ──────────────────────────────────────────────
    // Each call returns an INDEPENDENT copy of the key set.
    // This prevents the "shared mutable list" bug where a second caller
    // calling .clear() corrupts the first caller's iteration.
    //
    // Cost: one ArrayList allocation per call. With 9 enemy snapshot calls
    // per frame at 60fps that's ~540 small allocations/sec — acceptable.
    // The previous Pair bug was 150,000+/sec.

    fun getEnemySnapshot(): List<Int>        = ArrayList(enemies.keys)
    fun getProjectileSnapshot(): List<Int>   = ArrayList(projectiles.keys)
    fun getPickupSnapshot(): List<Int>       = ArrayList(pickups.keys)
    fun getParticleSnapshot(): List<Int>     = ArrayList(particles.keys)
    fun getDamageNumberSnapshot(): List<Int> = ArrayList(damageNumbers.keys)
    fun getVelocitySnapshot(): List<Int>     = ArrayList(velocities.keys)
    fun getTransformSnapshot(): List<Int>    = ArrayList(transforms.keys)
    fun getRenderSnapshot(): List<Int>       = ArrayList(renders.keys)
    fun getHealthSnapshot(): List<Int>       = ArrayList(healths.keys)

    // ── Legacy read-only accessors for the renderer only ──────────────────
    // The renderer never mutates the world so live views are safe there.
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
