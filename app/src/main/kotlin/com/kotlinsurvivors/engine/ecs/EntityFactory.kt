package com.kotlinsurvivors.engine.ecs

import com.kotlinsurvivors.engine.ecs.components.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Factory object for creating pre-configured entities.
 * Centralises all entity construction so no system creates entities directly.
 */
object EntityFactory {

    // ── Player ─────────────────────────────────────────────────────────────

    fun createPlayer(world: World, x: Float, y: Float): Int {
        val id = world.createEntity()
        world.transforms[id]  = TransformComponent(x = x, y = y)
        world.velocities[id]  = VelocityComponent()
        world.healths[id]     = HealthComponent(current = 100, max = 100, invincibleDuration = 0.5f)
        world.colliders[id]   = ColliderComponent(
            radius = 18f,
            layer = ColliderLayer.PLAYER,
            collidesWithLayers = setOf(ColliderLayer.ENEMY, ColliderLayer.PICKUP)
        )
        world.renders[id]     = RenderComponent(
            shape = RenderShape.CIRCLE,
            color = 0xFF4FC3F7,
            secondaryColor = 0xFF0288D1,
            width = 36f,
            height = 36f,
            glowRadius = 12f,
            glowColor = 0x664FC3F7
        )
        world.players[id]     = PlayerComponent()
        world.playerTags.add(id)

        // Starting weapon
        world.weapons[id] = mutableListOf(
            WeaponComponent(
                type = WeaponType.MAGIC_WAND,
                damage = 10,
                cooldown = 1.0f,
                projectileSpeed = 380f,
                projectileSize = 14f,
                piercing = 1,
                duration = 2.0f
            )
        )
        return id
    }

    // ── Enemies ────────────────────────────────────────────────────────────

    fun createEnemy(world: World, type: EnemyType, x: Float, y: Float, waveMultiplier: Float = 1f): Int {
        val id = world.createEntity()
        val cfg = enemyConfig(type, waveMultiplier)

        world.transforms[id] = TransformComponent(x = x, y = y)
        world.velocities[id] = VelocityComponent()
        world.healths[id]    = HealthComponent(current = cfg.hp, max = cfg.hp, invincibleDuration = 0.1f)
        world.colliders[id]  = ColliderComponent(
            radius = cfg.radius,
            layer = if (type == EnemyType.BOSS_SHADOW || type == EnemyType.BOSS_GOLEM || type == EnemyType.BOSS_NECROMANCER)
                ColliderLayer.BOSS else ColliderLayer.ENEMY,
            collidesWithLayers = setOf(ColliderLayer.PLAYER, ColliderLayer.PROJECTILE)
        )
        world.renders[id]    = RenderComponent(
            shape = cfg.shape,
            color = cfg.color,
            secondaryColor = cfg.secondaryColor,
            width = cfg.radius * 2f,
            height = cfg.radius * 2f,
            glowRadius = cfg.glow,
            glowColor = cfg.glowColor
        )
        world.enemies[id]    = EnemyComponent(
            type = type,
            attackDamage = cfg.damage,
            experienceValue = cfg.xp,
            coinDropChance = cfg.coinChance
        )

        if (type == EnemyType.BOSS_SHADOW || type == EnemyType.BOSS_GOLEM || type == EnemyType.BOSS_NECROMANCER) {
            world.bosses[id] = BossComponent(type = type)
        }
        return id
    }

    private data class EnemyCfg(
        val hp: Int, val radius: Float, val damage: Int,
        val xp: Int, val coinChance: Float,
        val shape: RenderShape, val color: Long, val secondaryColor: Long,
        val glow: Float, val glowColor: Long
    )

