package com.kotlinsurvivors.engine.lab

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.map.LevelMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The parts of the laboratory that only need the world: where things go and what they are.
 * Kept apart from the engine so it can be tested without running a game.
 */
object LabActions {
    /** Distance from the player at which tested enemies appear: on screen, but not on top of him. */
    const val SPAWN_DISTANCE = 230f
    const val MAX_ENEMIES    = 120
    private const val CLEARANCE = 20f
    private const val ANGLE_TRIES = 24

    /**
     * A free spot on a ring of [distance] around ([px], [py]), starting at [startAngle] and turning
     * until one is not inside a wall. Returns null when the whole ring is blocked.
     */
    fun findSpot(map: LevelMap?, px: Float, py: Float, distance: Float, startAngle: Float): Pair<Float, Float>? {
        for (k in 0 until ANGLE_TRIES) {
            val a = startAngle + k * 2f * PI.toFloat() / ANGLE_TRIES
            val x = px + cos(a) * distance
            val y = py + sin(a) * distance
            if (x < CLEARANCE || y < CLEARANCE) continue
            if (map != null) {
                if (x > map.worldSize - CLEARANCE || y > map.worldSize - CLEARANCE) continue
                if (!map.isBoxFree(x, y, CLEARANCE, CLEARANCE)) continue
            }
            return x to y
        }
        return null
    }

    /** Spawns up to [count] enemies of [type] around the player; returns how many appeared. */
    fun spawnEnemies(world: World, map: LevelMap?, type: EnemyType, count: Int, miniBoss: Boolean = false): Int {
        val pt = world.transforms[world.getPlayerEntity()] ?: return 0
        var made = 0
        for (i in 0 until count) {
            if (world.enemies.size >= MAX_ENEMIES) break
            val angle = i * 2f * PI.toFloat() / count
            val spot = findSpot(map, pt.x, pt.y, SPAWN_DISTANCE, angle) ?: continue
            if (miniBoss) EntityFactory.createMiniBoss(world, type, spot.first, spot.second)
            else EntityFactory.createEnemy(world, type, spot.first, spot.second)
            made++
        }
        return made
    }

    /** Puts a pickup near the player. Returns its entity, or -1 when there was no free spot. */
    fun dropPickup(world: World, map: LevelMap?, type: PickupType): Int {
        val pt = world.transforms[world.getPlayerEntity()] ?: return -1
        val spot = findSpot(map, pt.x, pt.y, 90f, 0f) ?: return -1
        return EntityFactory.createPickup(world, type, spot.first, spot.second)
    }

    /** Removes every enemy. Returns how many there were. */
    fun clearEnemies(world: World): Int {
        val ids = ArrayList(world.enemies.keys)
        for (id in ids) world.destroyEntity(id)
        world.flushDestroyed()
        return ids.size
    }

    /** Fills the player's health. */
    fun healFull(world: World) {
        world.healths[world.getPlayerEntity()]?.let { it.current = it.max }
    }

    /**
     * Drops an orb worth exactly the experience missing for the next level, after the player's
     * multipliers, so picking it up gives one level and the usual effects. Returns false when no
     * orb could be placed.
     */
    fun levelUp(world: World, map: LevelMap?): Boolean {
        val player = world.players[world.getPlayerEntity()] ?: return false
        val missing = (player.experienceToNextLevel - player.experience).coerceAtLeast(1)
        val multiplier = (player.xpMultiplier * player.comboXpMultiplier).coerceAtLeast(0.01f)
        val id = dropPickup(world, map, PickupType.EXPERIENCE_LARGE)
        if (id == -1) return false
        world.pickups[id]?.value = ceil(missing / multiplier).toInt()
        return true
    }
}
