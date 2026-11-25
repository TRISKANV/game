package com.kotlinsurvivors.engine.map

/**
 * Where the run is in the story of its levels: which level is being played, whether the way out
 * of Level 1 is open, and the fade to black that covers the change of level.
 *
 * The way out is the south end of the street. It stays shut until the first boss is down
 * ([openExit]); once it is open, walking into [Level1.EXIT_X0]..[Level1.EXIT_X1] past
 * [Level1.EXIT_TRIGGER_Y] starts the trip. The screen fades out, the engine puts the next level in
 * place on the one frame [update] returns true, and the screen fades back in.
 *
 * Pure Kotlin, no Android types, so it is tested on the JVM.
 */
class LevelFlow {
    enum class Phase { PLAYING, LEAVING, ARRIVING }

    var level    = 1;                private set
    var exitOpen = false;            private set
    /** 0 = clear screen, 1 = black. */
    var fade     = 0f;               private set
    var phase    = Phase.PLAYING;    private set

    /** True from the moment the trip starts until the screen is clear again. */
    val isTravelling: Boolean get() = phase != Phase.PLAYING

    fun reset() {
        level = 1; exitOpen = false; fade = 0f; phase = Phase.PLAYING
    }

    /** Opens the way out. True only the first time, so the engine announces it once. */
    fun openExit(): Boolean {
        if (level != 1 || exitOpen) return false
        exitOpen = true
        return true
    }

    /** Starts the trip to the next level at once (laboratory). */
    fun startTrip() {
        if (level == 1 && phase == Phase.PLAYING) phase = Phase.LEAVING
    }

    fun isInExit(x: Float, y: Float): Boolean =
        level == 1 && exitOpen && x in Level1.EXIT_X0..Level1.EXIT_X1 && y >= Level1.EXIT_TRIGGER_Y

    /**
     * Advances the fade by [dt] seconds. Returns true on the one frame the screen is fully black and
     * the next level has to be put in place; [level] already holds the new number by then.
     */
    fun update(dt: Float, playerX: Float, playerY: Float): Boolean {
        when (phase) {
            Phase.PLAYING -> if (isInExit(playerX, playerY)) phase = Phase.LEAVING
            Phase.LEAVING -> {
                fade = (fade + dt / FADE_OUT_SECONDS).coerceAtMost(1f)
                if (fade >= 1f) {
                    level    = 2
                    exitOpen = false
                    phase    = Phase.ARRIVING
                    return true
                }
            }
            Phase.ARRIVING -> {
                fade = (fade - dt / FADE_IN_SECONDS).coerceAtLeast(0f)
                if (fade <= 0f) phase = Phase.PLAYING
            }
        }
        return false
    }

    companion object {
        const val FADE_OUT_SECONDS = 0.8f
        const val FADE_IN_SECONDS  = 0.9f
    }
}