    private fun enemyConfig(type: EnemyType, mult: Float): EnemyCfg {
        val hp = { base: Int -> (base * mult).toInt().coerceAtLeast(1) }
        val dmg = { base: Int -> (base * mult).toInt().coerceAtLeast(1) }
        return when (type) {
            EnemyType.BASIC -> EnemyCfg(hp(20), 16f, dmg(8), 5, 0.08f, RenderShape.CIRCLE, 0xFFEF5350, 0xFF9b0000, 0f, 0x00000000)
            EnemyType.FAST  -> EnemyCfg(hp(12), 12f, dmg(5), 3, 0.05f, RenderShape.TRIANGLE, 0xFFFF8A65, 0xFFE64A19, 4f, 0x33FF8A65)
            EnemyType.TANK  -> EnemyCfg(hp(80), 28f, dmg(20), 15, 0.25f, RenderShape.CIRCLE, 0xFF78909C, 0xFF37474F, 0f, 0x00000000)
            EnemyType.RANGED-> EnemyCfg(hp(25), 14f, dmg(12), 8, 0.12f, RenderShape.RECT, 0xFFAB47BC, 0xFF6A1B9A, 5f, 0x33AB47BC)
            EnemyType.EXPLODER->EnemyCfg(hp(15),18f, dmg(40), 6, 0.10f, RenderShape.CIRCLE, 0xFFFFCA28, 0xFFF57F17, 8f, 0x44FFCA28)
            EnemyType.SWARM -> EnemyCfg(hp(5),  8f, dmg(3),  2, 0.02f, RenderShape.CIRCLE, 0xFF66BB6A, 0xFF1B5E20, 0f, 0x00000000)
            EnemyType.BOSS_SHADOW -> EnemyCfg(hp(500), 52f, dmg(30), 200, 1f, RenderShape.CIRCLE, 0xFF7C4DFF, 0xFF311B92, 20f, 0x557C4DFF)
            EnemyType.BOSS_GOLEM  -> EnemyCfg(hp(800), 64f, dmg(45), 300, 1f, RenderShape.RECT,   0xFF8D6E63, 0xFF3E2723, 10f, 0x338D6E63)
            EnemyType.BOSS_NECROMANCER -> EnemyCfg(hp(600), 48f, dmg(25), 250, 1f, RenderShape.TRIANGLE, 0xFF26C6DA, 0xFF006064, 18f, 0x5526C6DA)
        }
    }

    // ── Projectiles ────────────────────────────────────────────────────────

    fun createProjectile(
        world: World,
        x: Float, y: Float,
        vx: Float, vy: Float,
        weaponType: WeaponType,
        damage: Int,
        size: Float,
        piercing: Int,
        duration: Float,
        isCritical: Boolean = false,
        knockback: Float = 50f,
        homing: Boolean = false
    ): Int {
        val id = world.createEntity()
        world.transforms[id]  = TransformComponent(x = x, y = y)
        world.velocities[id]  = VelocityComponent(vx = vx, vy = vy)
        world.projectiles[id] = ProjectileComponent(
            weaponType  = weaponType,
            damage      = damage,
            lifetime    = duration,
            piercingLeft= piercing,
            isCritical  = isCritical,
            knockback   = knockback,
            homing      = homing
        )
        world.colliders[id]   = ColliderComponent(
            radius = size,
            layer  = ColliderLayer.PROJECTILE,
            collidesWithLayers = setOf(ColliderLayer.ENEMY, ColliderLayer.BOSS)
        )
        world.renders[id]     = projectileRender(weaponType, size, isCritical)
        return id
    }

    private fun projectileRender(type: WeaponType, size: Float, crit: Boolean): RenderComponent {
        val (color, gColor) = when (type) {
            WeaponType.MAGIC_WAND  -> Pair(if (crit) 0xFFFFD740L else 0xFFCE93D8L, if (crit) 0xAAFFD740L else 0x44CE93D8L)
            WeaponType.KNIFE       -> Pair(0xFFB0BEC5L, 0x22B0BEC5L)
            WeaponType.CROSS       -> Pair(0xFFF9A825L, 0x44F9A825L)
            WeaponType.FIRE_WAND   -> Pair(0xFFFF7043L, 0x66FF7043L)
            WeaponType.LIGHTNING   -> Pair(0xFFFFF176L, 0x88FFF176L)
            WeaponType.SANTA_WATER -> Pair(0xFF29B6F6L, 0x6629B6F6L)
            WeaponType.AXE         -> Pair(0xFF80CBC4L, 0x3380CBC4L)
            else                   -> Pair(0xFFFFFFFF, 0x44FFFFFFL)
        }
        return RenderComponent(
            shape = RenderShape.CIRCLE,
            color = color,
            width = size * 2f,
            height = size * 2f,
            glowRadius = size * 0.8f,
            glowColor = gColor
        )
    }

