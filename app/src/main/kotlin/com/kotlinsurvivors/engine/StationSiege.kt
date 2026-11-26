package com.kotlinsurvivors.engine

import com.kotlinsurvivors.features.game.domain.model.StationPhase

/**
 * The rules of the siege of the police station, apart from the engine so they can be tested on their own.
 *
 * Once the officers recognise the player the street door is shut and reinforcements keep coming
 * until [GOAL] officers have been put down; then the police chief wakes up. When he falls he drops
 * the key, and taking it opens the street door.
 *
 * Pure Kotlin, no Android types.
 */
class StationSiege {

    var phase = StationPhase.QUIET;  private set
    /** Officers put down since the alarm went. */
    var kills = 0;                   private set
    private var waveClock = 0f

    fun reset() {
        phase = StationPhase.QUIET; kills = 0; waveClock = 0f
    }

    /** The alarm goes. True only the first time. */
    fun alarm(): Boolean {
        if (phase != StationPhase.QUIET) return false
        phase = StationPhase.SIEGE
        kills = 0
        waveClock = FIRST_WAVE_DELAY
        return true
    }

    /** An officer fell. True on the one kill that completes the quota and wakes the chief. */
    fun copKilled(): Boolean {
        if (phase != StationPhase.SIEGE) return false
        kills++
        if (kills < GOAL) return false
        phase = StationPhase.CHIEF
        return true
    }

    /** The chief woke up by himself (somebody hurt him in his sleep). True if that changed the phase. */
    fun chiefWoke(): Boolean {
        if (phase != StationPhase.SIEGE && phase != StationPhase.QUIET) return false
        phase = StationPhase.CHIEF
        return true
    }

    /** The chief is dead and the key lies on the floor. */
    fun chiefKilled(): Boolean {
        if (phase != StationPhase.CHIEF) return false
        phase = StationPhase.KEY
        return true
    }

    /** The player has the key: the street door opens for good. */
    fun keyTaken(): Boolean {
        if (phase != StationPhase.KEY) return false
        phase = StationPhase.OPEN
        return true
    }

    /** The door is shut from the alarm until the key is taken. */
    val doorShut: Boolean get() = phase == StationPhase.SIEGE || phase == StationPhase.CHIEF || phase == StationPhase.KEY

    /** The lights and the siren go on from the alarm until the chief is dead. */
    val alarmOn: Boolean get() = phase == StationPhase.SIEGE || phase == StationPhase.CHIEF

    /**
     * How many officers to bring in now, given how many are on the floor. A wave comes every
     * [WAVE_EVERY] seconds, never more than [MAX_ALIVE] at a time and never more than are needed
     * to reach [GOAL] together with those already down.
     */
    fun reinforcements(dt: Float, alive: Int): Int {
        if (phase != StationPhase.SIEGE) return 0
        waveClock -= dt
        if (waveClock > 0f) return 0
        val missing = GOAL - kills - alive
        val room    = MAX_ALIVE - alive
        val n = minOf(WAVE_SIZE, missing, room)
        waveClock = if (n > 0) WAVE_EVERY else RETRY_EVERY
        return n.coerceAtLeast(0)
    }

    companion object {
        const val GOAL = 50
        const val MAX_ALIVE = 12
        const val WAVE_SIZE = 3
        const val WAVE_EVERY = 4f
        const val RETRY_EVERY = 1f
        const val FIRST_WAVE_DELAY = 3f
    }
}
