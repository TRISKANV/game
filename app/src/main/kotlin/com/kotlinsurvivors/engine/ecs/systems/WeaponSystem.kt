package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.*
import kotlin.random.Random

/**
 * WeaponSystem
 *
 * Memory fixes:
 *  1. All iterations use ID snapshots (zero Pair allocation).
 *  2. updateRenderTimers / updateEnemyIFrames use ID snapshots.
 *  3. updateProjectileLifetimes reuses a pre-allocated destroy list.
 *  4. Particle burst counts reduced to stay within memory budget.
 */
class WeaponSystem {

    private val toDestroy = ArrayList<Int>(128)

    private companion object {
        const val WHIP_LASH_GAP     = 0.08f   // seconds between lashes in different directions
        const val WHIP_REACH        = 130f
        const val WHIP_HALF_WIDTH   = 40f
        const val WHIP_BEHIND       = 20f     // an enemy touching the player still counts as being in front
        // Chest weapons
        const val DDOS_RADIUS       = 170f
        const val ZERO_DAY_LENGTH   = 480f
        const val ZERO_DAY_HALF_W   = 18f
        const val BOMB_RANGE        = 380f
        const val BOMB_RADIUS       = 130f
        const val DEFAULT_ENEMY_RADIUS = 12f
        val WHIP_ANGLES = floatArrayOf(0f, (PI / 2).toFloat(), PI.toFloat(), (3 * PI / 2).toFloat())  // right, down, left, up
    }

    fun update(world: World, dt: Float) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return

        val player  = world.players[pid]    ?: return
        val pt      = world.transforms[pid] ?: return
        val weapons = world.weapons[pid]    ?: return

        world.healths[pid]?.let {
            if (it.invincibleTimer > 0f)
                it.invincibleTimer = (it.invincibleTimer - dt).coerceAtLeast(0f)
        }

