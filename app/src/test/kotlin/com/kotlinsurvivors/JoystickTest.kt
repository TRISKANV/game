package com.kotlinsurvivors

import com.kotlinsurvivors.engine.input.VirtualJoystick
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class VirtualJoystickTest {

    private lateinit var joystick: VirtualJoystick

    @Before
    fun setup() {
        joystick = VirtualJoystick()
    }

    @Test
    fun `initial state is inactive with zero deltas`() {
        val state = joystick.getState()
        assertFalse(state.isActive)
        assertEquals(0f, state.dx, 0.001f)
        assertEquals(0f, state.dy, 0.001f)
    }

    @Test
    fun `onDown activates joystick`() {
        joystick.onDown(100f, 100f)
        assertTrue(joystick.getState().isActive)
    }

    @Test
    fun `onUp deactivates joystick`() {
        joystick.onDown(100f, 100f)
        joystick.onUp()
        assertFalse(joystick.getState().isActive)
    }

    @Test
    fun `small movement returns near-zero delta`() {
        joystick.onDown(100f, 100f)
        joystick.onMove(101f, 100f) // 1 pixel — below dead zone
        val state = joystick.getState()
        // Should be active but near zero
        assertEquals(0f, state.dx, 0.1f)
    }

    @Test
    fun `max displacement returns normalized delta magnitude of 1`() {
        joystick.onDown(100f, 100f)
        joystick.onMove(100f + joystick.maxRadius, 100f) // exactly at max radius, purely right
        val state = joystick.getState()
        assertEquals(1f, state.dx, 0.01f)
        assertEquals(0f, state.dy, 0.01f)
    }

    @Test
    fun `displacement beyond maxRadius is clamped to 1`() {
        joystick.onDown(100f, 100f)
        joystick.onMove(100f + joystick.maxRadius * 3f, 100f) // 3× max radius
        val state = joystick.getState()
        // dx must be clamped to 1
        assertTrue(state.dx <= 1.0f + 0.01f)
        assertTrue(state.dx >= 0.9f)
    }

    @Test
    fun `diagonal movement returns normalized direction`() {
        joystick.onDown(100f, 100f)
        val offset = joystick.maxRadius / sqrt(2f)
        joystick.onMove(100f + offset, 100f + offset)
        val state = joystick.getState()
        val magnitude = sqrt(state.dx * state.dx + state.dy * state.dy)
        assertEquals(1f, magnitude, 0.05f)
    }
}