    // ── Pickups ────────────────────────────────────────────────────────────

    fun createPickup(world: World, type: PickupType, x: Float, y: Float): Int {
        val id = world.createEntity()
        // slight random scatter
        val ox = Random.nextFloat() * 24f - 12f
        val oy = Random.nextFloat() * 24f - 12f
        world.transforms[id] = TransformComponent(x = x + ox, y = y + oy)
        world.velocities[id] = VelocityComponent()
        world.pickups[id]    = PickupComponent(
            type  = type,
            value = pickupValue(type),
            lifetime = 8f  // 8s for all pickups — prevents XP orb accumulation over time
        )
        world.colliders[id]  = ColliderComponent(
            radius = pickupRadius(type),
            layer  = ColliderLayer.PICKUP,
            collidesWithLayers = setOf(ColliderLayer.PLAYER)
        )
        world.renders[id]    = pickupRender(type)
        return id
    }

    private fun pickupValue(type: PickupType) = when (type) {
        PickupType.EXPERIENCE_SMALL  -> 5
        PickupType.EXPERIENCE_MEDIUM -> 15
        PickupType.EXPERIENCE_LARGE  -> 40
        PickupType.COIN              -> 1
        PickupType.COIN_BAG          -> 10
        PickupType.HEALTH_SMALL      -> 10
        PickupType.HEALTH_LARGE      -> 40
        else                         -> 0
    }

    private fun pickupRadius(type: PickupType) = when (type) {
        PickupType.EXPERIENCE_SMALL  -> 8f
        PickupType.EXPERIENCE_MEDIUM -> 10f
        PickupType.EXPERIENCE_LARGE  -> 14f
        PickupType.COIN, PickupType.COIN_BAG -> 9f
        else -> 12f
    }

    private fun pickupRender(type: PickupType): RenderComponent {
        val (color, sec, sz) = when (type) {
            PickupType.EXPERIENCE_SMALL  -> Triple(0xFF66BB6AL, 0xFF1B5E20L, 16f)
            PickupType.EXPERIENCE_MEDIUM -> Triple(0xFF29B6F6L, 0xFF01579BL, 20f)
            PickupType.EXPERIENCE_LARGE  -> Triple(0xFFCE93D8L, 0xFF6A1B9AL, 26f)
            PickupType.COIN              -> Triple(0xFFFFD740L, 0xFFF57F17L, 16f)
            PickupType.COIN_BAG          -> Triple(0xFFFFCA28L, 0xFFFF6F00L, 22f)
            PickupType.HEALTH_SMALL      -> Triple(0xFFEF9A9AL, 0xFFC62828L, 18f)
            PickupType.HEALTH_LARGE      -> Triple(0xFFF44336L, 0xFFB71C1CL, 24f)
            PickupType.MAGNET            -> Triple(0xFF90A4AEL, 0xFF546E7AL, 20f)
            PickupType.BOMB              -> Triple(0xFF212121L, 0xFF616161L, 20f)
        }
        return RenderComponent(
            shape = RenderShape.CIRCLE,
            color = color,
            secondaryColor = sec,
            width = sz,
            height = sz,
            glowRadius = sz * 0.4f,
            glowColor = color and 0x00FFFFFFL or 0x44000000L
        )
    }

    // ── Damage Numbers ─────────────────────────────────────────────────────

    fun createDamageNumber(world: World, x: Float, y: Float, value: Int, isCritical: Boolean): Int {
        val id = world.createEntity()
        world.transforms[id]     = TransformComponent(x = x + Random.nextFloat() * 20f - 10f, y = y)
        world.damageNumbers[id]  = DamageNumberComponent(value = value, isCritical = isCritical)
        return id
    }

    // ── Particles ──────────────────────────────────────────────────────────