        for (weapon in weapons) {
            weapon.timer -= dt
            if (weapon.timer > 0f) continue
            // The whip only cracks when somebody is in reach; it stays ready until then.
            val whipDirections = if (weapon.type == WeaponType.WHIP)
                whipDirectionsWithEnemies(world, pt.x, pt.y, weapon.area * player.areaMultiplier) else 0
            if (weapon.type == WeaponType.WHIP && whipDirections == 0) { weapon.timer = 0f; continue }
            // The chest weapons, like the whip, wait until there is something to hit.
            if (weapon.type.isChestOnly && !hasChestWeaponTarget(world, weapon, pt.x, pt.y, weapon.area * player.areaMultiplier)) {
                weapon.timer = 0f; continue
            }
            weapon.timer = weapon.cooldown / player.attackSpeedMultiplier

            val dmg      = (weapon.damage * player.damageMultiplier).toInt()
            val speed    = weapon.projectileSpeed * player.projectileSpeedMultiplier
            val size     = weapon.projectileSize  * player.projectileSizeMultiplier
            val area     = weapon.area            * player.areaMultiplier
            val dur      = weapon.duration        * player.durationMultiplier
            val crit     = Random.nextFloat() < player.criticalChance
            val finalDmg = if (crit) (dmg * player.criticalMultiplier).toInt() else dmg

            when (weapon.type) {
                WeaponType.MAGIC_WAND  -> fireMagicWand(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.KNIFE       -> fireKnife(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.CROSS       -> fireCross(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.FIRE_WAND   -> fireFireWand(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.AXE         -> fireAxe(world, pt.x, pt.y, finalDmg, speed, size, weapon, dur, crit)
                WeaponType.LIGHTNING   -> fireLightning(world, pt.x, pt.y, finalDmg, weapon, area)
                WeaponType.GARLIC      -> { }
                WeaponType.SANTA_WATER -> fireSantaWater(world, pt.x, pt.y, finalDmg, size, weapon, area)
                WeaponType.WHIP        -> fireWhip(world, pt.x, pt.y, finalDmg, whipDirections, area)
                WeaponType.BIBLE       -> { }
                WeaponType.DDOS        -> fireDdos(world, pt.x, pt.y, finalDmg, crit, area)
                WeaponType.ZERO_DAY    -> fireZeroDay(world, pt.x, pt.y, finalDmg, crit, weapon, area)
                WeaponType.LOGIC_BOMB  -> fireLogicBomb(world, pt.x, pt.y, finalDmg, crit, weapon, area)
            }
        }

        updateBlasts(world, dt)
        updateAuras(world, pid, dt, player)
        updateOrbitals(world, pid, dt, pt.x, pt.y, player)
        updateProjectileLifetimes(world, dt)
        updateRenderTimers(world, dt)
        updateEnemyIFrames(world, dt)
    }

    // ── Weapon firing ─────────────────────────────────────────────────────

    private fun fireMagicWand(world: World, ox: Float, oy: Float, dmg: Int,
                               speed: Float, size: Float, w: WeaponComponent, dur: Float, crit: Boolean) {
        val target = findNearestEnemy(world, ox, oy) ?: return
        val t      = world.transforms[target] ?: return
        val angle  = atan2(t.y - oy, t.x - ox)
        val spread = PI.toFloat() / 16f
        repeat(w.projectileCount) { i ->
            val off = if (w.projectileCount > 1) (i - (w.projectileCount - 1) * 0.5f) * spread else 0f
            EntityFactory.createProjectile(world, ox, oy, cos(angle+off)*speed, sin(angle+off)*speed,
                WeaponType.MAGIC_WAND, dmg, size, w.piercing, dur, crit, w.knockback)
        }
    }

    private fun fireKnife(world: World, ox: Float, oy: Float, dmg: Int,
                           speed: Float, size: Float, w: WeaponComponent, dur: Float, crit: Boolean) {
        val target = findNearestEnemy(world, ox, oy)
        val angle  = if (target != null) {
            val t = world.transforms[target] ?: return
            atan2(t.y - oy, t.x - ox)
        } else 0f
        repeat(w.projectileCount) { i ->
            val off = (i - (w.projectileCount - 1) * 0.5f) * 0.15f
            EntityFactory.createProjectile(world, ox, oy,
                cos(angle+off)*speed*1.5f, sin(angle+off)*speed*1.5f,
                WeaponType.KNIFE, dmg, size*0.7f, w.piercing, dur*0.6f, crit, w.knockback*0.5f)
        }
    }

    private fun fireCross(world: World, ox: Float, oy: Float, dmg: Int,
                           speed: Float, size: Float, w: WeaponComponent, dur: Float, crit: Boolean) {
        val dirs = listOf(0f, PI.toFloat()/2f, PI.toFloat(), 3f*PI.toFloat()/2f)
        for (angle in dirs) repeat(w.projectileCount) {
            EntityFactory.createProjectile(world, ox, oy, cos(angle)*speed, sin(angle)*speed,
                WeaponType.CROSS, dmg, size, w.piercing, dur, crit, w.knockback)
        }
    }

    private fun fireFireWand(world: World, ox: Float, oy: Float, dmg: Int,
                              speed: Float, size: Float, w: WeaponComponent, dur: Float, crit: Boolean) {
        val target = findNearestEnemy(world, ox, oy) ?: return
        val t      = world.transforms[target] ?: return
        val angle  = atan2(t.y - oy, t.x - ox)
        repeat(w.projectileCount) { i ->
            val off = (i - (w.projectileCount - 1) * 0.5f) * 0.1f
            EntityFactory.createProjectile(world, ox, oy, cos(angle+off)*speed, sin(angle+off)*speed,
                WeaponType.FIRE_WAND, dmg, size, w.piercing+2, dur, crit, 0f)
        }
    }

    private fun fireAxe(world: World, ox: Float, oy: Float, dmg: Int,
                         speed: Float, size: Float, w: WeaponComponent, dur: Float, crit: Boolean) {
        val base   = -PI.toFloat() / 2f
        val spread = PI.toFloat() / 5f
        repeat(w.projectileCount) { i ->
            val angle = base + (i - (w.projectileCount-1)*0.5f) * spread
            EntityFactory.createProjectile(world, ox, oy,
                cos(angle)*speed*0.8f, sin(angle)*speed*0.8f - 60f,
                WeaponType.AXE, dmg, size*1.4f, w.piercing, dur*1.2f, crit, w.knockback*1.5f)
        }
    }

    private fun fireLightning(world: World, ox: Float, oy: Float, dmg: Int,
                               w: WeaponComponent, area: Float) {
        val range   = 350f * area
        val targets = findEnemiesInRadius(world, ox, oy, range)
            .sortedBy { eid -> world.transforms[eid]?.let { val dx=it.x-ox; val dy=it.y-oy; dx*dx+dy*dy } ?: Float.MAX_VALUE }
            .take(w.projectileCount + (w.level - 1) * 2)
        for (eid in targets) {
            val health = world.healths[eid] ?: continue
            val t      = world.transforms[eid] ?: continue
            health.current = (health.current - dmg).coerceAtLeast(0)
            world.renders[eid]?.flashTimer = 0.12f
            EntityFactory.createDamageNumber(world, t.x, t.y, dmg, false)
            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.SPARK, 3)
            if (health.current <= 0 && !health.isDead) health.isDead = true
        }
    }

    private fun fireSantaWater(world: World, ox: Float, oy: Float, dmg: Int,
                                size: Float, w: WeaponComponent, area: Float) {
        repeat(w.projectileCount) {
            val angle  = Random.nextFloat() * 2f * PI.toFloat()
            val projId = EntityFactory.createProjectile(world, ox, oy,
                cos(angle)*200f, sin(angle)*200f,
                WeaponType.SANTA_WATER, dmg, size*area, 99, 0.4f, false, 0f)
            world.projectiles[projId]?.isAreaEffect = true
            world.projectiles[projId]?.areaRadius   = 48f * area
        }
    }

    /**
     * Bit k is set when an enemy stands inside the whip's lane in direction k
     * (0 right, 1 down, 2 left, 3 up). A lane is [WHIP_REACH] long and
     * 2 x [WHIP_HALF_WIDTH] wide, both scaled by [area].
     */
    private fun whipDirectionsWithEnemies(world: World, ox: Float, oy: Float, area: Float): Int {
        var mask = 0
        for (eid in world.getEnemySnapshot()) {
            val t = world.transforms[eid] ?: continue
            if (world.healths[eid]?.isDead != false) continue
            mask = mask or whipLanesOf(t.x - ox, t.y - oy, area)
            if (mask == 0b1111) break
        }
        return mask
    }

    private fun whipLanesOf(dx: Float, dy: Float, area: Float): Int {
        val reach = WHIP_REACH * area; val half = WHIP_HALF_WIDTH * area; val behind = WHIP_BEHIND
        var lanes = 0
        if (dx > -behind && dx < reach  && abs(dy) < half) lanes = lanes or 0b0001   // right
        if (dy > -behind && dy < reach  && abs(dx) < half) lanes = lanes or 0b0010   // down
        if (dx < behind  && dx > -reach && abs(dy) < half) lanes = lanes or 0b0100   // left
        if (dy < behind  && dy > -reach && abs(dx) < half) lanes = lanes or 0b1000   // up
        return lanes
    }

    /** Cracks the cable in every lane in [directions] and hurts whoever stands in them. */
    private fun fireWhip(world: World, ox: Float, oy: Float, dmg: Int, directions: Int, area: Float) {
        var order = 0
        for (k in 0 until 4) {
            if (directions and (1 shl k) == 0) continue
            EntityFactory.createWhipLash(world, ox, oy, WHIP_ANGLES[k], WHIP_REACH * area, order * WHIP_LASH_GAP)
            order++
        }
        for (eid in world.getEnemySnapshot()) {
            val t      = world.transforms[eid] ?: continue
            val health = world.healths[eid]    ?: continue
            if (health.isDead) continue
            if (whipLanesOf(t.x - ox, t.y - oy, area) and directions == 0) continue
            health.current = (health.current - dmg).coerceAtLeast(0)
            world.renders[eid]?.flashTimer = 0.1f
            EntityFactory.createDamageNumber(world, t.x, t.y, dmg, false)
            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.BLOOD, 2)
            if (health.current <= 0 && !health.isDead) health.isDead = true
        }
    }

    // ── Aura ─────────────────────────────────────────────────────────────

    private fun updateAuras(world: World, pid: Int, dt: Float, player: PlayerComponent) {
        val auras = world.auras[pid] ?: return
        val pt    = world.transforms[pid] ?: return
        for (aura in auras) {
            aura.tickTimer -= dt
            if (aura.tickTimer > 0f) continue
            aura.tickTimer = 1f / aura.tickRate
            val radius = aura.radius * player.areaMultiplier
            val dmg    = (aura.damage * player.damageMultiplier).toInt()
            for (eid in world.getEnemySnapshot()) {
                val et     = world.transforms[eid] ?: continue
                val health = world.healths[eid]    ?: continue
                if (health.isDead) continue
                val dx = et.x - pt.x; val dy = et.y - pt.y
                if (dx*dx + dy*dy > radius*radius) continue
                health.current = (health.current - dmg).coerceAtLeast(0)
                world.renders[eid]?.flashTimer = 0.08f
                // No particles from aura — too many per tick
                if (health.current <= 0 && !health.isDead) health.isDead = true
            }
        }
    }

    // ── Orbitals ──────────────────────────────────────────────────────────

    private fun updateOrbitals(world: World, pid: Int, dt: Float,
                                px: Float, py: Float, player: PlayerComponent) {
        val orbitals = world.orbitals[pid] ?: return
        for (orb in orbitals) {
            orb.currentAngle += orb.orbitSpeed * dt
            orb.hitTimer      = (orb.hitTimer - dt).coerceAtLeast(0f)
            if (orb.hitTimer > 0f) continue
            val ox = px + cos(orb.currentAngle) * orb.orbitRadius
            val oy = py + sin(orb.currentAngle) * orb.orbitRadius
            val dmg    = (orb.damage * player.damageMultiplier).toInt()
            val radius = orb.size * player.areaMultiplier
            for (eid in world.getEnemySnapshot()) {
                val et     = world.transforms[eid] ?: continue
                val health = world.healths[eid]    ?: continue
                if (health.isDead || eid in orb.hitEntities) continue
                val dx = et.x - ox; val dy = et.y - oy
                if (dx*dx + dy*dy > (radius+16f)*(radius+16f)) continue
                health.current = (health.current - dmg).coerceAtLeast(0)
                world.renders[eid]?.flashTimer = 0.1f
                orb.hitEntities.add(eid)
                orb.hitTimer = orb.hitCooldown
                EntityFactory.createDamageNumber(world, et.x, et.y, dmg, false)
                if (health.current <= 0 && !health.isDead) health.isDead = true
                break
            }
            // Clear stale IDs every revolution
            if (orb.currentAngle % (2f * PI.toFloat()) < orb.orbitSpeed * dt) {
                orb.hitEntities.clear()
            }
        }
    }

    // ── Projectile lifetimes ──────────────────────────────────────────────

    private fun updateProjectileLifetimes(world: World, dt: Float) {
        toDestroy.clear()
        for (id in world.getProjectileSnapshot()) {
            val proj = world.projectiles[id] ?: continue
            proj.lifetime -= dt
            if (proj.lifetime <= 0f) {
                if (proj.isAreaEffect) {
                    val t = world.transforms[id]
                    if (t != null) {
                        for (eid in world.getEnemySnapshot()) {
                            val et     = world.transforms[eid] ?: continue
                            val health = world.healths[eid]    ?: continue
                            if (health.isDead) continue
                            val dx = et.x - t.x; val dy = et.y - t.y
                            if (dx*dx + dy*dy < proj.areaRadius * proj.areaRadius) {
                                health.current = (health.current - proj.damage).coerceAtLeast(0)
                                world.renders[eid]?.flashTimer = 0.1f
                                EntityFactory.createDamageNumber(world, et.x, et.y, proj.damage, false)
                                if (health.current <= 0 && !health.isDead) health.isDead = true
                            }
                        }
                        EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.EXPLOSION, 6)
                    }
                }
                toDestroy.add(id)
            }
        }
        for (id in toDestroy) world.destroyEntity(id)
    }

