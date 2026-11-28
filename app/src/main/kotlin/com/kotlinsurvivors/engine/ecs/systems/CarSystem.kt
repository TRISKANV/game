package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.audio.SoundEffects
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.CarComponent
import com.kotlinsurvivors.engine.ecs.components.CarState
import com.kotlinsurvivors.engine.ecs.components.ParticleType
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.LevelCars
import com.kotlinsurvivors.engine.map.LevelMap
import com.kotlinsurvivors.features.game.domain.model.CarPrompt
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cars of a level: picking their locks, driving them, what the enemies do to them and what is
 * left of them. Only one car is driven at a time, by the player, whose position follows the car.
 *
 * The collision grid of the level keeps a car's parking place blocked while it stands there; the
 * place is freed when the car sets off and blocked again where it stops. The engine rebuilds the
 * enemies' walking distances when [consumeMapChange] says the grid changed.
 */
class CarSystem(private val sounds: SoundEffects = SoundEffects.None) {

    var levelMap: LevelMap? = null

    /** What the car button offers the player this frame, and how far the lock is picked (0..1). */
    var prompt: CarPrompt = CarPrompt.NONE; private set
    var progress: Float = 0f;               private set
    /** The look (cell of cars.png) of the car the button is about: the one in reach or the one being driven; -1 if none. */
    var promptLook: Int = -1;               private set

    private var mapChanged = false
    private var pickingId = -1
    /** True once a finger went down on the button while a locked car was in reach; see [update]. */
    private var pickArmed = false
    private var tickTimer = 0f
    private var driverRadius = 0f
    private var engineLoop = -1

    fun consumeMapChange(): Boolean { val c = mapChanged; mapChanged = false; return c }

    /** A car left behind when its level was left, with where it stood. The level's grid keeps its place blocked. */
    private class Parked(val x: Float, val y: Float, val car: CarComponent)
    private val stash = HashMap<Int, List<Parked>>()

    /** A new run: no car remembers anything. */
    fun reset() {
        stash.clear(); mapChanged = false; pickingId = -1; pickArmed = false; prompt = CarPrompt.NONE; progress = 0f; promptLook = -1; engineLoop = -1
    }

    /** The cars of [level] are put away as they stand, to be found there again if the player comes back. */
    fun leave(world: World, level: Int) {
        releaseDriver(world)
        val parked = ArrayList<Parked>(world.cars.size)
        for ((id, car) in world.cars) {
            val t = world.transforms[id] ?: continue
            parked.add(Parked(t.x, t.y, car))
        }
        stash[level] = parked
        for (id in ArrayList(world.cars.keys)) world.destroyEntity(id)
    }

    /** Puts the cars of [level] on the ground: where they were left, or in their parking places the first time. */
    fun spawn(world: World, level: Int) {
        val kept = stash.remove(level)
        if (kept != null) {
            for (p in kept) {
                val id = world.createEntity()
                world.transforms[id] = com.kotlinsurvivors.engine.ecs.components.TransformComponent(x = p.x, y = p.y)
                world.cars[id] = p.car
            }
            return
        }
        val data = LevelCars.of(level)
        var i = 0
        while (i + LevelCars.STRIDE <= data.size) {
            val look = data[i + 3].toInt()
            val hp = CarRules.hpFor(LevelCars.LOOK_SIZE[look * 2])
            EntityFactory.createCar(world, data[i], data[i + 1], data[i + 2], look, hp,
                floatArrayOf(data[i + 4], data[i + 5], data[i + 6], data[i + 7]))
            i += LevelCars.STRIDE
        }
    }

    /** Called before the cars are thrown away with their level: the player is on foot again. */
    fun releaseDriver(world: World) {
        val pid = world.getPlayerEntity()
        val player = world.players[pid] ?: return
        if (player.drivingCar >= 0) restoreDriver(world, pid)
        stopEngine()
        prompt = CarPrompt.NONE; progress = 0f; pickingId = -1
    }