    fun createParticleBurst(world: World, x: Float, y: Float, type: ParticleType, count: Int) {
        val (color, minSize, maxSize, minSpeed, maxSpeed, lifetime) = particleConfig(type)
        repeat(count) {
            val id    = world.createEntity()
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)
            val size  = minSize + Random.nextFloat() * (maxSize - minSize)
            val life  = lifetime * (0.7f + Random.nextFloat() * 0.6f)
            world.transforms[id] = TransformComponent(x = x, y = y)
            world.particles[id]  = ParticleComponent(
                type        = type,
                lifetime    = life,
                maxLifetime = life,
                vx          = cos(angle) * speed,
                vy          = sin(angle) * speed,
                size        = size,
                color       = color,
                drag        = if (type == ParticleType.BLOOD) 0.88f else 0.93f
            )
        }
    }

    private data class ParticleCfg(
        val color: Long, val minSize: Float, val maxSize: Float,
        val minSpeed: Float, val maxSpeed: Float, val lifetime: Float
    )

    private fun particleConfig(type: ParticleType) = when (type) {
        ParticleType.BLOOD      -> ParticleCfg(0xFFEF5350, 2f, 6f, 60f, 180f, 0.4f)
        ParticleType.EXPLOSION  -> ParticleCfg(0xFFFF7043, 4f, 10f, 80f, 250f, 0.5f)
        ParticleType.SPARK      -> ParticleCfg(0xFFFFF176, 2f, 4f, 100f, 300f, 0.3f)
        ParticleType.EXPERIENCE -> ParticleCfg(0xFF66BB6A, 3f, 7f, 40f, 120f, 0.6f)
        ParticleType.COIN       -> ParticleCfg(0xFFFFD740, 3f, 6f, 50f, 140f, 0.5f)
        ParticleType.LEVEL_UP   -> ParticleCfg(0xFFCE93D8, 4f, 12f, 60f, 200f, 0.8f)
        ParticleType.HEAL       -> ParticleCfg(0xFFA5D6A7, 3f, 8f, 30f, 100f, 0.7f)
    }

    // ── Aura Weapon ────────────────────────────────────────────────────────

    fun addAuraWeapon(world: World, entityId: Int, type: WeaponType, radius: Float, damage: Int, tickRate: Float) {
        val list = world.auras.getOrPut(entityId) { mutableListOf() }
        list.add(AuraComponent(weaponType = type, radius = radius, damage = damage, tickRate = tickRate))
    }

    // ── Orbital Weapon ─────────────────────────────────────────────────────

    fun addOrbitalWeapon(world: World, entityId: Int, type: WeaponType, count: Int,
                         radius: Float, speed: Float, damage: Int, size: Float) {
        val list = world.orbitals.getOrPut(entityId) { mutableListOf() }
        val step = (2f * Math.PI.toFloat()) / count
        repeat(count) { i ->
            list.add(OrbitalComponent(
                weaponType    = type,
                orbitRadius   = radius,
                orbitSpeed    = speed,
                currentAngle  = step * i,
                damage        = damage,
                size          = size
            ))
        }
    }

    // ── Enemy Projectile ───────────────────────────────────────────────────

    fun createEnemyProjectile(world: World, x: Float, y: Float, vx: Float, vy: Float, damage: Int): Int {
        val id = world.createEntity()
        world.transforms[id]  = TransformComponent(x = x, y = y)
        world.velocities[id]  = VelocityComponent(vx = vx, vy = vy)
        world.projectiles[id] = ProjectileComponent(
            weaponType   = WeaponType.MAGIC_WAND, // reuse type as marker
            damage       = damage,
            lifetime     = 3f,
            piercingLeft = 1
        )
        world.colliders[id]   = ColliderComponent(
            radius = 8f,
            layer  = ColliderLayer.PROJECTILE,
            collidesWithLayers = setOf(ColliderLayer.PLAYER)
        )
        world.renders[id]     = RenderComponent(
            shape = RenderShape.CIRCLE,
            color = 0xFFEF5350,
            width = 16f, height = 16f,
            glowRadius = 6f, glowColor = 0x44EF5350
        )
        return id
    }
}
