package com.kotlinsurvivors.engine

import android.util.Log
import com.kotlinsurvivors.core.crash.CrashLogger
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.systems.*
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kotlinsurvivors.engine.rendering.RenderBuffer
import com.kotlinsurvivors.engine.rendering.RenderKind
import com.kotlinsurvivors.engine.rendering.RenderSnapshot
import com.kotlinsurvivors.engine.ecs.components.VelocityComponent
import com.kotlinsurvivors.engine.rendering.Facing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class GameEngine(
    private val viewportWidth : Float,
    private val viewportHeight: Float,
    /** Levels of the shop's permanent upgrades, read at the start of every run. */
    private val loadPermanentLevels: suspend () -> Map<String, Int> = { emptyMap() },
    /**
     * Collision grid of the level to play. Null (the default) means the old open arena:
     * no obstacles and the player starts in the middle of the world.
     */
    private val loadLevelMap: suspend () -> LevelMap? = { null }
) {
    companion object {
        const val TARGET_FPS     = 60
        const val FIXED_DT       = 1f / TARGET_FPS
        const val MAX_FRAME_TIME = 0.05f
        const val WORLD_WIDTH    = 4096f
        const val WORLD_HEIGHT   = 4096f
        private const val RENDER_BUFFER_COUNT = 8
        private const val FACING_MIN_SPEED_SQ  = 25f   // below 5 px/s the player counts as standing still

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
    @Volatile private var isPaused  = false
    @Volatile private var isRunning = false
    private var pendingLevelUp = false
    private val frameEvents    = mutableListOf<GameEvent>()
    private var frameCount     = 0L
    private var hitCount       = 0
    private var lastHitTime    = 0f

    // Bookkeeping for emitState(): what was published last, and whether the
    // simulation has advanced since.
    private var tickedSinceEmit           = false
    private var hasEmitted                = false
    private var lastEmittedPaused         = false
    private var lastEmittedPendingLevelUp = false
    private var cachedLevelUpOptions: List<LevelUpOption> = emptyList()

    private val levelUpChannel = Channel<LevelUpOption>(capacity = 4)

    private var engineJob: Job? = null
    private val engineDispatcher = newSingleThreadContext("GameEngine")

    // ── Public API ──────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) = launchLoop(scope, previous = null)

    /**
     * Starts the game loop on the engine thread.
     *
     * The world is reset and populated from inside the coroutine, so only the
     * engine thread ever touches it. When restarting, [previous] is the loop
     * being replaced: the new loop waits for it to finish before resetting
     * anything, otherwise the old tick could still be running on the world.
     *
     * Uncaught exceptions are recorded by CrashLogger's global handler, which
     * is installed once in Application.onCreate().
     */
    private fun launchLoop(scope: CoroutineScope, previous: Job?) {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Engine starting. Viewport: ${viewportWidth}x${viewportHeight}")

        engineJob = scope.launch(engineDispatcher) {
            previous?.join()
            resetState()
            initWorld(readPermanentLevels(), readLevelMap())

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
                    CrashLogger.logCrash("game loop (frame $frameCount, t=${elapsedTime.toInt()}s)", e)
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
        CrashLogger.log("Engine stopping at ${elapsedTime}s")
        CrashLogger.flush()
        CrashLogger.attachWorld(null)
        isRunning = false
        engineJob?.cancel()
        engineJob = null
    }

    fun pause()  { isPaused = true  }
    fun resume() { isPaused = false }

    fun restart(scope: CoroutineScope) {
        Log.d(TAG, "Engine restarting")
        val previous = engineJob
        stop()
        launchLoop(scope, previous)
    }

    /**
     * Stops the loop and shuts down the engine thread. Call once, when the
     * engine is no longer needed. stop() alone keeps the thread alive so that
     * restart() can reuse it.
     */
    fun release() {
        stop()
        engineDispatcher.close()
    }

    fun applyLevelUpChoice(option: LevelUpOption) {
        Log.d(TAG, "LevelUp choice queued: id=${option.id} type=${option.type}")
        levelUpChannel.trySend(option)
    }

    // ── Private — engine thread only ────────────────────────────────────────

    /**
     * Applies the player's level-up pick. Only one upgrade is granted per
     * level-up: extra taps that arrive while no choice is pending (double tap,
     * a tap on the overlay while it fades out) are discarded.
     */
    private fun drainLevelUpChoices() {
        var result = levelUpChannel.tryReceive()
        while (result.isSuccess) {
            val option = result.getOrNull() ?: break
            if (pendingLevelUp) {
                Log.d(TAG, "Applying level-up choice: ${option.id}")
                val pid = world.getPlayerEntity()
                if (pid != -1) {
                    applyUpgrade(world, pid, option)
                    CrashLogger.log("LEVEL-UP choice '${option.id}' (${option.type}) -> ${loadoutSummary(pid)}")
                }
                pendingLevelUp       = false
                cachedLevelUpOptions = emptyList()
            } else {
                Log.d(TAG, "Ignoring level-up choice '${option.id}': none pending")
            }
            result = levelUpChannel.tryReceive()
        }
    }

    /** One-line description of what the player currently owns, for the log. */
    private fun loadoutSummary(pid: Int): String {
        val weapons  = world.weapons[pid]?.joinToString { "${it.type}:L${it.level}" } ?: "none"
        val auras    = world.auras[pid]?.size ?: 0
        val orbitals = world.orbitals[pid]?.size ?: 0
        return "weapons=[$weapons] auras=$auras orbitals=$orbitals"
    }

    /** Puts every engine-owned field back to its initial value. Engine thread only. */
    private fun resetState() {
        spawnSystem.reset()
        elapsedTime          = 0f
        isPaused             = false
        pendingLevelUp       = false
        frameCount           = 0L
        cachedLevelUpOptions = emptyList()
        hitCount             = 0
        lastHitTime          = 0f
        playerFacing         = Facing.DOWN
        playerMoving         = false
        tickedSinceEmit      = false
        hasEmitted           = false
        frameEvents.clear()
        while (levelUpChannel.tryReceive().isSuccess) { /* discard stale choices */ }
    }

    /** A failing database must not stop the game from starting: play without the bonuses. */
    private suspend fun readPermanentLevels(): Map<String, Int> =
        try {
            loadPermanentLevels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.logCrash("loading permanent upgrades", e)
            emptyMap()
        }

    /** A missing or broken map file must not stop the game from starting. */
    private suspend fun readLevelMap(): LevelMap? =
        try {
            loadLevelMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.logCrash("loading the level map", e)
            null
        }

    private fun initWorld(permanentLevels: Map<String, Int>, map: LevelMap?) {
        world.clear()
        movementSystem.levelMap = map
        spawnSystem.levelMap    = map
        // With a map the run starts just outside the house door, otherwise in the middle.
        val startX = if (map != null) Level1.SPAWN_X else WORLD_WIDTH  / 2f
        val startY = if (map != null) Level1.SPAWN_Y else WORLD_HEIGHT / 2f
        val playerId = EntityFactory.createPlayer(world, startX, startY)
        val applied = PermanentBonuses.applyTo(world, playerId, permanentLevels)
        CrashLogger.log("Permanent upgrades applied: " + (if (applied.isEmpty()) "none" else applied.entries.joinToString { "${it.key}:${it.value}" }))
        CrashLogger.attachWorld(world)
        CrashLogger.log("Engine init: viewport=${viewportWidth}x${viewportHeight}")
        Log.d(TAG, "World initialised. Player entity: ${world.getPlayerEntity()}")
    }

    private fun tick(dt: Float) {
        frameCount++
        elapsedTime += dt
        tickedSinceEmit = true
        frameEvents.clear()

        // Periodic stats log every LOG_EVERY frames
        if (frameCount % LOG_EVERY == 0L) {
            val pid    = world.getPlayerEntity()
            val player = world.players[pid]
            CrashLogger.logStats(
                elapsedTime = elapsedTime,
                frameCount  = frameCount,
                level       = player?.level ?: 0,
                xp          = player?.experience ?: 0,
                xpNext      = player?.experienceToNextLevel ?: 0,
                kills       = player?.killCount ?: 0
            )
            // Flush to disk every 30 seconds (10 × 3s LOG_EVERY intervals)
            if (frameCount % (LOG_EVERY * 10) == 0L) CrashLogger.flush()
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
                            CrashLogger.logCrash("generateLevelUpOptions", e); throw e
                        }
                        CrashLogger.log(
                            "LEVEL-UP offered at level ${event.newLevel}: " +
                                cachedLevelUpOptions.joinToString { it.id }
                        )
                        pendingLevelUp = true
                    }
                }
                is GameEvent.PlayerHit -> {
                    hitCount++
                    lastHitTime = elapsedTime
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
        // While paused, in a level-up or after game over nothing changes, so
        // there is nothing new to publish. Skipping keeps the buffer the UI is
        // drawing from being recycled by frames that would look identical.
        val flagsChanged = !hasEmitted ||
            isPaused != lastEmittedPaused ||
            pendingLevelUp != lastEmittedPendingLevelUp
        if (!tickedSinceEmit && !flagsChanged) return
        tickedSinceEmit           = false
        hasEmitted                = true
        lastEmittedPaused         = isPaused
        lastEmittedPendingLevelUp = pendingLevelUp

        val pid    = world.getPlayerEntity()
        val player = world.players[pid]
        val health = world.healths[pid]
        val pt     = world.transforms[pid]

        renderBufferIndex = (renderBufferIndex + 1) % renderBuffers.size
        renderBuffer      = renderBuffers[renderBufferIndex]

        // Fill the next buffer on the engine thread.
        // UI thread only reads it — never touches the live World.
        buildRenderSnapshot(pid, pt?.x ?: 0f, pt?.y ?: 0f)

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
            hitCount         = hitCount,
            bossKills        = player?.bossKills ?: 0,
            weaponCount      = world.weapons[pid]?.size ?: 0,
            secondsWithoutHit = (elapsedTime - lastHitTime).toInt(),
            renderSnapshot   = RenderSnapshot(renderBuffer),
            events           = emptyList()
        )
    }

    /**
     * The UI thread draws from the buffer carried by the last emitted state
     * while the engine already writes the next frame. Rotating through several
     * buffers keeps the one being drawn untouched for a number of frames, so the
     * renderer never reads a half-written one even when the UI thread stalls for
     * a moment (for example while a menu is being composed).
     */
    private val renderBuffers     = Array(RENDER_BUFFER_COUNT) { RenderBuffer() }
    private var renderBufferIndex = 0
    private var renderBuffer      = renderBuffers[0]

    // Direction the character sprite faces. Kept between frames so the player
    // keeps looking the way they last walked when they stop.
    private var playerFacing = Facing.DOWN
    private var playerMoving = false

    private fun updatePlayerFacing(v: VelocityComponent?) {
        val vx = v?.vx ?: 0f
        val vy = v?.vy ?: 0f
        playerMoving = vx * vx + vy * vy > FACING_MIN_SPEED_SQ
        if (!playerMoving) return
        playerFacing = Facing.of(vx, vy)
    }

    private fun buildRenderSnapshot(playerId: Int, playerX: Float, playerY: Float) {
        renderBuffer.reset()
        renderBuffer.cameraTargetX = playerX
        renderBuffer.cameraTargetY = playerY

        for (eid in world.getEnemySnapshot()) {
            val t = world.transforms[eid] ?: continue
            val r = world.renders[eid]    ?: continue
            val h = world.healths[eid]    ?: continue
            if (h.isDead) continue
            val isBoss = world.bosses.containsKey(eid)
            var f = 0
            if (r.flashTimer > 0f) f = f or RenderBuffer.FLAG_FLASHING
            if (h.percentage < 1f) f = f or RenderBuffer.FLAG_SHOW_HP_BAR
            if (isBoss)            f = f or RenderBuffer.FLAG_IS_BOSS
            renderBuffer.write(
                kindOrdinal  = if (isBoss) RenderKind.BOSS else RenderKind.ENEMY,
                ex = t.x, ey = t.y, eRotation = t.rotation,
                eShape = r.shape, eColor = r.color, eSecColor = r.secondaryColor,
                eWidth = r.width, eHeight = r.height,
                eGlowRadius = r.glowRadius, eGlowColor = r.glowColor,
                eFlashTimer = r.flashTimer, eHpPercent = h.percentage, eFlags = f,
                eSpriteId = world.enemies[eid]?.type?.ordinal ?: -1
            )
        }

        for (pid in world.getProjectileSnapshot()) {
            val t    = world.transforms[pid]  ?: continue
            val r    = world.renders[pid]     ?: continue
            val proj = world.projectiles[pid] ?: continue
            val f    = if (proj.isCritical) RenderBuffer.FLAG_IS_CRITICAL else 0
            val v    = world.velocities[pid]
            val angle = if (v != null && (v.vx != 0f || v.vy != 0f)) atan2(v.vy, v.vx) else 0f
            renderBuffer.write(
                kindOrdinal = RenderKind.PROJECTILE,
                ex = t.x, ey = t.y, eRotation = angle,
                eColor = r.color, eWidth = r.width, eHeight = r.height,
                eGlowRadius = r.glowRadius, eGlowColor = r.glowColor, eFlags = f,
                eSpriteId = proj.weaponType.ordinal
            )
        }

        for (pkId in world.getPickupSnapshot()) {
            val t = world.transforms[pkId] ?: continue
            val r = world.renders[pkId]    ?: continue
            renderBuffer.write(
                kindOrdinal = RenderKind.PICKUP,
                ex = t.x, ey = t.y,
                eColor = r.color, eWidth = r.width, eHeight = r.height,
                eGlowRadius = r.glowRadius, eGlowColor = r.glowColor,
                eSpriteId = world.pickups[pkId]?.type?.ordinal ?: -1
            )
        }

        if (playerId != -1) {
            val t        = world.transforms[playerId]
            val r        = world.renders[playerId]
            val h        = world.healths[playerId]
            val auras    = world.auras[playerId]
            val orbitals = world.orbitals[playerId]
            if (t != null && r != null && h != null) {
                var f = 0
                if (r.flashTimer > 0f)                   f = f or RenderBuffer.FLAG_FLASHING
                if (auras != null && auras.isNotEmpty()) f = f or RenderBuffer.FLAG_HAS_AURA
                updatePlayerFacing(world.velocities[playerId])
                if (playerMoving) f = f or RenderBuffer.FLAG_MOVING
                renderBuffer.write(
                    kindOrdinal  = RenderKind.PLAYER,
                    ex = t.x, ey = t.y,
                    eColor = r.color, eSecColor = r.secondaryColor,
                    eWidth = r.width, eHeight = r.height,
                    eGlowRadius = r.glowRadius, eGlowColor = r.glowColor,
                    eFlashTimer = r.flashTimer, eFlags = f,
                    eInvincTimer = h.invincibleTimer,
                    eAuraRadius  = auras?.firstOrNull()?.radius ?: 0f,
                    eSpriteId    = playerFacing
                )
                orbitals?.forEach { orb ->
                    renderBuffer.write(
                        kindOrdinal  = RenderKind.ORBITAL,
                        ex = t.x, ey = t.y, eColor = 0xFFF9A825L,
                        eOrbitAngle = orb.currentAngle,
                        eOrbitRadius = orb.orbitRadius, eOrbitSize = orb.size
                    )
                }
            }
        }

        for (pId in world.getParticleSnapshot()) {
            val t = world.transforms[pId] ?: continue
            val p = world.particles[pId]  ?: continue
            renderBuffer.write(
                kindOrdinal = RenderKind.PARTICLE,
                ex = t.x, ey = t.y, eColor = p.color,
                eWidth = p.size, eHeight = p.size,
                eLifetime = p.lifetime, eMaxLifetime = p.maxLifetime
            )
        }

        for (dnId in world.getDamageNumberSnapshot()) {
            val t  = world.transforms[dnId]    ?: continue
            val dn = world.damageNumbers[dnId] ?: continue
            val f  = if (dn.isCritical) RenderBuffer.FLAG_IS_CRITICAL else 0
            renderBuffer.write(
                kindOrdinal  = RenderKind.DAMAGE_NUMBER,
                ex = t.x, ey = t.y,
                eLifetime = dn.lifetime, eMaxLifetime = dn.maxLifetime,
                eDamageValue = dn.value, eFlags = f
            )
        }
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

        WeaponOffers.pickNewWeapons(ownedTypes)
            .forEach { wt ->
                options.add(LevelUpOption(
                    id = "new_${wt.name}", title = "New: ${weaponName(wt)}",
                    description = weaponDescription(wt),
                    type = UpgradeType.NEW_WEAPON, weaponType = wt,
                    icon = weaponIcon(wt), rarity = UpgradeRarity.RARE
                ))
            }

        options.addAll(listOf(
            LevelUpOption("stat_hp",       "Vital Boost",   "+30 Max HP",        UpgradeType.STAT, icon = "heart",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_speed",    "Swift Feet",    "+15% Move Speed",   UpgradeType.STAT, icon = "boots",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_damage",   "Power Surge",   "+20% Damage",       UpgradeType.STAT, icon = "sword",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_area",     "Wide Strike",   "+15% Area",         UpgradeType.STAT, icon = "globe",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_cooldown", "Rapid Fire",    "-10% Cooldowns",    UpgradeType.STAT, icon = "bolt",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_regen",    "Regeneration",  "+1 HP/sec Regen",   UpgradeType.STAT, icon = "battery",  rarity = UpgradeRarity.COMMON),
            LevelUpOption("stat_magnet",   "Magnetism",     "+50% Pickup Range", UpgradeType.STAT, icon = "magnet",  rarity = UpgradeRarity.RARE),
            LevelUpOption("stat_crit",     "Sharpness",     "+10% Crit Chance",  UpgradeType.STAT, icon = "burst",  rarity = UpgradeRarity.EPIC),
            LevelUpOption("stat_armor",    "Iron Skin",     "+1 Armor",          UpgradeType.STAT, icon = "shield",  rarity = UpgradeRarity.COMMON),
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
                WeaponUpgrades.applyToAttachedComponents(world, pid, weapon)
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
                    "stat_hp"       -> { health.max += 30; health.current += 30 }
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
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "Fires a flame projectile."
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
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "wand"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "knife"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "cross"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "fire"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "axe"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "bolt"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "garlic"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "water"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "whip"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "bible"
    }
}

enum class UpgradeRarity { COMMON, RARE, EPIC, LEGENDARY }