    // ── Render / health timers ────────────────────────────────────────────

    private fun updateRenderTimers(world: World, dt: Float) {
        for (id in world.getRenderSnapshot()) {
            val render = world.renders[id] ?: continue
            if (render.flashTimer > 0f) render.flashTimer -= dt
        }
    }

    private fun updateEnemyIFrames(world: World, dt: Float) {
        for (id in world.getHealthSnapshot()) {
            val health = world.healths[id] ?: continue
            if (health.invincibleTimer > 0f)
                health.invincibleTimer = (health.invincibleTimer - dt).coerceAtLeast(0f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    // ── Chest weapons ──────────────────────────────────────────────────────

    private fun distanceToNearestEnemy(world: World, ox: Float, oy: Float): Float {
        val id = findNearestEnemy(world, ox, oy) ?: return Float.MAX_VALUE
        val t = world.transforms[id] ?: return Float.MAX_VALUE
        return hypot(t.x - ox, t.y - oy)
    }

    private fun hasChestWeaponTarget(world: World, w: WeaponComponent, ox: Float, oy: Float, area: Float): Boolean {
        val reach = when (w.type) {
            WeaponType.DDOS       -> DDOS_RADIUS * area
            WeaponType.ZERO_DAY   -> ZERO_DAY_LENGTH * area
            else                  -> BOMB_RANGE
        }
        return distanceToNearestEnemy(world, ox, oy) <= reach
    }

    private fun hurtEnemy(world: World, eid: Int, dmg: Int, crit: Boolean, burst: ParticleType) {
        val health = world.healths[eid] ?: return
        val t      = world.transforms[eid] ?: return
        if (health.isDead) return
        health.current = (health.current - dmg).coerceAtLeast(0)
        world.renders[eid]?.flashTimer = 0.12f
        EntityFactory.createDamageNumber(world, t.x, t.y, dmg, crit)
        EntityFactory.createParticleBurst(world, t.x, t.y, burst, 2)
        if (health.current <= 0 && !health.isDead) health.isDead = true
    }

    /** DDoS: a shock wave that hurts everything around the player at once. */
    private fun fireDdos(world: World, ox: Float, oy: Float, dmg: Int, crit: Boolean, area: Float) {
        val radius = DDOS_RADIUS * area
        EntityFactory.createEffect(world, ParticleType.RING, ox, oy, 0f, radius)
        for (eid in findEnemiesInRadius(world, ox, oy, radius)) hurtEnemy(world, eid, dmg, crit, ParticleType.SPARK)
    }

    /**
     * Zero-Day: a beam toward the nearest enemy that goes through everything in its way.
     * Extra beams (one more every third level) fan out around the first.
     */
    private fun fireZeroDay(world: World, ox: Float, oy: Float, dmg: Int, crit: Boolean,
                            w: WeaponComponent, area: Float) {
        val target = findNearestEnemy(world, ox, oy) ?: return
        val tt     = world.transforms[target] ?: return
        val base   = atan2(tt.y - oy, tt.x - ox)
        val length = ZERO_DAY_LENGTH * area
        val half   = ZERO_DAY_HALF_W * (0.8f + 0.2f * area)
        val spread = PI.toFloat() / 10f
        val hit    = HashSet<Int>()
        repeat(w.projectileCount) { i ->
            val angle = base + if (w.projectileCount > 1) (i - (w.projectileCount - 1) * 0.5f) * spread else 0f
            EntityFactory.createEffect(world, ParticleType.BEAM, ox, oy, angle, length)
            val ca = cos(angle); val sa = sin(angle)
            for (eid in world.getEnemySnapshot()) {
                if (eid in hit) continue
                val t = world.transforms[eid] ?: continue
                val dx = t.x - ox; val dy = t.y - oy
                val along = dx * ca + dy * sa
                val across = abs(-dx * sa + dy * ca)
                val r = world.colliders[eid]?.radius ?: DEFAULT_ENEMY_RADIUS
                if (along < -10f || along > length || across > half + r) continue
                hit.add(eid)
                hurtEnemy(world, eid, dmg, crit, ParticleType.SPARK)
            }
        }
    }

    /** Logic Bomb: drops a bomb on the nearest enemies; it goes off after a short fuse. */
    private fun fireLogicBomb(world: World, ox: Float, oy: Float, dmg: Int, crit: Boolean,
                              w: WeaponComponent, area: Float) {
        val targets = findEnemiesInRadius(world, ox, oy, BOMB_RANGE)
            .sortedBy { eid -> world.transforms[eid]?.let { val dx = it.x - ox; val dy = it.y - oy; dx * dx + dy * dy } ?: Float.MAX_VALUE }
            .take(w.projectileCount)
        for (eid in targets) {
            val t = world.transforms[eid] ?: continue
            EntityFactory.createLogicBomb(world, t.x, t.y, dmg, BOMB_RADIUS * area, crit)
        }
    }

    /** Counts down every lit bomb and detonates it when the fuse runs out. */
    private fun updateBlasts(world: World, dt: Float) {
        if (world.blasts.isEmpty()) return
        toDestroy.clear()
        for ((id, blast) in world.blasts) {
            if (id in world.destroyedTags) continue
            blast.timeLeft -= dt
            if (blast.timeLeft > 0f) continue
            val t = world.transforms[id] ?: continue
            EntityFactory.createEffect(world, ParticleType.RING, t.x, t.y, 0f, blast.radius)
            EntityFactory.createParticleBurst(world, t.x, t.y, ParticleType.EXPLOSION, 6)
            for (eid in findEnemiesInRadius(world, t.x, t.y, blast.radius)) {
                hurtEnemy(world, eid, blast.damage, blast.isCritical, ParticleType.EXPLOSION)
            }
            toDestroy.add(id)
        }
        for (id in toDestroy) world.destroyEntity(id)
    }

    private fun findNearestEnemy(world: World, ox: Float, oy: Float): Int? {
        var nearest = -1; var nearestD2 = Float.MAX_VALUE
        for (eid in world.getEnemySnapshot()) {
            val health = world.healths[eid] ?: continue
            if (health.isDead) continue
            val t  = world.transforms[eid] ?: continue
            val dx = t.x - ox; val dy = t.y - oy; val d2 = dx*dx + dy*dy
            if (d2 < nearestD2) { nearestD2 = d2; nearest = eid }
        }
        return if (nearest == -1) null else nearest
    }

    private fun findEnemiesInRadius(world: World, ox: Float, oy: Float, radius: Float): List<Int> {
        val result = mutableListOf<Int>(); val r2 = radius * radius
        for (eid in world.getEnemySnapshot()) {
            val health = world.healths[eid] ?: continue
            if (health.isDead) continue
            val t  = world.transforms[eid] ?: continue
            val dx = t.x - ox; val dy = t.y - oy
            if (dx*dx + dy*dy <= r2) result.add(eid)
        }
        return result
    }
}
