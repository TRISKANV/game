package com.kotlinsurvivors.engine.ecs

import com.kotlinsurvivors.engine.ecs.components.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central ECS World.
 *
 * All public query methods that return entity sets return SNAPSHOTS (copied lists),
 * never live views of internal HashMap keys. This prevents ConcurrentModificationException
 * when systems create or destroy entities while iterating.
 */
class World {

    private val nextId      = AtomicInteger(1)
    private val freeIds     = ArrayDeque<Int>(256)
    private val liveEntities= HashSet<Int>(1024)

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

    // ── Reusable snapshot lists — allocated once, reused every frame ───────
    // Systems call the snapshot functions below instead of accessing .keys directly.
    private val enemySnapshot     = ArrayList<Int>(512)
    private val projectileSnapshot= ArrayList<Int>(256)
    private val pickupSnapshot    = ArrayList<Int>(256)
    private val particleSnapshot  = ArrayList<Int>(512)
    private val damageNumSnapshot = ArrayList<Int>(64)
    private val transformSnapshot = ArrayList<Int>(1024)
    private val velocitySnapshot  = ArrayList<Int>(1024)
    private val renderSnapshot    = ArrayList<Int>(1024)
    private val healthSnapshot    = ArrayList<Int>(512)
    private val pickupMapSnapshot = ArrayList<Pair<Int, PickupComponent>>(256)
    private val particleMapSnapshot=ArrayList<Pair<Int, ParticleComponent>>(512)
    private val damageMapSnapshot = ArrayList<Pair<Int, DamageNumberComponent>>(64)
    private val projMapSnapshot   = ArrayList<Pair<Int, ProjectileComponent>>(256)
    private val transformMapSnapshot = ArrayList<Pair<Int, TransformComponent>>(1024)
    private val velocityMapSnapshot  = ArrayList<Pair<Int, VelocityComponent>>(1024)
    private val renderMapSnapshot    = ArrayList<Pair<Int, RenderComponent>>(1024)
    private val healthMapSnapshot    = ArrayList<Pair<Int, HealthComponent>>(512)

    // ── Entity creation / destruction ──────────────────────────────────────

    fun createEntity(): Int {
        val id = if (freeIds.isNotEmpty()) freeIds.removeFirst() else nextId.getAndIncrement()
        liveEntities.add(id)
        return id
    }

    fun destroyEntity(id: Int) {
        destroyedTags.add(id)
    }

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

    fun getPlayerEntity(): Int = playerTags.firstOrNull() ?: -1

    // ── Snapshot queries — ALWAYS returns a copy, never a live view ────────
    // This is the critical fix: every system that iterates and may create/destroy
    // entities during the loop must use these snapshot functions.

    fun getEnemySnapshot(): List<Int> {
        enemySnapshot.clear()
        enemySnapshot.addAll(enemies.keys)
        return enemySnapshot
    }

    fun getProjectileSnapshot(): List<Int> {
        projectileSnapshot.clear()
        projectileSnapshot.addAll(projectiles.keys)
        return projectileSnapshot
    }

    fun getPickupSnapshot(): List<Int> {
        pickupSnapshot.clear()
        pickupSnapshot.addAll(pickups.keys)
        return pickupSnapshot
    }

    fun getParticleSnapshot(): List<Int> {
        particleSnapshot.clear()
        particleSnapshot.addAll(particles.keys)
        return particleSnapshot
    }

    fun getDamageNumberSnapshot(): List<Int> {
        damageNumSnapshot.clear()
        damageNumSnapshot.addAll(damageNumbers.keys)
        return damageNumSnapshot
    }

    fun getPickupMapSnapshot(): List<Pair<Int, PickupComponent>> {
        pickupMapSnapshot.clear()
        for ((k, v) in pickups) pickupMapSnapshot.add(Pair(k, v))
        return pickupMapSnapshot
    }

    fun getParticleMapSnapshot(): List<Pair<Int, ParticleComponent>> {
        particleMapSnapshot.clear()
        for ((k, v) in particles) particleMapSnapshot.add(Pair(k, v))
        return particleMapSnapshot
    }

    fun getDamageMapSnapshot(): List<Pair<Int, DamageNumberComponent>> {
        damageMapSnapshot.clear()
        for ((k, v) in damageNumbers) damageMapSnapshot.add(Pair(k, v))
        return damageMapSnapshot
    }

    fun getProjectileMapSnapshot(): List<Pair<Int, ProjectileComponent>> {
        projMapSnapshot.clear()
        for ((k, v) in projectiles) projMapSnapshot.add(Pair(k, v))
        return projMapSnapshot
    }

    fun getTransformMapSnapshot(): List<Pair<Int, TransformComponent>> {
        transformMapSnapshot.clear()
        for ((k, v) in transforms) transformMapSnapshot.add(Pair(k, v))
        return transformMapSnapshot
    }

    fun getVelocityMapSnapshot(): List<Pair<Int, VelocityComponent>> {
        velocityMapSnapshot.clear()
        for ((k, v) in velocities) velocityMapSnapshot.add(Pair(k, v))
        return velocityMapSnapshot
    }

    fun getRenderMapSnapshot(): List<Pair<Int, RenderComponent>> {
        renderMapSnapshot.clear()
        for ((k, v) in renders) renderMapSnapshot.add(Pair(k, v))
        return renderMapSnapshot
    }

    fun getHealthMapSnapshot(): List<Pair<Int, HealthComponent>> {
        healthMapSnapshot.clear()
        for ((k, v) in healths) healthMapSnapshot.add(Pair(k, v))
        return healthMapSnapshot
    }

    // ── Legacy: kept for renderer (read-only, no mutations during render) ──
    fun getEnemyEntities(): Set<Int>        = enemies.keys
    fun getProjectileEntities(): Set<Int>   = projectiles.keys
    fun getPickupEntities(): Set<Int>       = pickups.keys
    fun getParticleEntities(): Set<Int>     = particles.keys
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
