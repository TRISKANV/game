package com.kotlinsurvivors.engine.rendering

import com.kotlinsurvivors.engine.ecs.components.RenderShape

/**
 * RenderSnapshot — zero-allocation version.
 *
 * PROBLEM WITH PREVIOUS VERSION:
 *   The old RenderSnapshot held a List<RenderEntity> where each RenderEntity
 *   was a data class (~128 bytes on JVM). buildRenderSnapshot() created
 *   200 new RenderEntity objects per frame × 60fps = 12,000 objects/second
 *   = 1.5 MB/second of garbage. Over 2 minutes that's ~180 MB of GC pressure,
 *   causing increasingly long GC pauses until OOM kills the app.
 *
 * FIX:
 *   Replace List<RenderEntity> with parallel primitive FloatArrays and IntArrays.
 *   Primitives are stored inline in the array — no object headers, no GC roots.
 *   The engine thread writes into pre-allocated arrays each frame.
 *   The UI thread reads them. Zero allocations in the hot path.
 *
 * Layout (one entry per entity, indexed by [i]):
 *   kind[i]         — RenderEntityKind ordinal (Int)
 *   x[i], y[i]      — world position (Float)
 *   rotation[i]     — rotation (Float)
 *   shapeOrdinal[i] — RenderShape ordinal (Int)
 *   color[i]        — packed ARGB Long stored as two Ints: colorHi, colorLo
 *   ... etc.
 *
 * For simplicity and safety we use a single FloatArray + IntArray per category.
 * The renderer reads by index — no boxing, no allocation.
 */

// Maximum entities we'll ever render in one frame.
// 110 enemies + 30 projectiles + 30 pickups + 100 particles + 20 dmgnums + orbitals + player
const val MAX_RENDER_ENTITIES = 400

/**
 * Pre-allocated render buffer shared between engine thread (write) and
 * UI thread (read). Written atomically by the engine at end of each tick.
 *
 * Using a simple struct-of-arrays layout for cache efficiency.
 */
class RenderBuffer {
    // Number of valid entries written this frame
    var count: Int = 0

    // Camera target (for smooth follow)
    var cameraTargetX: Float = 0f
    var cameraTargetY: Float = 0f

    // Per-entity arrays — indexed 0..(count-1)
    val kind           = IntArray(MAX_RENDER_ENTITIES)   // RenderEntityKind.ordinal
    val x              = FloatArray(MAX_RENDER_ENTITIES)
    val y              = FloatArray(MAX_RENDER_ENTITIES)
    val rotation       = FloatArray(MAX_RENDER_ENTITIES)
    val shapeOrdinal   = IntArray(MAX_RENDER_ENTITIES)   // RenderShape.ordinal
    val colorHi        = IntArray(MAX_RENDER_ENTITIES)   // upper 32 bits of color Long
    val colorLo        = IntArray(MAX_RENDER_ENTITIES)   // lower 32 bits of color Long
    val secColorHi     = IntArray(MAX_RENDER_ENTITIES)
    val secColorLo     = IntArray(MAX_RENDER_ENTITIES)
    val width          = FloatArray(MAX_RENDER_ENTITIES)
    val height         = FloatArray(MAX_RENDER_ENTITIES)
    val glowRadius     = FloatArray(MAX_RENDER_ENTITIES)
    val glowColorHi    = IntArray(MAX_RENDER_ENTITIES)
    val glowColorLo    = IntArray(MAX_RENDER_ENTITIES)
    val flashTimer     = FloatArray(MAX_RENDER_ENTITIES)
    val hpPercent      = FloatArray(MAX_RENDER_ENTITIES)
    val flags          = IntArray(MAX_RENDER_ENTITIES)   // bit-packed booleans
    val invincTimer    = FloatArray(MAX_RENDER_ENTITIES)
    val auraRadius     = FloatArray(MAX_RENDER_ENTITIES)
    val orbitAngle     = FloatArray(MAX_RENDER_ENTITIES)
    val orbitRadius    = FloatArray(MAX_RENDER_ENTITIES)
    val orbitSize      = FloatArray(MAX_RENDER_ENTITIES)
    val lifetime       = FloatArray(MAX_RENDER_ENTITIES)
    val maxLifetime    = FloatArray(MAX_RENDER_ENTITIES)
    val damageValue    = IntArray(MAX_RENDER_ENTITIES)

    // Flag bit masks
    companion object {
        const val FLAG_FLASHING    = 1
        const val FLAG_SHOW_HP_BAR = 2
        const val FLAG_HAS_AURA    = 4
        const val FLAG_IS_CRITICAL = 8
        const val FLAG_IS_BOSS     = 16
    }

    fun reset() { count = 0 }

