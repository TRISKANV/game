package com.kotlinsurvivors.engine.map

import android.content.Context
import android.util.Log

/** Reads a level's collision grid from assets/sprites/. Returns null if it is missing or malformed. */
object LevelMapLoader {
    private const val TAG = "KS_LevelMap"

    fun loadLevel1(context: Context): LevelMap? = try {
        val bytes = context.assets.open("sprites/level1_solid.bin").use { it.readBytes() }
        LevelMap(Level1.CELLS, Level1.CELL_SIZE, bytes)
    } catch (e: Exception) {
        Log.e(TAG, "Could not load the level 1 collision grid, playing without it", e)
        null
    }
}
