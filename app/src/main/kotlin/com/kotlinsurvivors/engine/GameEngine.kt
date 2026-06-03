package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.systems.*
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * GameEngine
 *
 * Central game loop. Runs on a dedicated coroutine with a fixed-timestep
 * accumulator to ensure consistent physics regardless of frame rate.
 *
 * Architecture:
 *   ViewModel → starts/stops engine
 *   Engine    → ticks all ECS systems
 *   Engine    → emits GameState via StateFlow
 *   Compose   → collects GameState and renders
 */
class GameEngine(
    private val viewportWidth : Float,
    private val viewportHeight: Float
) {
    companion object {
        const val TARGET_FPS      = 60
        const val FIXED_DT        = 1f / TARGET_FPS
        const val MAX_FRAME_TIME  = 0.05f
        const val WORLD_WIDTH     = 4096f
        const val WORLD_HEIGHT    = 4096f
    }

    val world = World()

    private val movementSystem   = MovementSystem(WORLD_WIDTH, WORLD_HEIGHT)
    private val collisionSystem  = CollisionSystem()
    private val weaponSystem     = WeaponSystem()
    private val spawnSystem      = SpawnSystem(viewportWidth, viewportHeight)
    private val experienceSystem = ExperienceSystem()

    val joystick = VirtualJoystick()

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private var elapsedTime    = 0f
    private var isPaused       = false
    private var isRunning      = false
    private var pendingLevelUp = false
    private val frameEvents    = mutableListOf<GameEvent>()

    private var engineJob: Job? = null
    private val engineDispatcher = newSingleThreadContext("GameEngine")

    // ── Public API ──────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        initWorld()

        engineJob = scope.launch(engineDispatcher) {
            var lastTime    = System.nanoTime()
            var accumulator = 0f

            while (isActive && isRunning) {
                val now   = System.nanoTime()
                val rawDt = (now - lastTime) / 1_000_000_000f
                lastTime  = now
                val dt    = rawDt.coerceAtMost(MAX_FRAME_TIME)
                accumulator += dt

                while (accumulator >= FIXED_DT) {
                    if (!isPaused && !pendingLevelUp) {
                        tick(FIXED_DT)
                    }
                    accumulator -= FIXED_DT
                }

                emitState()

                val elapsed = (System.nanoTime() - now) / 1_000_000L
                val sleep   = (1000L / TARGET_FPS) - elapsed
                if (sleep > 0L) delay(sleep)
            }
        }
    }

    fun stop() {
        isRunning = false
        engineJob?.cancel()
        engineJob = null
    }

    fun pause()  { isPaused = true  }
    fun resume() { isPaused = false }

    fun restart(scope: CoroutineScope) {
        stop()
        world.clear()
        spawnSystem.reset()
        elapsedTime    = 0f
        isPaused       = false
        pendingLevelUp = false
        start(scope)
    }

    fun applyLevelUpChoice(option: LevelUpOption) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return
        applyUpgrade(world, pid, option)
        pendingLevelUp = false
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private fun initWorld() {
        world.clear()
        EntityFactory.createPlayer(world, WORLD_WIDTH / 2f, WORLD_HEIGHT / 2f)
    }

    private fun tick(dt: Float) {
        elapsedTime += dt
        frameEvents.clear()

        movementSystem.update(world, dt, joystick.getState())
        weaponSystem.update(world, dt)
        collisionSystem.update(world, dt, frameEvents)

        val processed = experienceSystem.processEvents(world, frameEvents, dt)

        spawnSystem.update(world, dt, elapsedTime)
        world.flushDestroyed()

        for (event in processed) {
            when (event) {
                is GameEvent.LevelUp  -> pendingLevelUp = true
                is GameEvent.PlayerDied -> isPaused = true
                else -> {}
            }
        }

        frameEvents.clear()
        frameEvents.addAll(processed)
    }

    private fun emitState() {
        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        val health = world.healths[pid]
        val pt     = world.transforms[pid]

        val levelUpOptions = if (pendingLevelUp) generateLevelUpOptions(world, pid) else emptyList()

        _gameState.value = GameState(
            isRunning        = isRunning && !isPaused,
            isPaused         = isPaused,
            isGameOver       = health?.isDead ?: false,
            isPendingLevelUp = pendingLevelUp,
            levelUpOptions   = levelUpOptions,
            elapsedTime      = elapsedTime,
            playerHp         = health?.current ?: 0,
            playerMaxHp      = health?.max ?: 100,
            playerLevel      = player?.level ?: 1,
            playerXp         = player?.experience ?: 0,
            playerXpToNext   = player?.experienceToNextLevel ?: 10,
            playerCoins      = player?.coins ?: 0,
            playerX          = pt?.x ?: 0f,
            playerY          = pt?.y ?: 0f,
            enemyCount       = world.getEnemyEntities().size,
            killCount        = player?.killCount ?: 0,
            world            = world,
            events           = frameEvents.toList()
        )
    }

    // ── Level-up option generation ─────────────────────────────────────────

    private fun generateLevelUpOptions(world: World, pid: Int): List<LevelUpOption> {
        val player  = world.players[pid]  ?: return emptyList()
        val weapons = world.weapons[pid]  ?: return emptyList()
        val ownedTypes = weapons.map { it.type }.toSet()

        val options = mutableListOf<LevelUpOption>()

        for (weapon in weapons) {
            if (weapon.level < 8) {
                options.add(LevelUpOption(
                    id          = "upgrade_${weapon.type.name}",
                    title       = weaponUpgradeTitle(weapon.type, weapon.level + 1),
                    description = weaponUpgradeDesc(weapon.level + 1),
                    type        = UpgradeType.WEAPON_UPGRADE,
                    weaponType  = weapon.type,
                    icon        = weaponIcon(weapon.type)
                ))
            }
        }

        com.kotlinsurvivors.engine.ecs.components.WeaponType.values()
            .filter { it !in ownedTypes && weapons.size < 6 }
            .take(2)
            .forEach { wt ->
                options.add(LevelUpOption(
                    id          = "new_${wt.name}",
                    title       = "New: ${weaponName(wt)}",
                    description = weaponDescription(wt),
                    type        = UpgradeType.NEW_WEAPON,
                    weaponType  = wt,
                    icon        = weaponIcon(wt)
                ))
            }

        options.addAll(listOf(
            LevelUpOption("stat_hp",       "Vital Boost",    "+30 Max HP",            UpgradeType.STAT, icon = "❤️"),
            LevelUpOption("stat_speed",    "Swift Feet",     "+15% Move Speed",       UpgradeType.STAT, icon = "👟"),
            LevelUpOption("stat_damage",   "Power Surge",    "+20% Damage",           UpgradeType.STAT, icon = "⚔️"),
            LevelUpOption("stat_area",     "Wide Strike",    "+15% Area",             UpgradeType.STAT, icon = "🌐"),
            LevelUpOption("stat_cooldown", "Rapid Fire",     "-10% Cooldowns",        UpgradeType.STAT, icon = "⚡"),
            LevelUpOption("stat_regen",    "Regeneration",   "+1 HP/sec Regen",       UpgradeType.STAT, icon = "🔋"),
            LevelUpOption("stat_magnet",   "Magnetism",      "+50% Pickup Range",     UpgradeType.STAT, icon = "🧲"),
        ))

        return options.shuffled().take(3)
    }

    private fun applyUpgrade(world: World, pid: Int, option: LevelUpOption) {
        val player  = world.players[pid]  ?: return
        val health  = world.healths[pid]  ?: return
        val weapons = world.weapons[pid]  ?: return

        when (option.type) {
            UpgradeType.WEAPON_UPGRADE -> {
                val weapon = weapons.find { it.type == option.weaponType } ?: return
                weapon.level++
                weapon.damage          = (weapon.damage * 1.2f).roundToInt()
                weapon.cooldown        *= 0.92f
                if (weapon.level % 3 == 0) weapon.projectileCount++
                if (weapon.level % 4 == 0) weapon.piercing++
                weapon.area            *= 1.1f
            }
            UpgradeType.NEW_WEAPON -> {
                val wt = option.weaponType ?: return
                if (weapons.none { it.type == wt }) {
                    weapons.add(newWeaponComponent(wt))
                    when (wt) {
                        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC ->
                            EntityFactory.addAuraWeapon(world, pid, wt, 100f, 8, 2f)
                        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE  ->
                            EntityFactory.addOrbitalWeapon(world, pid, wt, 3, 120f, 1.8f, 20, 14f)
                        else -> {}
                    }
                }
            }
            UpgradeType.STAT -> {
                when (option.id) {
                    "stat_hp"       -> health.current = (health.current + 30).coerceAtMost(health.max + 30)
                    "stat_speed"    -> player.speed                 *= 1.15f
                    "stat_damage"   -> player.damageMultiplier      *= 1.20f
                    "stat_area"     -> player.areaMultiplier        *= 1.15f
                    "stat_cooldown" -> player.attackSpeedMultiplier *= 1.10f
                    "stat_regen"    -> player.regenPerSecond        += 1f
                    "stat_magnet"   -> { player.magnetRadius *= 1.5f; player.pickupRadius *= 1.5f }
                }
            }
        }
    }

    private fun newWeaponComponent(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) =
        com.kotlinsurvivors.engine.ecs.components.WeaponComponent(
            type            = type,
            damage          = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> 10
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> 8
                com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> 12
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> 15
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> 25
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> 20
                com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> 18
                com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> 22
                else                                                               -> 0
            },
            cooldown        = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> 0.7f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> 1.4f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> 1.2f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> 1.8f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> 1.5f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> 1.6f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> 1.3f
                else                                                               -> 999f
            },
            projectileSpeed = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE -> 600f
                else                                                         -> 350f
            },
            projectileSize  = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE  -> 18f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE -> 8f
                else                                                         -> 13f
            },
            piercing        = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND -> 3
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE     -> 2
                else                                                             -> 1
            }
        )

    // ── Weapon metadata ────────────────────────────────────────────────────

    private fun weaponName(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) = when (type) {
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "Magic Wand"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "Throwing Knife"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "Cross"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "Fire Wand"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "Axe"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "Lightning Ring"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "Garlic"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "Santa Water"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "Whip"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "King Bible"
    }

    private fun weaponDescription(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) = when (type) {
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "Fires a magic bolt at the nearest enemy."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "Throws a fast knife toward the nearest enemy."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "Flings a cross in four directions."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "Fires a piercing flame projectile."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "Hurls a heavy axe in an arc."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "Strikes multiple nearby enemies with lightning."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "Damages enemies in an aura around you."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "Drops holy water that burns everything."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "Lashes enemies in a wide horizontal arc."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "Holy books orbit and destroy nearby enemies."
    }

    private fun weaponUpgradeTitle(type: com.kotlinsurvivors.engine.ecs.components.WeaponType, lvl: Int) =
        "${weaponName(type)} Lv.$lvl"

    private fun weaponUpgradeDesc(lvl: Int) = when {
        lvl % 4 == 0 -> "+1 Projectile, +Damage"
        lvl % 3 == 0 -> "+1 Pierce, +Speed"
        lvl % 2 == 0 -> "+Area, -Cooldown"
        else         -> "+Damage"
    }

    private fun weaponIcon(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) = when (type) {
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "🪄"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "🔪"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "✝️"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "🔥"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "🪓"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "⚡"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "🧄"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "💧"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "🌀"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "📖"
    }
}
