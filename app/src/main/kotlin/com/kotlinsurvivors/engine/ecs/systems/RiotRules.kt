package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.components.EnemyComponent
import com.kotlinsurvivors.engine.ecs.components.HealthComponent

/**
 * The riot officer's shield. It is not a second health bar: the officer's health is his body plus the
 * shield on top, so every kind of damage (shots, auras, bombs) already lands on the shield first.
 * [settle] runs once a frame and keeps a single hit from going through the shield into the body.
 */
object RiotRules {
    const val WALK_SPEED = 52f      // behind the shield he advances slowly, like a wall
    const val RUN_SPEED  = 168f     // without it he runs
    private const val SHIELD_BASE = 90

    fun shieldHp(multiplier: Float): Int = (SHIELD_BASE * multiplier).toInt().coerceAtLeast(1)

    /** True on the frame the shield breaks. While it holds, he takes no damage to his body and does no harm. */
    fun settle(enemy: EnemyComponent, health: HealthComponent): Boolean {
        if (!enemy.shieldUp || health.isDead) return false
        if (health.current > enemy.bodyHp) return false
        health.current = health.current.coerceAtLeast(enemy.bodyHp).coerceAtLeast(1)
        enemy.shieldUp = false
        return true
    }
}