    /**
     * [pressed] is true on the frame the button went down, [held] while it stays down.
     */
    fun update(world: World, dt: Float, joystick: JoystickState, pressed: Boolean, held: Boolean) {
        val pid = world.getPlayerEntity()
        if (pid == -1) return
        val player = world.players[pid] ?: return
        val pt     = world.transforms[pid] ?: return
        val health = world.healths[pid] ?: return

        for (car in world.cars.values) if (car.hurtTimer > 0f) car.hurtTimer = (car.hurtTimer - dt).coerceAtLeast(0f)

        promptLook = -1
        val drivenId = player.drivingCar
        if (drivenId >= 0) {
            val car = world.cars[drivenId]
            val ct  = world.transforms[drivenId]
            if (car == null || ct == null) { restoreDriver(world, pid); return }
            drive(world, pid, drivenId, car, ct, joystick, dt)
            promptLook = car.look
            if (car.state == CarState.DRIVEN) {
                prompt = CarPrompt.EXIT; progress = 0f
                if (pressed) getOut(world, pid, drivenId, car, ct)
            }
            return
        }

        stopEngine()
        // On foot: the nearest car in reach
        var best = -1; var bestD2 = CarRules.REACH * CarRules.REACH
        for ((id, car) in world.cars) {
            if (car.state != CarState.LOCKED && car.state != CarState.OPEN) continue
            val ct = world.transforms[id] ?: continue
            val dx = ct.x - pt.x; val dy = ct.y - pt.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; best = id }
        }
        if (best == -1) { prompt = CarPrompt.NONE; progress = 0f; pickingId = -1; pickArmed = false; return }

