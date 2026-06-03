package com.kotlinsurvivors.engine.input

import androidx.compose.ui.geometry.Offset

/**
 * Immutable snapshot of the virtual joystick state.
 * Passed to the movement system each frame.
 */
data class JoystickState(
    val isActive: Boolean = false,
    val dx: Float = 0f,   // normalized [-1, 1]
    val dy: Float = 0f,   // normalized [-1, 1]
    val rawX: Float = 0f, // screen pixels from center
    val rawY: Float = 0f
)

/**
 * Mutable joystick tracker updated by the Compose touch handler.
 * Thread-safe reads via @Volatile fields.
 */
class VirtualJoystick {

    val maxRadius = 80f  // pixels

    @Volatile var centerX      = 0f
    @Volatile var centerY      = 0f
    @Volatile var currentX     = 0f
    @Volatile var currentY     = 0f
    @Volatile var isActive     = false

    fun onDown(x: Float, y: Float) {
        centerX   = x
        centerY   = y
        currentX  = x
        currentY  = y
        isActive  = true
    }

    fun onMove(x: Float, y: Float) {
        currentX  = x
        currentY  = y
    }

    fun onUp() {
        isActive  = false
        currentX  = centerX
        currentY  = centerY
    }

    fun getState(): JoystickState {
        if (!isActive) return JoystickState()

        val rawX  = currentX - centerX
        val rawY  = currentY - centerY
        val dist  = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()

        return if (dist < 4f) {
            JoystickState(isActive = true, dx = 0f, dy = 0f)
        } else {
            val clampedDist = dist.coerceAtMost(maxRadius)
            val nx = rawX / dist
            val ny = rawY / dist
            JoystickState(
                isActive = true,
                dx       = nx * (clampedDist / maxRadius),
                dy       = ny * (clampedDist / maxRadius),
                rawX     = nx * clampedDist,
                rawY     = ny * clampedDist
            )
        }
    }

    /** Returns the clamped knob offset for rendering. */
    fun getKnobOffset(): Offset {
        if (!isActive) return Offset.Zero
        val rawX  = currentX - centerX
        val rawY  = currentY - centerY
        val dist  = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
        if (dist < 4f) return Offset.Zero
        val clamped = dist.coerceAtMost(maxRadius)
        return Offset(rawX / dist * clamped, rawY / dist * clamped)
    }
}
