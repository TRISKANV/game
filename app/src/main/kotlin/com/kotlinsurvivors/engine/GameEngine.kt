package com.kotlinsurvivors.engine

import com.kotlinsurvivors.engine.ecs.systems.ComboTracker
import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.engine.ecs.components.AIState
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PatrolState
import com.kotlinsurvivors.core.audio.SoundEffects
import android.util.Log
import com.kotlinsurvivors.core.crash.CrashLogger
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.systems.*
import com.kotlinsurvivors.engine.input.VirtualJoystick
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.Level1
import com.kotlinsurvivors.engine.map.Level2
import com.kotlinsurvivors.engine.map.Level3
import com.kotlinsurvivors.engine.map.LevelFlow
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.engine.map.Levels
import com.kotlinsurvivors.features.game.domain.model.LevelNotice
import com.kotlinsurvivors.features.game.domain.model.GameState
import com.kotlinsurvivors.features.game.domain.model.StationPhase
import com.kotlinsurvivors.features.game.domain.model.LevelUpOption
import com.kotlinsurvivors.features.game.domain.model.UpgradeType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.rendering.RenderBuffer
import com.kotlinsurvivors.engine.rendering.RenderKind
import com.kotlinsurvivors.engine.rendering.RenderSnapshot
import com.kotlinsurvivors.engine.ecs.components.VelocityComponent
import com.kotlinsurvivors.engine.lab.LabActions
import com.kotlinsurvivors.engine.lab.LabCommand
import com.kotlinsurvivors.engine.rendering.Facing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.math.PI
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
    private val loadLevelMap: suspend () -> LevelMap? = { null },
    /** Where the sound effects go. Silent by default, so tests and tools need no audio. */
    private val sounds: SoundEffects = SoundEffects.None,
    /**
     * The laboratory: nothing spawns by itself, the player cannot die (until the tester says so)
     * and the tester's [LabCommand]s are applied. Never set in a real run.
     */
    private val laboratory: Boolean = false,
    /** Collision grid of Level 2, read at the start of the run so crossing over never waits on a file. */
    private val loadNextLevelMap: suspend () -> LevelMap? = { null },
    private val loadStationMap: suspend () -> LevelMap? = { null }
) {
    companion object {
        const val TARGET_FPS     = 60
        /** Sound for reaching each combo tier, indexed by tier (tier 0 never sounds). */
        private val COMBO_SOUNDS = arrayOf(Sound.UI_CLICK, Sound.COMBO_1, Sound.COMBO_2, Sound.COMBO_3, Sound.COMBO_4)
        const val FIXED_DT       = 1f / TARGET_FPS
        const val MAX_FRAME_TIME = 0.05f
        const val WORLD_WIDTH    = 4096f
        const val WORLD_HEIGHT   = 4096f
        const val MAX_WEAPON_LEVEL = 8
        private const val EVENT_BANNER_SECONDS = 2.5f
        private const val LEVEL_NOTICE_SECONDS = 3.2f
        private const val STATION_LEVEL = 3
        private const val RIOT_SHARE = 0.18f
        private const val SIREN_EVERY_SECONDS = 2.4f
        private const val REINFORCEMENT_MIN_DISTANCE = 380f
        private const val STATION_RETURN_BELOW = 110f      // where the player stands when he comes out of the station
        /** How close to the wanted poster the player has to stand, and for how long, before the officers recognise him. */
        private const val POSTER_RADIUS = 170f
        private const val POSTER_LOOK_SECONDS = 2.5f
        private const val RENDER_BUFFER_COUNT = 8
        private const val DOG_RUN_SPEED_SQ     = 10_000f   // above 100 px/s the dog gallops
        private const val FACING_MIN_SPEED_SQ  = 25f   // below 5 px/s the player counts as standing still

        // Logging
        private const val TAG       = "KS_Engine"
        private const val LOG_EVERY = 180 // log stats every 3 seconds (180 frames at 60fps)
    }

    val world = World()

    private val movementSystem   = MovementSystem(WORLD_WIDTH, WORLD_HEIGHT)
    private val collisionSystem  = CollisionSystem()
    private val weaponSystem     = WeaponSystem(sounds)
    private val spawnSystem      = SpawnSystem(viewportWidth, viewportHeight)
    private val experienceSystem = ExperienceSystem()
    private val eventDirector    = EventDirector(spawnSystem, WORLD_WIDTH, viewportWidth, viewportHeight)

    val joystick = VirtualJoystick()

    // ── Levels ──────────────────────────────────────────────────────────────
    private val levelFlow = LevelFlow()
    private var nextLevelMap: LevelMap? = null
    private var stationMap: LevelMap? = null
    /** Seconds the player has spent in front of the wanted poster, and whether the officers already know him. */
    private var posterTimer = 0f
    private var copsAlarmed = false

    /** The siege of the station: the alarm, the quota of officers, the chief and the key. */
    private val siege = StationSiege()
    private var chiefId = -1
    private var chiefX = 0f
    private var chiefY = 0f
    private var sirenTimer = 0f
    private var levelNotice: LevelNotice? = null
    private var levelNoticeTimer = 0f
    /** While the screen fades out at the end of the street the character keeps walking south, on his own. */
    private val autoWalkSouth = JoystickState(isActive = true, dx = 0f, dy = 1f, rawX = 0f, rawY = 1f)
    private val standStill    = JoystickState(isActive = true, dx = 0f, dy = 0f, rawX = 0f, rawY = 0f)

    /** What the character does by himself while the screen fades out: he walks on through the way out, or waits at a door. */
    private fun tripInput(): JoystickState =
        if (levelFlow.level == 2 && levelFlow.target == STATION_LEVEL) standStill else autoWalkSouth

    // ── Laboratory ──────────────────────────────────────────────────────────
    private val labQueue = java.util.concurrent.ConcurrentLinkedQueue<LabCommand>()
    private var labInvincible = true

    init { eventDirector.autoStart = !laboratory }

    /** Queues a laboratory command; it runs on the engine thread at the start of the next tick. Ignored in a real run. */
    fun sendLabCommand(command: LabCommand) {
        if (laboratory) labQueue.add(command)
    }

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private var elapsedTime    = 0f
    @Volatile private var isPaused  = false
    @Volatile private var isRunning = false
    /** True while the level-up roulette is open; the simulation is frozen for as long as it is. */
    private var pendingLevelUp = false
    /** Levels gained whose roulette the player has not opened yet. The game keeps running meanwhile. */
    private var levelUpsWaiting = 0

    // A boss chest was opened and the game waits, frozen, until the player has seen what it holds.
    private var pendingChest: ChestReward? = null
    @Volatile private var chestSeen = false

    // The event (special wave, mini-boss) just announced, shown for EVENT_BANNER_SECONDS.
    private var eventBanner: EventKind? = null
    private var eventBannerTimer = 0f
    private val directorEvents = mutableListOf<GameEvent>()
    private val combo = ComboTracker()
    @Volatile private var openRouletteRequested = false
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
    private var lastEmittedChest = false
    private var lastEmittedLevelUpsWaiting = 0
    private var cachedLevelUpOptions: List<LevelUpOption> = emptyList()
    private var cachedLevelUpWinner = 0

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
            nextLevelMap = readNextLevelMap()
            stationMap   = readStationMap()

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
                        openRouletteIfRequested()
                        finishChestIfSeen()
                        if (!isPaused && !pendingLevelUp && pendingChest == null) {
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

    /**
     * The player tapped the level-up button: open the roulette for one of the levels waiting.
     * Ignored when none is waiting or the roulette is already open.
     */
    fun openLevelUp() {
        openRouletteRequested = true
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
                levelUpsWaiting      = (levelUpsWaiting - 1).coerceAtLeast(0)
                cachedLevelUpOptions = emptyList()
                cachedLevelUpWinner  = 0
            } else {
                Log.d(TAG, "Ignoring level-up choice '${option.id}': none pending")
            }
            result = levelUpChannel.tryReceive()
        }
    }

    /**
     * Draws the roulette when the player asked for it. The prizes are worked out now, not
     * when the level was gained, so they match the weapons the player has at this moment.
     */
    private fun openRouletteIfRequested() {
        if (!openRouletteRequested) return
        openRouletteRequested = false
        if (pendingLevelUp || levelUpsWaiting <= 0) return
        val pid = world.getPlayerEntity()
        if (pid == -1 || world.healths[pid]?.isDead == true) return
        val spin = try {
            LevelUpRoulette.spin(levelUpPool(world, pid))
        } catch (e: Exception) {
            CrashLogger.logCrash("level-up roulette", e); throw e
        }
        cachedLevelUpOptions = spin.segments
        cachedLevelUpWinner  = spin.winnerIndex
        pendingLevelUp       = true
        CrashLogger.log("LEVEL-UP opened ($levelUpsWaiting waiting): roulette lands on '${spin.winner.id}'")
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
        levelUpsWaiting      = 0
        pendingChest         = null
        chestSeen            = false
        eventBanner          = null
        eventBannerTimer     = 0f
        levelFlow.reset()
        posterTimer          = 0f
        copsAlarmed          = false
        movementSystem.copRoutes.clear()
        siege.reset()
        chiefId              = -1
        sirenTimer           = 0f
        lockStationDoor(false)
        levelNotice          = null
        levelNoticeTimer     = 0f
        directorEvents.clear()
        eventDirector.reset()
        eventDirector.autoStart = !laboratory
        labQueue.clear()
        labInvincible = true
        combo.reset()
        openRouletteRequested = false
        frameCount           = 0L
        cachedLevelUpOptions = emptyList()
        cachedLevelUpWinner  = 0
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

    private suspend fun readNextLevelMap(): LevelMap? =
        try {
            loadNextLevelMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.logCrash("loading the map of level 2", e)
            null
        }

    private suspend fun readStationMap(): LevelMap? =
        try {
            loadStationMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogger.logCrash("loading the map of the police station", e)
            null
        }

    private fun initWorld(permanentLevels: Map<String, Int>, map: LevelMap?) {
        world.clear()
        // The end of the street starts shut: its barrier opens when the first boss falls.
        map?.setRect(Level1.EXIT_X0, Level1.BARRIER_Y0, Level1.EXIT_X1, Level1.BARRIER_Y1, true)
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
        if (eventBannerTimer > 0f) {
            eventBannerTimer -= dt
            if (eventBannerTimer <= 0f) eventBanner = null
        }
        tickedSinceEmit = true
        frameEvents.clear()
        if (laboratory) drainLabCommands()

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
            movementSystem.update(world, dt, if (levelFlow.phase == LevelFlow.Phase.LEAVING) tripInput() else joystick.getState())
            if (movementSystem.consumeAlarms() > 0) sounds.play(Sound.DOG_BARK)
            if (movementSystem.consumeStationAlarm()) { sounds.play(Sound.EVENT_MINIBOSS); startSiege() }
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

        if (laboratory && labInvincible) LabActions.healFull(world)
        playEventSounds(frameEvents)
        combo.update(dt)
        world.players[world.getPlayerEntity()]?.let(combo::applyTo)

        val processed: List<GameEvent>
        try {
            processed = experienceSystem.processEvents(world, frameEvents, dt)
        } catch (e: Exception) {
            Log.e(TAG, "CRASH in experienceSystem.processEvents (frame $frameCount)", e)
            Log.e(TAG, "frameEvents: ${frameEvents.joinToString()}")
            Log.e(TAG, world.getDiagnosticString()); throw e
        }

        try {
            if (levelFlow.level == STATION_LEVEL) updateStation(dt)
            spawnSystem.riotShare = if (levelFlow.level == 2 && siege.phase == StationPhase.OPEN) RIOT_SHARE else 0f
            settleRiots()
            if (levelFlow.level != STATION_LEVEL && levelIsHostile()) {
                if (!laboratory && spawnSystem.update(world, dt, elapsedTime).bossSpawned) sounds.play(Sound.BOSS_APPEAR)
                eventDirector.update(world, dt, elapsedTime, directorEvents)
                announceEvents()
            }
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

        updateLevelFlow(dt)

        for (event in processed) siegeEvent(event)
        for (event in processed) {
            when (event) {
                is GameEvent.LevelUp -> {
                    sounds.play(Sound.LEVEL_READY)
                    Log.d(TAG, "LEVEL UP → level ${event.newLevel} at ${elapsedTime.toInt()}s")
                    // Only counted: the roulette opens when the player taps the level-up button.
                    levelUpsWaiting++
                    CrashLogger.log("LEVEL-UP gained: level ${event.newLevel}, $levelUpsWaiting waiting")
                }
                is GameEvent.ChestDropped -> sounds.play(Sound.CHEST_APPEAR)
                is GameEvent.ChestOpened -> openChest()
                is GameEvent.PlayerHit -> {
                    hitCount++
                    lastHitTime = elapsedTime
                }
                is GameEvent.PlayerDied -> {
                    sounds.play(Sound.GAME_OVER)
                    Log.d(TAG, "PLAYER DIED at ${elapsedTime.toInt()}s kills=${world.players[world.getPlayerEntity()]?.killCount}")
                    isPaused = true
                }
                else -> {}
            }
        }
    }

    /**
     * Opens the way out of Level 1 once the first boss is down, and carries the run over to Level 2
     * when the player walks out: the screen fades to black, the next level is put in place and the
     * screen fades back in.
     */
    private fun updateLevelFlow(dt: Float) {
        val pid = world.getPlayerEntity()
        val pt  = world.transforms[pid] ?: return
        if (levelFlow.level == 1 && !levelFlow.exitOpen && (world.players[pid]?.bossKills ?: 0) >= 1) openExit()
        if (levelFlow.update(dt, pt.x, pt.y)) enterLevel(pid)
        if (levelFlow.level == STATION_LEVEL && levelFlow.phase == LevelFlow.Phase.PLAYING) watchPoster(pid, pt.x, pt.y, dt)
        if (levelNoticeTimer > 0f) {
            levelNoticeTimer -= dt
            if (levelNoticeTimer <= 0f) levelNotice = null
        }
    }

    private fun openExit() {
        if (!levelFlow.openExit()) return
        val map = movementSystem.levelMap
        if (map != null) {
            map.setRect(Level1.EXIT_X0, Level1.BARRIER_Y0, Level1.EXIT_X1, Level1.BARRIER_Y1, false)
            movementSystem.levelMap = map          // the enemies' walking distances are worked out again with the gap
        }
        levelNotice      = LevelNotice.EXIT_OPEN
        levelNoticeTimer = LEVEL_NOTICE_SECONDS
        sounds.play(Sound.WAVE_CLEAR)
        CrashLogger.log("EXIT opened at ${elapsedTime.toInt()}s")
    }

    /** The player in front of his own wanted poster: after a few seconds the officers recognise him. */
    private fun watchPoster(pid: Int, x: Float, y: Float, dt: Float) {
        if (copsAlarmed) return
        val dx = x - Level3.POSTER_X; val dy = y - Level3.POSTER_Y
        if (dx * dx + dy * dy < POSTER_RADIUS * POSTER_RADIUS) posterTimer += dt
        else posterTimer = (posterTimer - dt).coerceAtLeast(0f)
        if (posterTimer >= POSTER_LOOK_SECONDS) {
            copsAlarmed = true
            movementSystem.raiseStationAlarm(world, x, y)
        }
        // an officer who sees him first raises the alarm himself
        if (!copsAlarmed && world.enemies.values.none { it.type == EnemyType.COP && it.patrol != PatrolState.NONE }) copsAlarmed = true
    }

    /** Called on the one frame the screen is black: everything of the old level goes, the player and what he carries stay. */
    private fun enterLevel(pid: Int) {
        val to   = levelFlow.level
        val from = levelFlow.previous
        val gone = ArrayList<Int>(world.enemies.size + world.projectiles.size + world.pickups.size + 64)
        gone.addAll(world.enemies.keys); gone.addAll(world.projectiles.keys); gone.addAll(world.pickups.keys)
        gone.addAll(world.particles.keys); gone.addAll(world.blasts.keys); gone.addAll(world.damageNumbers.keys)
        for (id in gone) if (id != pid) world.destroyEntity(id)
        world.flushDestroyed()
        movementSystem.copRoutes.clear()

        val info = Levels.of(to)
        val map  = if (to == STATION_LEVEL) stationMap else nextLevelMap
        movementSystem.levelMap = map
        spawnSystem.levelMap    = map
        // coming back out of the station, the player stands in front of its door
        val outFromStation = to == 2 && from == STATION_LEVEL
        val x = if (outFromStation) (Level2.STATION_DOOR_LEFT + Level2.STATION_DOOR_RIGHT) / 2f else info.spawnX
        val y = if (outFromStation) Level2.STATION_DOOR_Y + STATION_RETURN_BELOW else info.spawnY
        world.transforms[pid]?.let { it.x = x; it.y = y }
        world.velocities[pid]?.let { it.vx = 0f; it.vy = 0f }
        directorEvents.clear()
        eventBanner = null
        eventBannerTimer = 0f
        if (to == STATION_LEVEL) {
            posterTimer = 0f
            copsAlarmed = false
            spawnStationStaff()
            levelNotice = LevelNotice.STATION
        } else {
            if (!outFromStation) spawnSystem.reset()
            eventDirector.restartSchedule(elapsedTime)
            levelNotice = if (outFromStation) null else LevelNotice.LEVEL_TWO
        }
        levelNoticeTimer = if (levelNotice != null) LEVEL_NOTICE_SECONDS else 0f
        CrashLogger.log("LEVEL $to entered from $from at ${elapsedTime.toInt()}s, map=${if (map != null) "loaded" else "missing"}")
    }

    /** Breaks the shield of any riot officer whose shield has been used up, with a sound and a flash. */
    private fun settleRiots() {
        for ((eid, enemy) in world.enemies) {
            if (enemy.type != EnemyType.RIOT || !enemy.shieldUp) continue
            val health = world.healths[eid] ?: continue
            if (RiotRules.settle(enemy, health)) {
                sounds.play(Sound.SHIELD_BREAK)
                world.renders[eid]?.flashTimer = 0.3f
            }
        }
    }

    /** Level 2 is peaceful on arrival: enemies only come once the station's siege is over (the key is taken). */
    private fun levelIsHostile(): Boolean =
        laboratory || levelFlow.level != 2 || siege.phase == StationPhase.OPEN

    /** Whoever is in the station when the player comes in: the chief asleep on his sofa and the officers on their beats. */
    private fun spawnStationStaff() {
        if (siege.phase == StationPhase.OPEN) return                  // already cleared: the station stays empty
        val mult = spawnSystem.difficultyAt(elapsedTime)
        chiefId = EntityFactory.createChief(world, Level3.CHIEF_X, Level3.CHIEF_Y, mult)
        chiefX = Level3.CHIEF_X; chiefY = Level3.CHIEF_Y
        spawnCops()
    }

    /** Shuts or opens the street door: the cells behind the shutter are blocked and the path-finding map is rebuilt. */
    private fun lockStationDoor(shut: Boolean) {
        val map = stationMap ?: return
        map.setRect(Level3.SHUTTER_X0, Level3.SHUTTER_Y0, Level3.SHUTTER_X1, Level3.SHUTTER_Y1, shut)
        levelFlow.stationLocked = shut
        if (levelFlow.level == STATION_LEVEL) movementSystem.levelMap = map
    }

    /** The officers raised the alarm: lights, siren, the shutter comes down and reinforcements start coming. */
    private fun startSiege() {
        if (!siege.alarm()) return
        lockStationDoor(true)
        sirenTimer       = 0f
        levelNotice      = LevelNotice.ALARM
        levelNoticeTimer = LEVEL_NOTICE_SECONDS
        sounds.play(Sound.SHUTTER)
        CrashLogger.log("SIEGE begins at ${elapsedTime.toInt()}s")
    }

    private fun wakeChiefNow() {
        movementSystem.wakeChief(world, chiefId)
        levelNotice      = LevelNotice.CHIEF_AWAKE
        levelNoticeTimer = LEVEL_NOTICE_SECONDS
        sounds.play(Sound.CHIEF_WAKE)
        CrashLogger.log("CHIEF wakes at ${elapsedTime.toInt()}s")
    }

    /** The chief wakes before the quota: somebody hit him, or the laboratory asked for it. */
    private fun wakeChiefEarly() {
        if (!world.enemies.containsKey(chiefId)) return
        startSiege()
        if (siege.chiefWoke()) wakeChiefNow()
    }

    /** What happens in the station from one frame to the next while the siege goes on. */
    private fun updateStation(dt: Float) {
        val chief = world.enemies[chiefId]
        if (chief != null) {
            world.transforms[chiefId]?.let { chiefX = it.x; chiefY = it.y }
            val hp = world.healths[chiefId]
            if (chief.asleep && hp != null && hp.current < hp.max) wakeChiefEarly()
        }
        if (siege.alarmOn) {
            sirenTimer -= dt
            if (sirenTimer <= 0f) { sounds.play(Sound.SIREN); sirenTimer = SIREN_EVERY_SECONDS }
        }
        var alive = 0
        for (enemy in world.enemies.values) if (enemy.type == EnemyType.COP) alive++
        val wanted = siege.reinforcements(dt, alive)
        if (wanted > 0) bringReinforcements(wanted)
    }

    /** New officers come through the doors of the rooms farthest from the player. */
    private fun bringReinforcements(count: Int) {
        val pt   = world.transforms[world.getPlayerEntity()] ?: return
        val mult = spawnSystem.difficultyAt(elapsedTime)
        val spots = Level3.REINFORCEMENTS
        val far = ArrayList<Int>()
        for (i in 0 until spots.size / 2) {
            val dx = spots[i * 2] - pt.x; val dy = spots[i * 2 + 1] - pt.y
            if (dx * dx + dy * dy > REINFORCEMENT_MIN_DISTANCE * REINFORCEMENT_MIN_DISTANCE) far.add(i)
        }
        if (far.isEmpty()) return
        repeat(count) {
            val i  = far[Random.nextInt(far.size)]
            val id = EntityFactory.createEnemy(world, EnemyType.COP, spots[i * 2] + Random.nextFloat() * 40f - 20f, spots[i * 2 + 1], mult)
            world.enemies[id]?.aiState = AIState.CHASE
        }
    }

    /** The events that matter to the siege: officers falling, the chief falling and the key being picked up. */
    private fun siegeEvent(event: GameEvent) {
        when (event) {
            is GameEvent.EnemyKilled -> when (event.type) {
                EnemyType.COP -> if (siege.copKilled()) wakeChiefNow()
                EnemyType.CHIEF -> if (siege.chiefKilled()) {
                    EntityFactory.createPickup(world, PickupType.KEY, chiefX, chiefY)
                    levelNotice      = LevelNotice.KEY_DROPPED
                    levelNoticeTimer = LEVEL_NOTICE_SECONDS
                    CrashLogger.log("CHIEF down at ${elapsedTime.toInt()}s, the key is on the floor")
                }
                else -> Unit
            }
            is GameEvent.PickupCollected -> if (event.type == PickupType.KEY && siege.keyTaken()) {
                lockStationDoor(false)
                levelNotice      = LevelNotice.STATION_OPEN
                levelNoticeTimer = LEVEL_NOTICE_SECONDS
                sounds.play(Sound.KEY_GET)
                eventDirector.restartSchedule(elapsedTime)   // the plaza stops being peaceful once the station is cleared
                CrashLogger.log("KEY taken at ${elapsedTime.toInt()}s: the street door is open")
            }
            else -> Unit
        }
    }

    /** The officers of the station, each on his beat. */
    private fun spawnCops() {
        val mult = spawnSystem.difficultyAt(elapsedTime)
        for (route in Level3.COP_ROUTES) {
            val id = EntityFactory.createEnemy(world, EnemyType.COP, route[0], route[1], mult)
            val enemy = world.enemies[id] ?: continue
            enemy.patrol = PatrolState.WALK
            enemy.routeIndex = if (route.size >= 4) 1 else 0
            enemy.patrolDir = if (route.size >= 4) atan2(route[3] - route[1], route[2] - route[0]) else (PI / 2).toFloat()
            enemy.patrolTimer = 0.5f + Random.nextFloat() * 1.5f
            movementSystem.copRoutes[id] = route
        }
    }

    /** Applies what the tester asked for since the last tick. Laboratory only. */
    private fun drainLabCommands() {
        val pid = world.getPlayerEntity()
        if (pid == -1) { labQueue.clear(); return }
        val pt = world.transforms[pid] ?: return
        val map = movementSystem.levelMap
        while (true) {
            val command = labQueue.poll() ?: break
            CrashLogger.log("LAB: $command")
            when (command) {
                is LabCommand.SpawnEnemy    -> LabActions.spawnEnemies(world, map, command.type, command.count)
                is LabCommand.SpawnMiniBoss -> LabActions.spawnEnemies(world, map, command.type, 1, miniBoss = true)
                is LabCommand.DropPickup    -> LabActions.dropPickup(world, map, command.type)
                is LabCommand.StartEvent    -> eventDirector.force(world, command.kind, pt.x, pt.y, elapsedTime, directorEvents)
                is LabCommand.GiveWeapon    -> labGiveWeapon(pid, command.type)
                is LabCommand.LevelUp       -> LabActions.levelUp(world, map)
                is LabCommand.AddCombo      -> combo.onKills(command.kills)
                is LabCommand.OpenExit      -> openExit()
                is LabCommand.GoToLevel2    -> levelFlow.startTrip()
                is LabCommand.GoToStation   -> levelFlow.startTripToStation()
                is LabCommand.WakeChief     -> if (levelFlow.level == STATION_LEVEL) wakeChiefEarly()
                is LabCommand.ClearEnemies  -> LabActions.clearEnemies(world)
                is LabCommand.HealFull      -> LabActions.healFull(world)
                is LabCommand.SetInvincible -> labInvincible = command.on
            }
        }
    }

    private fun labGiveWeapon(pid: Int, type: com.kotlinsurvivors.engine.ecs.components.WeaponType) {
        val owned = world.weapons[pid]?.find { it.type == type }
        if (owned == null) addWeapon(world, pid, type)
        else if (owned.level < MAX_WEAPON_LEVEL) levelUpWeapon(world, pid, owned)
    }

    /** Shows the banner and plays the sound for every event the director started this frame. */
    private fun announceEvents() {
        if (directorEvents.isEmpty()) return
        for (event in directorEvents) {
            when (event) {
                is GameEvent.EventStarted -> {
                    eventBanner = event.kind; eventBannerTimer = EVENT_BANNER_SECONDS
                    sounds.play(if (event.kind == EventKind.MINI_BOSS) Sound.EVENT_MINIBOSS else Sound.EVENT_WAVE)
                    CrashLogger.log("EVENT started: ${event.kind} at ${elapsedTime.toInt()}s, ${world.enemies.size} enemies")
                }
                is GameEvent.WaveCleared -> {
                    eventBanner = EventKind.WAVE_CLEARED; eventBannerTimer = EVENT_BANNER_SECONDS
                    sounds.play(Sound.WAVE_CLEAR)
                    CrashLogger.log("EVENT wave cleared at ${elapsedTime.toInt()}s")
                }
                else -> Unit
            }
        }
        directorEvents.clear()
    }

    /** Sounds for what happened in the collision step: kills, pickups and hits on the player. */
    private fun playEventSounds(events: List<GameEvent>) {
        var kills = 0
        for (event in events) {
            when (event) {
                is GameEvent.EnemyKilled -> sounds.play(when {
                    event.type.isBoss || event.miniBoss || event.type == EnemyType.CHIEF -> Sound.BOSS_DIE
                    event.type == EnemyType.EXPLODER -> Sound.ENEMY_BLAST
                    else                             -> Sound.ENEMY_DIE
                })
                is GameEvent.PickupCollected -> when (event.type) {
                    PickupType.EXPERIENCE_SMALL, PickupType.EXPERIENCE_MEDIUM,
                    PickupType.EXPERIENCE_LARGE -> sounds.play(Sound.PICKUP_XP)
                    PickupType.COIN, PickupType.COIN_BAG -> sounds.play(Sound.PICKUP_COIN)
                    PickupType.HEALTH_SMALL, PickupType.HEALTH_LARGE -> sounds.play(Sound.PICKUP_HEAL)
                    PickupType.MAGNET -> sounds.play(Sound.PICKUP_MAGNET)
                    PickupType.BOMB   -> sounds.play(Sound.ENEMY_BLAST)
                    PickupType.CHEST  -> Unit   // sounds when it finishes opening
                    PickupType.KEY    -> Unit   // the siege makes its own sound
                }
                is GameEvent.PlayerHit -> sounds.play(Sound.PLAYER_HURT)
                else -> Unit
            }
            if (event is GameEvent.EnemyKilled) kills++
        }
        if (combo.onKills(kills)) sounds.play(COMBO_SOUNDS[combo.tier])
    }

    private fun emitState() {
        // While paused, in a level-up or after game over nothing changes, so
        // there is nothing new to publish. Skipping keeps the buffer the UI is
        // drawing from being recycled by frames that would look identical.
        val flagsChanged = !hasEmitted ||
            isPaused != lastEmittedPaused ||
            pendingLevelUp != lastEmittedPendingLevelUp ||
            (pendingChest != null) != lastEmittedChest ||
            levelUpsWaiting != lastEmittedLevelUpsWaiting
        if (!tickedSinceEmit && !flagsChanged) return
        tickedSinceEmit           = false
        hasEmitted                = true
        lastEmittedPaused         = isPaused
        lastEmittedPendingLevelUp = pendingLevelUp
        lastEmittedChest          = pendingChest != null
        lastEmittedLevelUpsWaiting = levelUpsWaiting

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
            levelUpsWaiting  = levelUpsWaiting,
            levelUpOptions   = cachedLevelUpOptions,  // reuse cached, never regenerate per frame
            levelUpWinner    = cachedLevelUpWinner,
            chestReward      = pendingChest,
            eventBanner      = eventBanner,
            levelNumber      = levelFlow.level,
            exitOpen         = levelFlow.exitOpen,
            levelFade        = levelFlow.fade,
            levelNotice      = levelNotice,
            stationPhase     = siege.phase,
            copsDefeated     = siege.kills.coerceAtMost(StationSiege.GOAL),
            comboCount       = if (combo.isVisible) combo.count else 0,
            comboTier        = combo.tier,
            comboFraction    = if (combo.isVisible) (combo.fraction * 20f).toInt() / 20f else 0f,
            elapsedTime      = elapsedTime,
            playerHp         = health?.current ?: 0,
            playerMaxHp      = health?.max ?: 100,
            playerLevel      = player?.level ?: 1,
            playerXp         = player?.experience ?: 0,
            playerXpToNext   = player?.experienceToNextLevel ?: 25,
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
            val isMini = world.enemies[eid]?.isMiniBoss == true
            if (h.percentage < 1f || isMini) f = f or RenderBuffer.FLAG_SHOW_HP_BAR
            if (isBoss)            f = f or RenderBuffer.FLAG_IS_BOSS
            if (isMini)            f = f or RenderBuffer.FLAG_IS_MINI_BOSS

            // The guard (BASIC) is drawn from his heading and his punch clock, so the picture needs both.
            val enemy = world.enemies[eid]
            var heading = t.rotation
            var sincePunch = 0f
            var cooldown = 1f
            if (enemy != null && (enemy.type == EnemyType.BASIC || enemy.type == EnemyType.DOG || enemy.type == EnemyType.COP || enemy.type == EnemyType.CHIEF || enemy.type == EnemyType.RIOT)) {
                val v = world.velocities[eid]
                val vx = v?.vx ?: 0f
                val vy = v?.vy ?: 0f
                val moving = vx * vx + vy * vy > FACING_MIN_SPEED_SQ
                if (moving) f = f or RenderBuffer.FLAG_MOVING
                cooldown = enemy.attackCooldown
                sincePunch = if (enemy.attackTimer > 0f) cooldown - enemy.attackTimer else RenderBuffer.GUARD_NO_PUNCH
                val toPlayerX = playerX - t.x
                val toPlayerY = playerY - t.y
                val nearPlayer = toPlayerX * toPlayerX + toPlayerY * toPlayerY < RenderBuffer.GUARD_WINDUP_RANGE * RenderBuffer.GUARD_WINDUP_RANGE
                // He looks where he walks; when he is about to hit or has just hit, he looks at the player.
                heading = if (moving && !nearPlayer && sincePunch >= RenderBuffer.GUARD_PUNCH_SECONDS) atan2(vy, vx) else atan2(toPlayerY, toPlayerX)
                // an officer on his beat looks where he is going, and goes on looking there when he stops
                if (enemy.type == EnemyType.COP && enemy.patrol == PatrolState.WALK) heading = enemy.patrolDir
                if (enemy.shouting && enemy.patrol == PatrolState.ALERT) f = f or RenderBuffer.FLAG_SHOUTING
                if (enemy.asleep) f = f or RenderBuffer.FLAG_SLEEPING
                if (enemy.shieldUp) f = f or RenderBuffer.FLAG_SHIELDED
                if ((enemy.type == EnemyType.BASIC || enemy.type == EnemyType.COP || enemy.type == EnemyType.CHIEF || (enemy.type == EnemyType.RIOT && !enemy.shieldUp)) && nearPlayer && enemy.attackTimer <= 0f) f = f or RenderBuffer.FLAG_WINDING_UP
                if (enemy.patrol == PatrolState.ALERT) f = f or RenderBuffer.FLAG_ALERTED
                if (enemy.patrol == PatrolState.WALK) f = f or RenderBuffer.FLAG_STROLLING
                if (enemy.type == EnemyType.DOG && vx * vx + vy * vy > DOG_RUN_SPEED_SQ) f = f or RenderBuffer.FLAG_RUNNING
            }
            renderBuffer.write(
                kindOrdinal  = if (isBoss) RenderKind.BOSS else RenderKind.ENEMY,
                ex = t.x, ey = t.y, eRotation = heading,
                eShape = r.shape, eColor = r.color, eSecColor = r.secondaryColor,
                eWidth = r.width, eHeight = r.height,
                eGlowRadius = r.glowRadius, eGlowColor = r.glowColor,
                eFlashTimer = r.flashTimer, eHpPercent = h.percentage, eFlags = f,
                eLifetime = sincePunch, eMaxLifetime = cooldown,
                eSpriteId = enemy?.type?.ordinal ?: -1
            )

            // The leash of a patrol that has not noticed anything yet, from the guard to his dog.
            if (enemy != null && enemy.type == EnemyType.BASIC && enemy.patrol == PatrolState.WALK && enemy.partner >= 0) {
                val dogT = world.transforms[enemy.partner]
                if (dogT != null) renderBuffer.write(
                    kindOrdinal = RenderKind.LEASH,
                    ex = t.x, ey = t.y, eWidth = dogT.x, eHeight = dogT.y
                )
            }
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
                // For a chest: seconds it has been opening (-1 while closed) and how long it takes.
                eLifetime = world.pickups[pkId]?.openTimer ?: -1f, eMaxLifetime = ExperienceSystem.CHEST_OPEN_SECONDS,
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
            val effectSprite = when (p.type) {
                ParticleType.LASH -> RenderBuffer.SPRITE_WHIP_LASH
                ParticleType.RING -> RenderBuffer.SPRITE_DDOS_RING
                ParticleType.BEAM -> RenderBuffer.SPRITE_ZERO_DAY_BEAM
                ParticleType.BOMB -> RenderBuffer.SPRITE_LOGIC_BOMB
                else              -> -1
            }
            if (effectSprite != -1) {
                // The whip's cable, the DDoS ring and the Zero-Day beam stay attached to the
                // player while they play; a lit bomb stays where it was dropped.
                val attached = p.type != ParticleType.BOMB
                val owner = if (attached) world.transforms[world.getPlayerEntity()] ?: t else t
                renderBuffer.write(
                    kindOrdinal = RenderKind.PARTICLE,
                    ex = owner.x, ey = owner.y, eRotation = Math.atan2(p.vy.toDouble(), p.vx.toDouble()).toFloat(),
                    eWidth = p.size, eHeight = p.size,
                    eLifetime = p.lifetime, eMaxLifetime = p.maxLifetime,
                    eSpriteId = effectSprite
                )
                continue
            }
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

    /** Everything the level-up roulette may land on right now. */
    private fun levelUpPool(world: World, pid: Int): List<LevelUpOption> {
        world.players[pid] ?: return emptyList()
        val weapons = world.weapons[pid]  ?: return emptyList()
        val ownedTypes = weapons.map { it.type }.toSet()
        val options = mutableListOf<LevelUpOption>()

        for (weapon in weapons) {
            if (weapon.level < MAX_WEAPON_LEVEL) {
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
                    rarity = rarity,
                    level = weapon.level + 1
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

        // Legendary: the rarest prizes of the wheel.
        options.add(LevelUpOption("legend_titan", "Titan Heart", "+100 Max HP and a full heal",
            UpgradeType.STAT, icon = "heart", rarity = UpgradeRarity.LEGENDARY))
        options.add(LevelUpOption("legend_fury", "Warlord's Fury", "+50% Damage",
            UpgradeType.STAT, icon = "sword", rarity = UpgradeRarity.LEGENDARY))
        options.add(LevelUpOption("legend_time", "Time Warp", "+35% Attack Speed",
            UpgradeType.STAT, icon = "bolt", rarity = UpgradeRarity.LEGENDARY))
        if (weapons.any { it.level < MAX_WEAPON_LEVEL }) {
            options.add(LevelUpOption("legend_arsenal", "Arsenal Surge", "Every weapon gains a level",
                UpgradeType.STAT, icon = "crown", rarity = UpgradeRarity.LEGENDARY))
        }
        return options
    }

    /** Gives the player a weapon he does not have yet, with the extra parts some of them need. */
    private fun addWeapon(world: World, pid: Int, wt: com.kotlinsurvivors.engine.ecs.components.WeaponType) {
        val weapons = world.weapons[pid] ?: return
        if (weapons.any { it.type == wt }) return
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

    private fun applyUpgrade(world: World, pid: Int, option: LevelUpOption) {
        val player  = world.players[pid]  ?: return
        val health  = world.healths[pid]  ?: return
        val weapons = world.weapons[pid]  ?: return

        Log.d(TAG, "Applying upgrade: ${option.id} (${option.type})")

        when (option.type) {
            UpgradeType.WEAPON_UPGRADE -> {
                val weapon = weapons.find { it.type == option.weaponType } ?: return
                levelUpWeapon(world, pid, weapon)
            }
            UpgradeType.NEW_WEAPON -> {
                val wt = option.weaponType ?: return
                addWeapon(world, pid, wt)
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
                    "legend_titan"  -> { health.max += 100; health.current = health.max }
                    "legend_fury"   -> player.damageMultiplier *= 1.50f
                    "legend_time"   -> player.attackSpeedMultiplier *= 1.35f
                    "legend_arsenal" -> weapons.filter { it.level < MAX_WEAPON_LEVEL }
                        .forEach { levelUpWeapon(world, pid, it) }
                }
            }
        }
    }

    /**
     * The player touched a boss chest: decides what is inside and freezes the game while the screen
     * shows it. Nothing is handed over until [confirmChest], so what the player sees is what he gets.
     */
    private fun openChest() {
        val pid = world.getPlayerEntity()
        val owned = world.weapons[pid] ?: return
        pendingChest = ChestRules.roll(owned, MAX_WEAPON_LEVEL)
        chestSeen = false
        CrashLogger.log("CHEST opened: $pendingChest")
    }

    /** The player has seen the reward and tapped to go on. Safe to call from the UI thread. */
    fun confirmChest() {
        chestSeen = true
    }

    /** Hands over the reward once the player confirmed it and lets the game run again. */
    private fun finishChestIfSeen() {
        val reward = pendingChest ?: return
        if (!chestSeen) return
        val pid = world.getPlayerEntity()
        val weapons = world.weapons[pid]
        if (pid != -1 && weapons != null) {
            when (reward.kind) {
                ChestReward.Kind.NEW_WEAPON ->
                    reward.weapon?.let { if (weapons.none { w -> w.type == it }) weapons.add(newWeaponComponent(it)) }
                ChestReward.Kind.LEVEL_UP ->
                    weapons.find { it.type == reward.weapon }?.let { levelUpWeapon(world, pid, it) }
                ChestReward.Kind.HEAL ->
                    world.healths[pid]?.let { it.current = it.max }
            }
            CrashLogger.log("CHEST reward given: $reward -> ${loadoutSummary(pid)}")
        }
        pendingChest = null
        chestSeen = false
    }

    private fun levelUpWeapon(world: World, pid: Int, weapon: com.kotlinsurvivors.engine.ecs.components.WeaponComponent) {
        weapon.level++
        weapon.damage = (weapon.damage * 1.2f).roundToInt()
        weapon.cooldown *= 0.92f
        if (weapon.level % 3 == 0) weapon.projectileCount++
        if (weapon.level % 4 == 0) weapon.piercing++
        weapon.area *= 1.1f
        WeaponUpgrades.applyToAttachedComponents(world, pid, weapon)
        Log.d(TAG, "Weapon ${weapon.type} upgraded to Lv${weapon.level} dmg=${weapon.damage}")
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
                com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS        -> 30
                com.kotlinsurvivors.engine.ecs.components.WeaponType.ZERO_DAY    -> 28
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LOGIC_BOMB  -> 60
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
                com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND  -> 1.0f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS        -> 3.0f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.ZERO_DAY    -> 2.4f
                com.kotlinsurvivors.engine.ecs.components.WeaponType.LOGIC_BOMB  -> 3.5f
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
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "Ping Packet"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "SQL Injection"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "Crosshair"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "Firewall"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "USB Drop"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "Ethernet Surge"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "Antivirus"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "Honeypot"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "Ethernet Whip"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "Rogue Laptop"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS        -> "DDoS"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.ZERO_DAY    -> "Zero-Day"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LOGIC_BOMB  -> "Logic Bomb"
    }
    private fun weaponDescription(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) = when (type) {
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "Sends a data packet at the nearest enemy."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "Fires a fast syringe at the nearest enemy."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "Locks on and fires in four directions."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "Launches a burning wall that pierces enemies."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "Throws an infected USB stick in an arc."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "Zaps multiple nearby enemies through the cable."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "Scans and damages enemies around you."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "Drops a trap that burns everything inside."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "Cracks a network cable at enemies in front of you, in any of four directions."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "Hacked laptops orbit you and destroy nearby enemies."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS        -> "A shock wave hurts everything around you."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.ZERO_DAY    -> "A beam that goes through every enemy in line."
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LOGIC_BOMB  -> "Drops a bomb on the nearest enemy that explodes after a moment."
    }
    private fun weaponUpgradeTitle(type: com.kotlinsurvivors.engine.ecs.components.WeaponType, lvl: Int) = "${weaponName(type)} Lv.$lvl"
    private fun weaponUpgradeDesc(lvl: Int) = when {
        lvl % 3 == 0 -> "+1 Projectile, +Damage, +Area"   // matches levelUpWeapon()
        else         -> "+Damage, +Area, -Cooldown"
    }
    private fun weaponIcon(type: com.kotlinsurvivors.engine.ecs.components.WeaponType) = when (type) {
        com.kotlinsurvivors.engine.ecs.components.WeaponType.MAGIC_WAND   -> "w_packet"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.KNIFE        -> "w_syringe"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.CROSS        -> "w_crosshair"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.FIRE_WAND   -> "w_firewall"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.AXE         -> "w_usb"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LIGHTNING   -> "w_plug"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.GARLIC      -> "w_antivirus"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.SANTA_WATER -> "w_honeypot"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.WHIP        -> "w_cable"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.BIBLE       -> "w_laptop"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.DDOS        -> "w_ddos"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.ZERO_DAY    -> "w_zeroday"
        com.kotlinsurvivors.engine.ecs.components.WeaponType.LOGIC_BOMB  -> "w_logicbomb"
    }
}

enum class UpgradeRarity { COMMON, RARE, EPIC, LEGENDARY }