        val car = world.cars[best] ?: return
        promptLook = car.look
        if (best != pickingId) { pickingId = best; progress = 0f; pickArmed = false }
        if (car.state == CarState.OPEN) {
            prompt = CarPrompt.ENTER; progress = 0f
            if (pressed) getIn(world, pid, best, car)
            return
        }
        prompt = CarPrompt.LOCKPICK
        // a blow interrupts the work: the lock goes back to the start
        if (health.invincibleTimer > 0f) { progress = 0f; return }
        // The lock is only picked by a finger that went down on the button next to this car: a button that
        // was already down when the player walked up (or got stuck down) does nothing.
        if (pressed) pickArmed = true
        if (!held) pickArmed = false
        val picking = held && pickArmed
        progress = CarRules.pickProgress(progress, dt, picking)
        if (picking && progress < 1f) {
            tickTimer -= dt
            if (tickTimer <= 0f) { sounds.play(Sound.CAR_PICK); tickTimer = TICK_SECONDS }
        }
        if (progress >= 1f) {
            car.state = CarState.OPEN
            progress = 0f
            getIn(world, pid, best, car)
        }
    }

    // ── Getting in and out ────────────────────────────────────────────────

    private fun getIn(world: World, pid: Int, id: Int, car: CarComponent) {
        val player = world.players[pid] ?: return
        player.drivingCar = id
        car.state = CarState.DRIVEN
        car.speed = 0f
        setParkingPlace(car, false)
        world.colliders[pid]?.let { driverRadius = it.radius; it.radius = CarRules.BODY_RADIUS }
        world.velocities[pid]?.let { it.vx = 0f; it.vy = 0f }
        sounds.play(Sound.CAR_DOOR)
        engineLoop = sounds.startLoop(Sound.CAR_ENGINE)
        prompt = CarPrompt.EXIT; progress = 0f; pickingId = -1
    }

    private fun getOut(world: World, pid: Int, id: Int, car: CarComponent, ct: com.kotlinsurvivors.engine.ecs.components.TransformComponent) {
        car.speed = 0f
        car.state = CarState.OPEN
        parkHere(car, ct.x, ct.y)
        restoreDriver(world, pid)
        placeDriverBeside(world, pid, car, ct.x, ct.y)
        world.healths[pid]?.let { it.invincibleTimer = EXIT_GRACE_SECONDS }
        sounds.play(Sound.CAR_DOOR)
        stopEngine()
        prompt = CarPrompt.NONE
    }

    private fun restoreDriver(world: World, pid: Int) {
        world.players[pid]?.drivingCar = -1
        if (driverRadius > 0f) world.colliders[pid]?.radius = driverRadius
    }

    /** The player steps out on the side of the car where there is room, or beside the nose as a last resort. */
    private fun placeDriverBeside(world: World, pid: Int, car: CarComponent, cx: Float, cy: Float) {
        val pt = world.transforms[pid] ?: return
        val map = levelMap
        val sideX = -sin(car.heading); val sideY = cos(car.heading)
        val fwdX = cos(car.heading);   val fwdY = sin(car.heading)
        val gap = CarRules.BOX_HALF + OUT_DISTANCE
        val tries = arrayOf(
            floatArrayOf(sideX, sideY), floatArrayOf(-sideX, -sideY),
            floatArrayOf(-fwdX, -fwdY), floatArrayOf(fwdX, fwdY)
        )
        for (t in tries) {
            val x = cx + t[0] * gap; val y = cy + t[1] * gap
            if (map == null || map.isBoxFree(x, y + MovementSystem.PLAYER_FEET_OFFSET, MovementSystem.PLAYER_HALF, MovementSystem.PLAYER_HALF)) {
                pt.x = x; pt.y = y; return
            }
        }
        pt.x = cx + sideX * gap; pt.y = cy + sideY * gap
    }

    // ── The parking place on the collision grid ───────────────────────────

    private fun setParkingPlace(car: CarComponent, blocked: Boolean) {
        val map = levelMap ?: return
        map.setRect(car.boxX0, car.boxY0, car.boxX1, car.boxY1, blocked)
        mapChanged = true
    }

    /** The car stops here: its new place is a square around it. */
    private fun parkHere(car: CarComponent, x: Float, y: Float) {
        val h = CarRules.BOX_HALF
        car.boxX0 = x - h; car.boxY0 = y - h; car.boxX1 = x + h; car.boxY1 = y + h
        setParkingPlace(car, true)
    }

    // ── Driving ────────────────────────────────────────────────────────────

    private fun drive(
        world: World, pid: Int, id: Int, car: CarComponent,
        ct: com.kotlinsurvivors.engine.ecs.components.TransformComponent, joystick: JoystickState, dt: Float
    ) {
        if (car.state != CarState.DRIVEN) return
        CarRules.drive(car, joystick.isActive, joystick.dx, joystick.dy, dt)
        val map = levelMap
        val nx = ct.x + cos(car.heading) * car.speed * dt
        val ny = ct.y + sin(car.heading) * car.speed * dt
        val half = CarRules.BOX_HALF
        var hit = false
        if (map == null) { ct.x = nx; ct.y = ny }
        else {
            val stuck = !map.isBoxFree(ct.x, ct.y, half, half)        // it came out of a tight parking place: let it go
            if (stuck || map.canMove(ct.x, ct.y, nx, ct.y, half, half)) ct.x = nx else hit = true
            if (stuck || map.canMove(ct.x, ct.y, ct.x, ny, half, half)) ct.y = ny else hit = true
            val limit = map.cells * map.cellSize - EDGE
            ct.x = ct.x.coerceIn(EDGE, limit); ct.y = ct.y.coerceIn(EDGE, limit)
        }
        if (hit) {
            val damage = CarRules.crashDamage(car.speed)
            if (damage > 0) { hurt(world, car, damage); sounds.play(Sound.CAR_CRASH) }
            car.speed *= 0.35f
        }
        syncDriver(world, pid, ct.x, ct.y)
        if (car.hp <= 0) wreck(world, pid, id, car, ct)
    }

    /** The player is wherever the car is. Called again after the movement system, which would otherwise push him off. */
    fun syncDriver(world: World, pid: Int, x: Float, y: Float) {
        world.transforms[pid]?.let { it.x = x; it.y = y }
        world.velocities[pid]?.let { it.vx = 0f; it.vy = 0f }
    }

    fun keepDriverInCar(world: World) {
        val pid = world.getPlayerEntity()
        val id = world.players[pid]?.drivingCar ?: return
        if (id < 0) return
        val ct = world.transforms[id] ?: return
        syncDriver(world, pid, ct.x, ct.y)
    }

    fun hurt(world: World, car: CarComponent, damage: Int) {
        car.hp = (car.hp - damage).coerceAtLeast(0)
        car.hurtTimer = HURT_FLASH_SECONDS
    }

    /** The car has had enough: it stops for good, and the player is thrown out beside it, unhurt. */
    private fun wreck(world: World, pid: Int, id: Int, car: CarComponent, ct: com.kotlinsurvivors.engine.ecs.components.TransformComponent) {
        car.state = CarState.WRECK
        car.speed = 0f
        parkHere(car, ct.x, ct.y)
        restoreDriver(world, pid)
        placeDriverBeside(world, pid, car, ct.x, ct.y)
        world.healths[pid]?.let { it.invincibleTimer = WRECK_GRACE_SECONDS }
        EntityFactory.createParticleBurst(world, ct.x, ct.y, ParticleType.EXPLOSION, 10)
        sounds.play(Sound.CAR_WRECK)
        stopEngine()
        prompt = CarPrompt.NONE
    }

    private fun stopEngine() {
        if (engineLoop != -1) { sounds.stopLoop(engineLoop); engineLoop = -1 }
    }

    /** Health of the car being driven, 0..1, or -1 on foot (for the HUD). */
    fun drivenHealth(world: World): Float {
        val id = world.players[world.getPlayerEntity()]?.drivingCar ?: return -1f
        val car = if (id >= 0) world.cars[id] else null
        return if (car == null) -1f else car.hp.toFloat() / car.maxHp
    }

    private companion object {
        const val TICK_SECONDS = 0.28f
        const val OUT_DISTANCE = 34f
        const val EXIT_GRACE_SECONDS = 0.6f
        const val WRECK_GRACE_SECONDS = 1.8f
        const val HURT_FLASH_SECONDS = 0.15f
        const val EDGE = 60f
    }
}
