package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.*
import kotlin.random.Random

/**
 * WeaponSystem
 *
 * Ticks every weapon attached to the player.
 * Each WeaponType has its own firing pattern:
 *  - Projectile weapons: spawn projectile entities
 *  - Aura weapons (Garlic): damage nearby enemies every tick
 *  - Orbital weapons (Bible): rotate projectiles around player
 *  - Area weapons (Santa Water): drop damaging pool entities
 */
class WeaponSystem {

    fun update(world: World, dt: Float) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return

        val player   = world.players[pid]    ?: return
        val pt       = world.transforms[pid] ?: return
        val weapons  = world.weapons[pid]    ?: return

        // Tick invincibility frames
        val health = world.healths[pid]
        if (health != null && health.invincibleTimer > 0f) {
            health.invincibleTimer = (health.invincibleTimer - dt).coerceAtLeast(0f)
        }

        // Tick all weapons
        for (weapon in weapons) {
            weapon.timer -= dt
            if (weapon.timer > 0f) continue
            weapon.timer = weapon.cooldown / player.attackSpeedMultiplier

            val dmg   = (weapon.damage * player.damageMultiplier).toInt()
            val speed = weapon.projectileSpeed * player.projectileSpeedMultiplier
            val size  = weapon.projectileSize  * player.projectileSizeMultiplier
            val area  = weapon.area            * player.areaMultiplier
            val dur   = weapon.duration        * player.durationMultiplier
            val crit  = Random.nextFloat() < player.criticalChance
            val finalDmg = if (crit) (dmg * player.criticalMultiplier).toInt() else dmg

            when (weapon.type) {
                WeaponType.MAGIC_WAND  -> fireMagicWand(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.KNIFE       -> fireKnife(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit, player)
                WeaponType.CROSS       -> fireCross(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.FIRE_WAND   -> fireFireWand(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.AXE         -> fireAxe(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.LIGHTNING   -> fireLightning(world, pt.x, pt.y, finalDmg, size, weapon, area)
                WeaponType.GARLIC      -> { /* handled by AuraSystem */ }
                WeaponType.SANTA_WATER -> fireSantaWater(world, pt.x, pt.y, finalDmg, size, weapon, dur, area)
                WeaponType.WHIP        -> fireWhip(world, pt.x, pt.y, finalDmg, size, weapon, area)
                WeaponType.BIBLE       -> { /* handled by OrbitalSystem */ }
            }
        }

        // Tick aura weapons
        updateAuras(world, pid, dt, player)

        // Tick orbital weapons
        updateOrbitals(world, pid, dt, pt.x, pt.y, player)

        // Tick projectile lifetimes
        updateProjectileLifetimes(world, dt)

        // Update render flash timers and invincibility
        updateRenderTimers(world, dt)

        // Update enemy invincibility frames
        updateEnemyIFrames(world, dt)
    }

    // ── Magic Wand: fires at nearest enemy ────────────────────────────────

    private fun fireMagicWand(
        world: World, ox: Float, oy: Float, dmg: Int,
        speed: Float, size: Float, weapon: WeaponComponent, dur: Float, crit: Boolean
    ) {
        val target = findNearestEnemy(world, ox, oy) ?: return
        val t      = world.transforms[target] ?: return
        val angle  = atan2(t.y - oy, t.x - ox)
        val spread = PI.toFloat() / 16f

        repeat(weapon.projectileCount) { i ->
            val off = if (weapon.projectileCount > 1) (i - (weapon.projectileCount - 1) * 0.5f) * spread else 0f
            val a   = angle + off
            EntityFactory.createProjectile(
                world, ox, oy,
                cos(a) * speed, sin(a) * speed,
                WeaponType.MAGIC_WAND, dmg, size,
                weapon.piercing, dur, crit, weapon.knockback
            )
        }
    }

    // ── Knife: fast, forward-moving ───────────────────────────────────────

    private fun fireKnife(
        world: World, ox: Float, oy: Float, dmg: Int,
        speed: Float, size: Float, weapon: WeaponComponent, dur: Float, crit: Boolean,
        player: PlayerComponent
    ) {
        val target = findNearestEnemy(world, ox, oy)
        val angle  = if (target != null) {
            val t = world.transforms[target]!!
            atan2(t.y - oy, t.x - ox)
        } else 0f

        repeat(weapon.projectileCount) { i ->
            val off = (i - (weapon.projectileCount - 1) * 0.5f) * 0.15f
            EntityFactory.createProjectile(
                world, ox, oy,
                cos(angle + off) * speed * 1.5f,
                sin(angle + off) * speed * 1.5f,
                WeaponType.KNIFE, dmg, size * 0.7f,
                weapon.piercing, dur * 0.6f, crit, weapon.knockback * 0.5f
            )
        }
    }

    // ── Cross: 4-directional ──────────────────────────────────────────────

    private fun fireCross(
        world: World, ox: Float, oy: Float, dmg: Int,
        speed: Float, size: Float, weapon: WeaponComponent, dur: Float, crit: Boolean
    ) {
        val directions = listOf(0f, PI.toFloat() / 2f, PI.toFloat(), 3f * PI.toFloat() / 2f)
        for (angle in directions) {
            repeat(weapon.projectileCount) {
                EntityFactory.createProjectile(
                    world, ox, oy,
                    cos(angle) * speed, sin(angle) * speed,
                    WeaponType.CROSS, dmg, size,
                    weapon.piercing, dur, crit, weapon.knockback
                )
            }
        }
    }

    // ── Fire Wand: piercing fire projectile ───────────────────────────────

    private fun fireFireWand(
        world: World, ox: Float, oy: Float, dmg: Int,
        speed: Float, size: Float, weapon: WeaponComponent, dur: Float, crit: Boolean
    ) {
        val target = findNearestEnemy(world, ox, oy) ?: return
        val t      = world.transforms[target] ?: return
        val angle  = atan2(t.y - oy, t.x - ox)

        repeat(weapon.projectileCount) { i ->
            val off = (i - (weapon.projectileCount - 1) * 0.5f) * 0.1f
            EntityFactory.createProjectile(
                world, ox, oy,
                cos(angle + off) * speed, sin(angle + off) * speed,
                WeaponType.FIRE_WAND, dmg, size,
                weapon.piercing + 2, dur, crit, 0f
            )
        }
    }

    // ── Axe: arc upward, large hitbox ────────────────────────────────────

    private fun fireAxe(
        world: World, ox: Float, oy: Float, dmg: Int,
        speed: Float, size: Float, weapon: WeaponComponent, dur: Float, crit: Boolean
    ) {
        val baseAngle = -PI.toFloat() / 2f  // upward
        val spread    = PI.toFloat() / 5f
        repeat(weapon.projectileCount) { i ->
            val angle = baseAngle + (i - (weapon.projectileCount - 1) * 0.5f) * spread
            EntityFactory.createProjectile(
                world, ox, oy,
                cos(angle) * speed * 0.8f,
                sin(angle) * speed * 0.8f - 60f, // arc: gravity simulated in projectile update
                WeaponType.AXE, dmg, size * 1.4f,
                weapon.piercing, dur * 1.2f, crit, weapon.knockback * 1.5f
            )
        }
    }

    // ── Lightning: instant chain damage ──────────────────────────────────

    private fun fireLightning(
        world: World, ox: Float, oy: Float, dmg: Int,
        size: Float, weapon: WeaponComponent, area: Float
    ) {
        val range = 350f * area
        val enemies = findEnemiesInRadius(world, ox, oy, range)
            .sortedBy { eid ->
                val t = world.transforms[eid]
                if (t != null) {
                    val dx = t.x - ox; val dy = t.y - oy
                    dx * dx + dy * dy
                } else Float.MAX_VALUE
            }
            .take(weapon.projectileCount + (weapon.level - 1) * 2)

        for (eid in enemies) {
            val health = world.healths[eid] ?: continue
            val t      = world.transforms[eid] ?: continue
            health.current = (health.current - dmg).coerceAtLeast(0)
            world.renders[eid]?.flashTimer = 0.12f
            EntityFactory.createDamageNumber(world, t.x, t.y, dmg, false)
            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.SPARK, 6)
            if (health.current <= 0 && !health.isDead) {
                health.isDead = true
            }
        }
    }

    // ── Santa Water: drops area damage pools ─────────────────────────────

    private fun fireSantaWater(
        world: World, ox: Float, oy: Float, dmg: Int,
        size: Float, weapon: WeaponComponent, dur: Float, area: Float
    ) {
        repeat(weapon.projectileCount) {
            val angle  = Random.nextFloat() * 2f * PI.toFloat()
            val dist   = 60f + Random.nextFloat() * 120f
            val tx     = ox + cos(angle) * dist
            val ty     = oy + sin(angle) * dist
            val projId = EntityFactory.createProjectile(
                world, ox, oy,
                cos(angle) * 200f, sin(angle) * 200f,
                WeaponType.SANTA_WATER, dmg, size * area,
                99, 0.4f, false, 0f
            )
            // Will land and become area-of-effect handled in ProjectileSystem
            world.projectiles[projId]?.isAreaEffect = true
            world.projectiles[projId]?.areaRadius   = 48f * area
        }
    }

    // ── Whip: horizontal melee sweep ─────────────────────────────────────

    private fun fireWhip(
        world: World, ox: Float, oy: Float, dmg: Int,
        size: Float, weapon: WeaponComponent, area: Float
    ) {
        val halfW = 120f * area
        val halfH = 40f  * area
        // Damage all enemies in a wide rectangle in front
        for (eid in world.getEnemyEntities()) {
            val t      = world.transforms[eid] ?: continue
            val health = world.healths[eid]    ?: continue
            if (health.isDead) continue
            val dx = abs(t.x - ox)
            val dy = abs(t.y - oy)
            if (dx < halfW && dy < halfH) {
                health.current = (health.current - dmg).coerceAtLeast(0)
                world.renders[eid]?.flashTimer = 0.1f
                EntityFactory.createDamageNumber(world, t.x, t.y, dmg, false)
                EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.BLOOD, 3)
                if (health.current <= 0 && !health.isDead) health.isDead = true
            }
        }
    }

    // ── Aura weapons ──────────────────────────────────────────────────────

    private fun updateAuras(world: World, pid: Int, dt: Float, player: PlayerComponent) {
        val auras = world.auras[pid] ?: return
        val pt    = world.transforms[pid] ?: return

        for (aura in auras) {
            aura.tickTimer -= dt
            if (aura.tickTimer > 0f) continue
            aura.tickTimer = 1f / aura.tickRate

            val radius = aura.radius * player.areaMultiplier
            val dmg    = (aura.damage * player.damageMultiplier).toInt()

            for (eid in world.getEnemyEntities()) {
                val et     = world.transforms[eid] ?: continue
                val health = world.healths[eid]    ?: continue
                if (health.isDead) continue

                val dx = et.x - pt.x
                val dy = et.y - pt.y
                if (dx * dx + dy * dy > radius * radius) continue

                health.current = (health.current - dmg).coerceAtLeast(0)
                world.renders[eid]?.flashTimer = 0.08f
                EntityFactory.createParticleBurst(world, et.x, et.y, ParticleType.SPARK, 2)
                if (health.current <= 0 && !health.isDead) health.isDead = true
            }
        }
    }

    // ── Orbital weapons ───────────────────────────────────────────────────

    private fun updateOrbitals(
        world: World, pid: Int, dt: Float,
        px: Float, py: Float, player: PlayerComponent
    ) {
        val orbitals = world.orbitals[pid] ?: return

        for (orb in orbitals) {
            orb.currentAngle += orb.orbitSpeed * dt
            orb.hitTimer      = (orb.hitTimer - dt).coerceAtLeast(0f)

            val ox = px + cos(orb.currentAngle) * orb.orbitRadius
            val oy = py + sin(orb.currentAngle) * orb.orbitRadius

            val dmg    = (orb.damage * player.damageMultiplier).toInt()
            val radius = orb.size * player.areaMultiplier

            if (orb.hitTimer > 0f) continue

            for (eid in world.getEnemyEntities()) {
                val et     = world.transforms[eid] ?: continue
                val health = world.healths[eid]    ?: continue
                if (health.isDead) continue
                if (eid in orb.hitEntities) continue

                val dx = et.x - ox
                val dy = et.y - oy
                if (dx * dx + dy * dy > (radius + 16f) * (radius + 16f)) continue

                health.current = (health.current - dmg).coerceAtLeast(0)
                world.renders[eid]?.flashTimer = 0.1f
                orb.hitEntities.add(eid)
                orb.hitTimer = orb.hitCooldown
                EntityFactory.createDamageNumber(world, et.x, et.y, dmg, false)
                if (health.current <= 0 && !health.isDead) health.isDead = true
                break
            }

            // Clear hit set on full rotation
            if (orb.currentAngle % (2f * PI.toFloat()) < orb.orbitSpeed * dt) {
                orb.hitEntities.clear()
            }
        }
    }

    // ── Projectile lifetimes ──────────────────────────────────────────────

    private fun updateProjectileLifetimes(world: World, dt: Float) {
        val toDestroy = mutableListOf<Int>()
        for ((id, proj) in world.projectiles) {
            proj.lifetime -= dt
            if (proj.lifetime <= 0f) {
                if (proj.isAreaEffect) {
                    // Drop area damage pool — instant damage in radius
                    val t = world.transforms[id]
                    if (t != null) {
                        for (eid in world.getEnemyEntities()) {
                            val et     = world.transforms[eid] ?: continue
                            val health = world.healths[eid]    ?: continue
                            if (health.isDead) continue
                            val dx = et.x - t.x; val dy = et.y - t.y
                            if (dx * dx + dy * dy < proj.areaRadius * proj.areaRadius) {
                                health.current = (health.current - proj.damage).coerceAtLeast(0)
                                world.renders[eid]?.flashTimer = 0.1f
                                EntityFactory.createDamageNumber(world, et.x, et.y, proj.damage, false)
                                if (health.current <= 0 && !health.isDead) health.isDead = true
                            }
                        }
                        EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.EXPLOSION, 12)
                    }
                }
                toDestroy.add(id)
            }
        }
        toDestroy.forEach { world.destroyEntity(it) }
    }