    /** Write one entity into the next slot. Returns the slot index. */
    fun write(
        kindOrdinal : Int,
        ex          : Float,
        ey          : Float,
        eRotation   : Float      = 0f,
        eShape      : RenderShape = RenderShape.CIRCLE,
        eColor      : Long       = 0xFFFFFFFFL,
        eSecColor   : Long       = 0xFFAAAAAAL,
        eWidth      : Float      = 16f,
        eHeight     : Float      = 16f,
        eGlowRadius : Float      = 0f,
        eGlowColor  : Long       = 0L,
        eFlashTimer : Float      = 0f,
        eHpPercent  : Float      = 1f,
        eFlags      : Int        = 0,
        eInvincTimer: Float      = 0f,
        eAuraRadius : Float      = 0f,
        eOrbitAngle : Float      = 0f,
        eOrbitRadius: Float      = 0f,
        eOrbitSize  : Float      = 0f,
        eLifetime   : Float      = 1f,
        eMaxLifetime: Float      = 1f,
        eDamageValue: Int        = 0
    ): Boolean {
        if (count >= MAX_RENDER_ENTITIES) return false
        val i = count++
        kind[i]        = kindOrdinal
        x[i]           = ex;        y[i]     = ey
        rotation[i]    = eRotation
        shapeOrdinal[i]= eShape.ordinal
        colorHi[i]     = (eColor    ushr 32).toInt()
        colorLo[i]     = (eColor    and 0xFFFFFFFFL).toInt()
        secColorHi[i]  = (eSecColor ushr 32).toInt()
        secColorLo[i]  = (eSecColor and 0xFFFFFFFFL).toInt()
        width[i]       = eWidth;    height[i]  = eHeight
        glowRadius[i]  = eGlowRadius
        glowColorHi[i] = (eGlowColor ushr 32).toInt()
        glowColorLo[i] = (eGlowColor and 0xFFFFFFFFL).toInt()
        flashTimer[i]  = eFlashTimer
        hpPercent[i]   = eHpPercent
        flags[i]       = eFlags
        invincTimer[i] = eInvincTimer
        auraRadius[i]  = eAuraRadius
        orbitAngle[i]  = eOrbitAngle
        orbitRadius[i] = eOrbitRadius
        orbitSize[i]   = eOrbitSize
        lifetime[i]    = eLifetime;  maxLifetime[i] = eMaxLifetime
        damageValue[i] = eDamageValue
        return true
    }

    /** Reconstruct color Long from stored hi/lo ints */
    fun getColor(i: Int): Long    = (colorHi[i].toLong() shl 32) or (colorLo[i].toLong() and 0xFFFFFFFFL)
    fun getSecColor(i: Int): Long = (secColorHi[i].toLong() shl 32) or (secColorLo[i].toLong() and 0xFFFFFFFFL)
    fun getGlowColor(i: Int): Long= (glowColorHi[i].toLong() shl 32) or (glowColorLo[i].toLong() and 0xFFFFFFFFL)
    fun hasFlag(i: Int, flag: Int): Boolean = (flags[i] and flag) != 0
}

// Kind constants — same ordinals as the old RenderEntityKind enum
object RenderKind {
    const val PLAYER       = 0
    const val ENEMY        = 1
    const val BOSS         = 2
    const val PROJECTILE   = 3
    const val PICKUP       = 4
    const val PARTICLE     = 5
    const val DAMAGE_NUMBER= 6
    const val ORBITAL      = 7
}

// Keep RenderSnapshot as a thin wrapper for backward compat with GameState
data class RenderSnapshot(
    val buffer: RenderBuffer = RenderBuffer()
)

// Keep these for backward compat with GameRenderer (will update renderer too)
enum class RenderEntityKind {
    PLAYER, ENEMY, BOSS, PROJECTILE, PICKUP, PARTICLE, DAMAGE_NUMBER, ORBITAL
}

data class RenderEntity(
    val kind           : RenderEntityKind,
    val x              : Float,
    val y              : Float,
    val rotation       : Float       = 0f,
    val shape          : RenderShape = RenderShape.CIRCLE,
    val color          : Long        = 0xFFFFFFFF,
    val secondaryColor : Long        = 0xFFAAAAAA,
    val width          : Float       = 16f,
    val height         : Float       = 16f,
    val glowRadius     : Float       = 0f,
    val glowColor      : Long        = 0x00000000,
    val alpha          : Float       = 1f,
    val flashTimer     : Float       = 0f,
    val isFlashing     : Boolean     = false,
    val hpPercent      : Float       = 1f,
    val showHealthBar  : Boolean     = false,
    val invincibleTimer: Float       = 0f,
    val hasAura        : Boolean     = false,
    val auraRadius     : Float       = 0f,
    val orbitAngle     : Float       = 0f,
    val orbitRadius    : Float       = 0f,
    val orbitSize      : Float       = 0f,
    val lifetime       : Float       = 1f,
    val maxLifetime    : Float       = 1f,
    val damageValue    : Int         = 0,
    val isCritical     : Boolean     = false
)
