package com.kotlinsurvivors.engine.ecs

import android.util.Log
import com.kotlinsurvivors.engine.ecs.components.*
import java.util.concurrent.atomic.AtomicInteger

class World {

    companion object {
        private const val TAG = "KS_World"
    }

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

    fun destroyEntity(id: Int) {
        if (id in destroyedTags) {
            Log.w(TAG, "destroyEntity called twice for id=$id — already in destroyedTags")
        }
        destroyedTags.add(id)
    }

    fun flushDestroyed(): Int {
        val count = destroyedTags.size
        if (count > 200) {
            Log.w(TAG, "flushDestroyed: unusually large batch — count=$count")
        }
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

    // ── Snapshot methods — each call returns an independent copy ───────────

    fun getEnemySnapshot(): List<Int>        = ArrayList(enemies.keys)
    fun getProjectileSnapshot(): List<Int>   = ArrayList(projectiles.keys)
    fun getPickupSnapshot(): List<Int>       = ArrayList(pickups.keys)
    fun getParticleSnapshot(): List<Int>     = ArrayList(particles.keys)
    fun getDamageNumberSnapshot(): List<Int> = ArrayList(damageNumbers.keys)
    fun getVelocitySnapshot(): List<Int>     = ArrayList(velocities.keys)
    fun getTransformSnapshot(): List<Int>    = ArrayList(transforms.keys)
    fun getRenderSnapshot(): List<Int>       = ArrayList(renders.keys)
    fun getHealthSnapshot(): List<Int>       = ArrayList(healths.keys)

    // ── Legacy live views for renderer only ────────────────────────────────
    fun getEnemyEntities(): Set<Int>        = enemies.keys
    fun getProjectileEntities(): Set<Int>   = projectiles.keys
    fun getPickupEntities(): Set<Int>       = pickups.keys
    fun getParticleEntities(): Set<Int>     = particles.keys
    fun getDamageNumberEntities(): Set<Int> = damageNumbers.keys

    // ── Diagnostic string — used in crash logs ─────────────────────────────
    fun getDiagnosticString(): String = buildString {
        append("World{")
        append("live=${liveEntities.size}")
        append(", enemies=${enemies.size}")
        append(", projectiles=${projectiles.size}")
        append(", pickups=${pickups.size}")
        append(", particles=${particles.size}")
        append(", damageNums=${damageNumbers.size}")
        append(", transforms=${transforms.size}")
        append(", velocities=${velocities.size}")
        append(", destroyedPending=${destroyedTags.size}")
        append(", freeIds=${freeIds.size}")
        append(", nextId=${nextId.get()}")
        append("}")
    }

    fun clear() {
        liveEntities.clear(); freeIds.clear(); destroyedTags.clear()
        transforms.clear();   velocities.clear(); healths.clear()
        colliders.clear();    renders.clear();    players.clear()
        enemies.clear();      weapons.clear();    projectiles.clear()
        pickups.clear();      damageNumbers.clear(); particles.clear()
        auras.clear();        orbitals.clear();   bosses.clear()
        playerTags.clear()
        Log.d(TAG, "World cleared")
    }
}
