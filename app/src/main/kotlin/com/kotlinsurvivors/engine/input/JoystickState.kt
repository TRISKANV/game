package com.kotlinsurvivors.engine.input

import androidx.compose.ui.geometry.Offset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable snapshot of the virtual joystick state.
 * Passed to the movement system each frame.
 */
data class JoystickState(
    val isActive: Boolean = false,
    val dx: Float = 0f,   // normalized [-1, 1]
    val dy: Float = 0f,   // normalized [-1, 1]
    val rawX: Float = 0f,
    val rawY: Float = 0f
)

/**
 * VirtualJoystick
 *
 * Thread-safe joystick tracker written by the UI thread (touch events)
 * and read by the engine thread (game loop).
 *
 * Uses @Volatile on every field so the engine thread always sees the
 * latest values without needing a mutex (acceptable for floats — worst
 * case is one slightly stale read per frame, which is imperceptible).
 *
 * Key fix: tracks whether a valid onDown() happened before accepting
 * onMove() calls, preventing crashes from partial gesture sequences.
 */
class VirtualJoystick {

    val maxRadius = 80f

    @Volatile private var centerX   = 0f
    @Volatile private var centerY   = 0f
    @Volatile private var currentX  = 0f
    @Volatile private var currentY  = 0f
    @Volatile var isActive          = false

    // Guards against onMove() being called without a prior onDown()
    private val hasValidCenter = AtomicBoolean(false)

    fun onDown(x: Float, y: Float) {
        centerX  = x
        centerY  = y
        currentX = x
        currentY = y
        hasValidCenter.set(true)
        isActive = true
    }

    fun onMove(x: Float, y: Float) {
        // Ignore moves that have no valid starting center
        if (!hasValidCenter.get()) return
        currentX = x
        currentY = y
    }

    fun onUp() {
        isActive = false
        hasValidCenter.set(false)
        // Reset current to center so next getState() sees zero delta
        currentX = centerX
        currentY = centerY
    }

    fun getState(): JoystickState {
        if (!isActive || !hasValidCenter.get()) return JoystickState()

        // Snapshot volatile fields once to avoid torn reads
        val cx = centerX
        val cy = centerY
        val px = currentX
        val py = currentY

        val rawX = px - cx
        val rawY = py - cy
        val dist = kotlin.math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()

        if (dist < 4f) return JoystickState(isActive = true, dx = 0f, dy = 0f)

        val clamped = dist.coerceAtMost(maxRadius)
        val nx = rawX / dist
        val ny = rawY / dist

        return JoystickState(
            isActive = true,
            dx       = nx * (clamped / maxRadius),
            dy       = ny * (clamped / maxRadius),
            rawX     = nx * clamped,
            rawY     = ny * clamped
        )
    }

    /** Returns the clamped knob offset for rendering. */
    fun getKnobOffset(): Offset {
        if (!isActive || !hasValidCenter.get()) return Offset.Zero
        val cx   = centerX;  val cy = centerY
        val rawX = currentX - cx
        val rawY = currentY - cy
        val dist = kotlin.math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
        if (dist < 4f) return Offset.Zero
        val clamped = dist.coerceAtMost(maxRadius)
        return Offset(rawX / dist * clamped, rawY / dist * clamped)
    }

    fun getCenterX() = centerX
    fun getCenterY() = centerY
}
