package com.kotlinsurvivors.engine

import android.util.Log
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.systems.*
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kotlinsurvivors.engine.rendering.RenderEntity
import com.kotlinsurvivors.engine.rendering.RenderEntityKind
import com.kotlinsurvivors.engine.rendering.RenderSnapshot
import kotlin.math.roundToInt

class GameEngine(
    private val viewportWidth : Float,
    private val viewportHeight: Float
) {
    companion object {
        const val TARGET_FPS     = 60
        const val FIXED_DT       = 1f / TARGET_FPS
        const val MAX_FRAME_TIME = 0.05f
        const val WORLD_WIDTH    = 4096f
        const val WORLD_HEIGHT   = 4096f

        // Logging
        private const val TAG       = "KS_Engine"
        private const val LOG_EVERY = 180 // log stats every 3 seconds (180 frames at 60fps)
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
    private var frameCount     = 0L
    private var cachedLevelUpOptions: List<LevelUpOption> = emptyList()

    private val levelUpChannel = Channel<LevelUpOption>(capacity = 4)

    private var engineJob: Job? = null
    private val engineDispatcher = newSingleThreadContext("GameEngine")

    // ── Public API ──────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        initWorld()
        Log.d(TAG, "Engine starting. Viewport: ${viewportWidth}x${viewportHeight}")

        // Install global uncaught exception handler to capture any crash
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "═══ UNCAUGHT EXCEPTION on thread '${thread.name}' ═══", throwable)
            Log.e(TAG, "World state at crash: ${world.getDiagnosticString()}")
            previousHandler?.uncaughtException(thread, throwable)
        }

        engineJob = scope.launch(engineDispatcher) {
            var lastTime    = System.nanoTime()
            var accumulator = 0f

            Log.d(TAG, "Game loop started on thread: ${Thread.currentThread().name}")

            while (isActive && isRunning) {
                try {
                    val now   = System.nanoTime()
                    val rawDt = (now - lastTime) / 1_000_000_000f
                    lastTime  = now
                    val dt    = rawDt.coerceAtMost(MAX_FRAME_TIME)
                    accumulator += dt

                    while (accumulator >= FIXED_DT) {
                        drainLevelUpChoices()
                        if (!isPaused && !pendingLevelUp) {
                            tick(FIXED_DT)
                        }
                        accumulator -= FIXED_DT
                    }

                    emitState()

                    val elapsed = (System.nanoTime() - now) / 1_000_000L
                    val sleep   = (1000L / TARGET_FPS) - elapsed
                    if (sleep > 0L) delay(sleep)

                } catch (e: CancellationException) {
                    // Normal coroutine cancellation — don't log as error
                    throw e
                } catch (e: Exception) {
                    // Any other exception in the game loop — log full details
                    Log.e(TAG, "═══ EXCEPTION IN GAME LOOP (frame $frameCount) ═══", e)
                    Log.e(TAG, "World state: ${world.getDiagnosticString()}")
                    Log.e(TAG, "elapsedTime=$elapsedTime isPaused=$isPaused pendingLevelUp=$pendingLevelUp")
                    Log.e(TAG, "frameEvents count=${frameEvents.size}")
                    frameEvents.forEachIndexed { i, ev -> Log.e(TAG, "  event[$i]: $ev") }
                    // Re-throw so the coroutine and app crash with the real exception
                    throw e
                }
            }

            Log.d(TAG, "Game loop ended normally.")
        }
    }

    fun stop() {
        Log.d(TAG, "Engine stopping at elapsed=${elapsedTime}s")
        isRunning = false
        engineJob?.cancel()
        engineJob = null
    }

    fun pause()  { isPaused = true  }
    fun resume() { isPaused = false }

    fun restart(scope: CoroutineScope) {
        Log.d(TAG, "Engine restarting")
        stop()
        world.clear()
        spawnSystem.reset()
        elapsedTime    = 0f
        isPaused       = false
        pendingLevelUp = false
        frameCount           = 0L
        cachedLevelUpOptions = emptyList()
        while (levelUpChannel.tryReceive().isSuccess) { /* discard */ }
        start(scope)
    }

    fun applyLevelUpChoice(option: LevelUpOption) {
        Log.d(TAG, "LevelUp choice queued: id=${option.id} type=${option.type}")
        levelUpChannel.trySend(option)
    }

    // ── Private — engine thread only ────────────────────────────────────────

    private fun drainLevelUpChoices() {
        var result = levelUpChannel.tryReceive()
        while (result.isSuccess) {
            val option = result.getOrNull() ?: break
            Log.d(TAG, "Applying level-up choice: ${option.id}")
            val pid = world.getPlayerEntity()
            if (pid != -1) applyUpgrade(world, pid, option)
            pendingLevelUp       = false
            cachedLevelUpOptions = emptyList()
            result = levelUpChannel.tryReceive()
        }
    }

    private fun initWorld() {
        world.clear()
        EntityFactory.createPlayer(world, WORLD_WIDTH / 2f, WORLD_HEIGHT / 2f)
        Log.d(TAG, "World initialised. Player entity: ${world.getPlayerEntity()}")
    }

    private fun tick(dt: Float) {
        frameCount++
        elapsedTime += dt
        frameEvents.clear()

        // Periodic stats log every LOG_EVERY frames
        if (frameCount % LOG_EVERY == 0L) {
            val pid    = world.getPlayerEntity()
            val player = world.players[pid]
            Log.d(TAG, buildString {
                append("── STATS @ ${elapsedTime.toInt()}s ──")
                append(" enemies=${world.enemies.size}")
                append(" projectiles=${world.projectiles.size}")
                append(" pickups=${world.pickups.size}")
                append(" particles=${world.particles.size}")
                append(" dmgNums=${world.damageNumbers.size}")
                append(" entities=${world.getLiveEntityCount()}")
                append(" level=${player?.level}")
                append(" xp=${player?.experience}/${player?.experienceToNextLevel}")
                append(" kills=${player?.killCount}")
                append(" pendingLevelUp=$pendingLevelUp")
            })
        }

        try {
            movementSystem.update(world, dt, joystick.getState())
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in movementSystem.update (frame $frameCount)", e)
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        try {
            weaponSystem.update(world, dt)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in weaponSystem.update (frame $frameCount)", e)
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        try {
            collisionSystem.update(world, dt, frameEvents)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in collisionSystem.update (frame $frameCount)", e)
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        val processed: List<GameEvent>
        try {
            processed = experienceSystem.processEvents(world, frameEvents, dt)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in experienceSystem.processEvents (frame $frameCount)", e)
            Log.e(TAG, "frameEvents: ${frameEvents.joinToString()}")
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        try {
            spawnSystem.update(world, dt, elapsedTime)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in spawnSystem.update (frame $frameCount)", e)
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        try {
            world.flushDestroyed()
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in world.flushDestroyed (frame $frameCount)", e)
            Log.e(TAG, "destroyedTags=${world.destroyedTags.size}: ${world.destroyedTags.take(20)}")
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        for (event in processed) {
            when (event) {
                is GameEvent.LevelUp -> {
                    Log.d(TAG, "LEVEL UP → level ${event.newLevel} at ${elapsedTime.toInt()}s")
                    if (!pendingLevelUp) {
                        // Generate options ONCE on the engine thread and cache them.
                        // emitState() will reuse the cache — no regeneration per frame.
                        val pid = world.getPlayerEntity()
                        cachedLevelUpOptions = try {
                            generateLevelUpOptions(world, pid)
                        } catch (e: Exception) {
                            Log.e(TAG, "CRASH generateLevelUpOptions", e); throw e
                        }
                        pendingLevelUp = true
                    }
                }
                is GameEvent.PlayerDied -> {
                    Log.d(TAG, "PLAYER DIED at ${elapsedTime.toInt()}s kills=${world.players[world.getPlayerEntity()]?.killCount}")
                    isPaused = true
                }
                else -> {}
            }
        }
    }

    private fun emitState() {
        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        val health = world.healths[pid]
        val pt     = world.transforms[pid]

        // Build immutable render snapshot on engine thread.
        // UI thread only reads this — never touches the live World.
        val snapshot = buildRenderSnapshot(pid, pt?.x ?: 0f, pt?.y ?: 0f)

        _gameState.value = GameState(
            isRunning        = isRunning && !isPaused,
            isPaused         = isPaused,
            isGameOver       = health?.isDead ?: false,
            isPendingLevelUp = pendingLevelUp,
            levelUpOptions   = cachedLevelUpOptions,  // reuse cached, never regenerate per frame
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
            renderSnapshot   = snapshot,
            events           = emptyList()
        )
    }

    private val snapshotEntities = ArrayList<RenderEntity>(512)

    private fun buildRenderSnapshot(playerId: Int, playerX: Float, playerY: Float): RenderSnapshot {
        snapshotEntities.clear()

        for (eid in world.getEnemySnapshot()) {
            val t = world.transforms[eid] ?: continue
            val r = world.renders[eid]    ?: continue
            val h = world.healths[eid]    ?: continue
            if (h.isDead) continue
            snapshotEntities.add(RenderEntity(
                kind = if (world.bosses.containsKey(eid)) RenderEntityKind.BOSS else RenderEntityKind.ENEMY,
                x = t.x, y = t.y, rotation = t.rotation,
                shape = r.shape, color = r.color, secondaryColor = r.secondaryColor,
                width = r.width, height = r.height, glowRadius = r.glowRadius, glowColor = r.glowColor,
                flashTimer = r.flashTimer, isFlashing = r.flashTimer > 0f,
                hpPercent = h.percentage, showHealthBar = h.percentage < 1f
            ))
        }

        for (pid in world.getProjectileSnapshot()) {
            val t    = world.transforms[pid]  ?: continue
            val r    = world.renders[pid]     ?: continue
            val proj = world.projectiles[pid] ?: continue
            snapshotEntities.add(RenderEntity(
                kind = RenderEntityKind.PROJECTILE,
                x = t.x, y = t.y, color = r.color, width = r.width, height = r.height,
                glowRadius = r.glowRadius, glowColor = r.glowColor, isCritical = proj.isCritical
            ))
        }

        for (pkId in world.getPickupSnapshot()) {
            val t = world.transforms[pkId] ?: continue
            val r = world.renders[pkId]    ?: continue
            snapshotEntities.add(RenderEntity(
                kind = RenderEntityKind.PICKUP,
                x = t.x, y = t.y, color = r.color, width = r.width, height = r.height,
                glowRadius = r.glowRadius, glowColor = r.glowColor
            ))
        }

        if (playerId != -1) {
            val t        = world.transforms[playerId]
            val r        = world.renders[playerId]
            val h        = world.healths[playerId]
            val auras    = world.auras[playerId]
            val orbitals = world.orbitals[playerId]
            if (t != null && r != null && h != null) {
                snapshotEntities.add(RenderEntity(
                    kind = RenderEntityKind.PLAYER,
                    x = t.x, y = t.y, color = r.color, secondaryColor = r.secondaryColor,
                    width = r.width, height = r.height, glowRadius = r.glowRadius, glowColor = r.glowColor,
                    flashTimer = r.flashTimer, isFlashing = r.flashTimer > 0f,
                    invincibleTimer = h.invincibleTimer,
                    hasAura = auras != null && auras.isNotEmpty(),
                    auraRadius = auras?.firstOrNull()?.radius ?: 0f
                ))
                orbitals?.forEach { orb ->
                    snapshotEntities.add(RenderEntity(
                        kind = RenderEntityKind.ORBITAL,
                        x = t.x, y = t.y, orbitAngle = orb.currentAngle,
                        orbitRadius = orb.orbitRadius, orbitSize = orb.size, color = 0xFFF9A825
                    ))
                }
            }
        }

        for (pId in world.getParticleSnapshot()) {
            val t = world.transforms[pId] ?: continue
            val p = world.particles[pId]  ?: continue
            snapshotEntities.add(RenderEntity(
                kind = RenderEntityKind.PARTICLE, x = t.x, y = t.y,
                color = p.color, width = p.size, height = p.size,
                lifetime = p.lifetime, maxLifetime = p.maxLifetime
            ))
        }

        for (dnId in world.getDamageNumberSnapshot()) {
            val t  = world.transforms[dnId]    ?: continue
            val dn = world.damageNumbers[dnId] ?: continue
            snapshotEntities.add(RenderEntity(
                kind = RenderEntityKind.DAMAGE_NUMBER, x = t.x, y = t.y,
                damageValue = dn.value, isCritical = dn.isCritical,
                lifetime = dn.lifetime, maxLifetime = dn.maxLifetime
            ))
        }

        return RenderSnapshot(
            cameraTargetX = playerX,
            cameraTargetY = playerY,
            entities      = ArrayList(snapshotEntities)
        )
    }

    // ── Upgrade generation & application — unchanged from original ──────────

    private fun generateLevelUpOptions(world: World, pid: Int): List<LevelUpOption> {
        val player  = world.players[pid]  ?: return emptyList()
        val weapons = world.weapons[pid]  ?: return emptyList()
        val ownedTypes = weapons.map { it.type }.toSet()
        val options = mutableListOf<LevelUpOption>()

        for (weapon in weapons) {
            if (weapon.level < 8) {
                val rarity = when {
                    weapon.level >= 6 -> UpgradeRarity.EPIC
                    weapon.level >= 3 -> UpgradeRarity.RARE
                    else              -> UpgradeRarity.COMMON
                }
                options.add(LevelUpOption(
                    id = "upgrade_${weapon.type.name}",
                    title = weaponUpgradeTitle(weapon.type, weapon.level + 1),
                    description = weaponUpgradeDesc(weapon.level + 1),
                    type = UpgradeType.WEAPON_UPGRADE,
                    weaponType = weapon.type,
                    icon = weaponIcon(weapon.type),
                    rarity = rarity
                ))
            }
        }

        com.kotlinsurvivors.engine.ecs.components.WeaponType.values()
            .filter { it !in ownedTypes && weapons.size < 6 }
            .take(2)
            .forEach { wt ->
                options.add(LevelUpOption(
                    id = "new_${wt.name}", title = "New: ${weaponName(wt)}",
                    description = weaponDescription(wt),
                    type = UpgradeType.NEW_WEAPON, weaponType = wt,
                    icon = weaponIcon(wt), rarity = UpgradeRarity.RARE
                ))
            }

        options.addAll(listOf(
            LevelUpOption("stat_hp",       "Vital Boost",   "+30 Max HP",        UpgradeType.STAT, icon = "❤️",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_speed",    "Swift Feet",    "+15% Move Speed",   UpgradeType.STAT, icon = "👟",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_damage",   "Power Surge",   "+20% Damage",       UpgradeType.STAT, icon = "⚔️",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_area",     "Wide Strike",   "+15% Area",         UpgradeType.STAT, icon = "🌐",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_cooldown", "Rapid Fire",    "-10% Cooldowns",    UpgradeType.STAT, icon = "⚡",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_regen",    "Regeneration",  "+1 HP/sec Regen",   UpgradeType.STAT, icon = "🔋",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_magnet",   "Magnetism",     "+50% Pickup Range", UpgradeType.STAT, icon = "🧲",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_crit",     "Sharpness",     "+10% Crit Chance",  UpgradeType.STAT, icon = "💥",  rarity = UpgradeRarity.EPIC),
            LevelUpOption("stat_armor",    "Iron Skin",     "+1 Armor",          UpgradeType.STAT, icon = "🛡️",  rarity = UpgradeRarity.COMMON),
        ))

        val shuffled = options.shuffled()
        val result   = shuffled.take(3).toMutableList()
        if (player.level >= 5 && result.none { it.rarity.ordinal >= UpgradeRarity.RARE.ordinal }) {
            shuffled.firstOrNull { it.rarity.ordinal >= UpgradeRarity.RARE.ordinal }?.let { result[2] = it }
        }
        return result
    }

    private fun applyUpgrade(world: World, pid: Int, option: LevelUpOption) {
        val player  = world.players[pid]  ?: return
        val health  = world.healths[pid]  ?: return
        val weapons = world.weapons[pid]  ?: return

        Log.d(TAG, "Applying upgrade: ${option.id} (${option.type})")

        when (option.type) {
            UpgradeType.WEAPON_UPGRADE -> {
                val weapon = weapons.find { it.type == option.weaponType } ?: return
                weapon.level++
                weapon.damage = (weapon.damage * 1.2f).roundToInt()
                weapon.cooldown *= 0.92f
                if (weapon.level % 3 == 0) weapon.projectileCount++
                if (weapon.level % 4 == 0) weapon.piercing++
                weapon.area *= 1.1f
                Log.d(TAG, "Weapon ${weapon.type} upgraded to Lv${weapon.level} dmg=${weapon.damage}")
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
                    Log.d(TAG, "New weapon added: $wt. Total weapons: ${weapons.size}")
                }
            }
            UpgradeType.STAT -> {
                when (option.id) {
                    "stat_hp"       -> health.current += 30
                    "stat_speed"    -> player.speed *= 1.15f
                    "stat_damage"   -> player.damageMultiplier *= 1.20f
                    "stat_area"     -> player.areaMultiplier *= 1.15f
                    "stat_cooldown" -> player.attackSpeedMultiplier *= 1.10f
                    "stat_regen"    -> player.regenPerSecond += 1f
                    "stat_magnet"   -> { player.magnetRadius *= 1.5f; player.pickupRadius *= 1.5f }
                    "stat_crit"     -> player.criticalChance = (player.criticalChance + 0.10f).coerceAtMost(0.80f)
                    "stat_armor"    -> player.armor += 1
                }
            }
        }
    }

    private fun newWeaponComponent(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) =
        com.kotlinsurvivors.engine.ecs.components.WeaponComponent(
            type = type,
            damage = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> 10
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> 8
                com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> 12
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> 15
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> 25
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> 20
                com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> 18
                com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> 22
                else -> 0
            },
            cooldown = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> 0.7f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> 1.4f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> 1.2f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> 1.8f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> 1.5f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> 1.6f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> 1.3f
                else -> 999f
            },
            projectileSpeed = if (type == com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE) 600f else 350f,
            projectileSize  = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE   -> 18f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE -> 8f
                else -> 13f
            },
            piercing = when (type) {
                com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND -> 3
                com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE     -> 2
                else -> 1
            }
        )

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
    private fun weaponUpgradeTitle(type: com.kotlinsurvivors.engine.ecs.components.WeaponType, lvl: Int) = "${weaponName(type)} Lv.$lvl"
    private fun weaponUpgradeDesc(lvl: Int) = when {
        lvl % 4 == 0 -> "+1 Projectile, +Damage"
        lvl % 3 == 0 -> "+1 Pierce, +Speed"
        lvl % 2 == 0 -> "+Area, -Cooldown"
        else -> "+Damage"
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

enum class UpgradeRarity { COMMON, RARE, EPIC, LEGENDARY }