    // ── Render / iFrame timers ────────────────────────────────────────────

    private fun updateRenderTimers(world: World, dt: Float) {
        for ((_, render) in world.renders) {
            if (render.flashTimer > 0f) render.flashTimer -= dt
            if (render.animTimer  > 0f) render.animTimer  -= dt
        }
    }

    private fun updateEnemyIFrames(world: World, dt: Float) {
        for ((_, health) in world.healths) {
            if (health.invincibleTimer > 0f)
                health.invincibleTimer = (health.invincibleTimer - dt).coerceAtLeast(0f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun findNearestEnemy(world: World, ox: Float, oy: Float): Int? {
        var nearest   = -1
        var nearestD2 = Float.MAX_VALUE
        for (eid in world.getEnemyEntities()) {
            val health = world.healths[eid] ?: continue
            if (health.isDead) continue
            val t  = world.transforms[eid] ?: continue
            val dx = t.x - ox; val dy = t.y - oy
            val d2 = dx * dx + dy * dy
            if (d2 < nearestD2) { nearestD2 = d2; nearest = eid }
        }
        return if (nearest == -1) null else nearest
    }

    private fun findEnemiesInRadius(world: World, ox: Float, oy: Float, radius: Float): List<Int> {
        val result = mutableListOf<Int>()
        val r2     = radius * radius
        for (eid in world.getEnemyEntities()) {
            val health = world.healths[eid] ?: continue
            if (health.isDead) continue
            val t  = world.transforms[eid] ?: continue
            val dx = t.x - ox; val dy = t.y - oy
            if (dx * dx + dy * dy <= r2) result.add(eid)
        }
        return result
    }
}
