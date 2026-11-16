package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.features.game.domain.model.PermanentUpgrades

/**
 * Applies the shop's permanent upgrades to the player at the start of a run.
 *
 * The numbers match the descriptions in [PermanentUpgrades]. Levels above an
 * upgrade's maximum (for example from a damaged database) are clamped, and
 * ids that are not known are ignored.
 */
object PermanentBonuses {

    private const val HP_PER_LEVEL        = 20
    private const val SPEED_PER_LEVEL     = 0.05f
    private const val DAMAGE_PER_LEVEL    = 0.10f
    private const val COOLDOWN_PER_LEVEL  = 0.05f
    private const val AREA_PER_LEVEL      = 0.08f
    private const val ARMOR_PER_LEVEL     = 1
    private const val REGEN_PER_LEVEL     = 0.5f
    private const val MAGNET_PER_LEVEL    = 0.20f
    private const val COIN_DROP_PER_LEVEL = 0.05f
    private const val XP_PER_LEVEL        = 0.10f

    /**
     * Returns the levels that were actually applied (after clamping),
     * leaving out upgrades at level 0, so callers can log them.
     */
    fun applyTo(world: World, playerId: Int, levels: Map<String, Int>): Map<String, Int> {
        val player = world.players[playerId] ?: return emptyMap()
        val health = world.healths[playerId] ?: return emptyMap()

        val applied = LinkedHashMap<String, Int>()
        for (definition in PermanentUpgrades.all) {
            val level = (levels[definition.id] ?: 0).coerceIn(0, definition.maxLevel)
            if (level == 0) continue
            applied[definition.id] = level

            when (definition.id) {
                "perm_hp" -> {
                    health.max     += HP_PER_LEVEL * level
                    health.current  = health.max
                }
                "perm_speed"    -> player.speed *= 1f + SPEED_PER_LEVEL * level
                "perm_damage"   -> player.damageMultiplier += DAMAGE_PER_LEVEL * level
                // "-5% cooldown" per level: the weapon timer divides by this multiplier.
                "perm_cooldown" -> player.attackSpeedMultiplier /= 1f - COOLDOWN_PER_LEVEL * level
                "perm_area"     -> player.areaMultiplier *= 1f + AREA_PER_LEVEL * level
                "perm_armor"    -> player.armor += ARMOR_PER_LEVEL * level
                "perm_regen"    -> player.regenPerSecond += REGEN_PER_LEVEL * level
                "perm_magnet"   -> {
                    val factor = 1f + MAGNET_PER_LEVEL * level
                    player.magnetRadius *= factor
                    player.pickupRadius *= factor
                }
                "perm_luck"     -> player.coinDropMultiplier += COIN_DROP_PER_LEVEL * level
                "perm_xp"       -> player.xpMultiplier += XP_PER_LEVEL * level
            }
        }
        return applied
    }
}
