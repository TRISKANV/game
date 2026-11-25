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
 * Pre-allocated render buffer. The engine thread fills it and then publishes
 * it inside GameState; the UI thread only reads it. The engine rotates
 * through several buffers so the one being drawn is never rewritten.
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

    /**
     * Which sprite to draw. Meaning depends on [kind]: EnemyType ordinal for
     * enemies and bosses, PickupType ordinal for pickups, WeaponType ordinal for
     * projectiles, and the facing row (see [Facing]) for the player. -1 = none.
     */
    val spriteId       = IntArray(MAX_RENDER_ENTITIES)

    // Flag bit masks
    companion object {
        const val FLAG_FLASHING    = 1
        const val FLAG_SHOW_HP_BAR = 2
        const val FLAG_HAS_AURA    = 4
        const val FLAG_IS_CRITICAL = 8
        const val FLAG_IS_BOSS     = 16
        const val FLAG_MOVING      = 32
        const val FLAG_IS_MINI_BOSS = 64
        /** The guard is close enough to the player to raise his fists. */
        const val FLAG_WINDING_UP = 128
        /** The guard has just noticed the player: a "!" over his head. */
        const val FLAG_ALERTED = 256
        /** The dog is running, not trotting. */
        const val FLAG_RUNNING = 512
        /** The guard is on patrol, walking slowly: his steps are slower too. */
        const val FLAG_STROLLING = 1024
        /** The officer who recognised the player is shouting: a speech bubble over his head. */
        const val FLAG_SHOUTING = 2048

        /** The guard's punch lasts this long after the hit lands; [lifetime] holds the seconds since it did. */
        const val GUARD_PUNCH_SECONDS = 0.42f
        /** [lifetime] of a guard that has not punched in this cooldown. */
        const val GUARD_NO_PUNCH = 99f
        /** Distance (world px) at which a guard starts raising his fists. */
        const val GUARD_WINDUP_RANGE = 70f

        /** [spriteId] of a particle that is the whip cable instead of a dot. */
        const val SPRITE_WHIP_LASH = 100
        const val SPRITE_DDOS_RING = 101
        const val SPRITE_ZERO_DAY_BEAM = 102
        const val SPRITE_LOGIC_BOMB = 103
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
        eDamageValue: Int        = 0,
        eSpriteId   : Int        = -1
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
        spriteId[i]    = eSpriteId
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
    /** A leash between a patrolling guard and his dog: [x],[y] is the guard, [width],[height] the dog (world coordinates). */
    const val LEASH        = 8
}

/** Row of the player sprite sheet for each direction the character can face. */
object Facing {
    const val DOWN  = 0
    const val UP    = 1
    const val LEFT  = 2
    const val RIGHT = 3
    const val DOWN_LEFT  = 4
    const val DOWN_RIGHT = 5

    /** Movement is vertical (or horizontal) when it is this many times larger on that axis. */
    private const val AXIS_DOMINANCE = 2f

    /**
     * Direction for a movement of ([vx], [vy]). Down-left and down-right have their
     * own sprites; for diagonals going up there is no back-facing diagonal art, so
     * those use the side view.
     */
    fun of(vx: Float, vy: Float): Int {
        val ax = if (vx < 0f) -vx else vx
        val ay = if (vy < 0f) -vy else vy
        return when {
            ay > ax * AXIS_DOMINANCE -> if (vy < 0f) UP else DOWN
            ax > ay * AXIS_DOMINANCE -> if (vx < 0f) LEFT else RIGHT
            vy > 0f                  -> if (vx < 0f) DOWN_LEFT else DOWN_RIGHT
            else                     -> if (vx < 0f) LEFT else RIGHT
        }
    }
}

/** Thin wrapper that carries the buffer inside GameState. */
data class RenderSnapshot(
    val buffer: RenderBuffer = RenderBuffer()
)
