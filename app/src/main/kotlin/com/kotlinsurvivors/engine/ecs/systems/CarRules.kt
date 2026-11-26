package com.kotlinsurvivors.engine.ecs.systems

import com.kotlinsurvivors.engine.ecs.components.CarComponent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/** The numbers and the pure rules of the cars: the engine's [CarSystem] only moves things according to them. */
object CarRules {
    /** Seconds the lock takes to pick while the button is held. */
    const val LOCK_SECONDS = 2.2f
    /** Picking runs back this many times faster than it runs forward when the button is let go. */
    const val LOCK_FALLBACK = 2f
    /** How close to a car the player must be to use it, from the centre of the car. */
    const val REACH = 105f

    const val MAX_SPEED = 330f
    private const val ACCELERATION = 1.7f
    private const val BRAKING      = 2.6f
    /** Radians per second the heading can turn. */
    const val TURN_RATE = 3.4f

    /** Half size of the square that collides with the level, and radius that enemies touch. */
    const val BOX_HALF = 30f
    const val BODY_RADIUS = 34f

    /** Below this speed the car does not run enemies over. */
    const val RUN_OVER_SPEED = 70f
    /** A crash into a wall under this speed does no harm. */
    const val CRASH_SPEED = 120f

    /** Health of a car with a body [pictureWidth] picture pixels long. */
    fun hpFor(pictureWidth: Float): Int = (pictureWidth * 2f).toInt()

    /** The damage a car does to what it runs over at [speed]. */
    fun runOverDamage(speed: Float): Int = 6 + (speed * 0.09f).toInt()

    /** Damage the car takes from hitting a wall at [speed]. */
    fun crashDamage(speed: Float): Int = if (speed <= CRASH_SPEED) 0 else 1 + ((speed - CRASH_SPEED) * 0.05f).toInt()

    /** The new lock progress (0..1) after [dt] seconds, with the button held or not. */
    fun pickProgress(progress: Float, dt: Float, holding: Boolean): Float =
        if (holding) (progress + dt / LOCK_SECONDS).coerceAtMost(1f)
        else (progress - dt / LOCK_SECONDS * LOCK_FALLBACK).coerceAtLeast(0f)

    fun angleDifference(target: Float, from: Float): Float {
        var d = (target - from) % (2f * PI.toFloat())
        if (d > PI) d -= 2f * PI.toFloat()
        if (d < -PI) d += 2f * PI.toFloat()
        return d
    }

    /**
     * Steers and accelerates [car] for [dt] seconds towards the direction ([dx], [dy]) the joystick
     * points to ([active] false: nobody pushes, the car rolls to a stop). The car turns at a fixed rate and
     * eases off the pedal while it is pointing away from where the player wants to go.
     */
    fun drive(car: CarComponent, active: Boolean, dx: Float, dy: Float, dt: Float) {
        var target = 0f
        if (active) {
            val wanted = atan2(dy, dx)
            val diff = angleDifference(wanted, car.heading)
            val step = TURN_RATE * dt
            car.heading += diff.coerceIn(-step, step)
            if (car.heading > PI) car.heading -= 2f * PI.toFloat()
            if (car.heading < -PI) car.heading += 2f * PI.toFloat()
            val magnitude = (abs(dx) + abs(dy)).coerceIn(0f, 1f).let { max(it, 0.6f) }
            target = MAX_SPEED * magnitude * max(0.3f, cos(diff))
        }
        val rate = if (target > car.speed) ACCELERATION else BRAKING
        car.speed += (target - car.speed) * (dt * rate).coerceAtMost(1f)
        if (car.speed < 2f && target == 0f) car.speed = 0f
    }
}
