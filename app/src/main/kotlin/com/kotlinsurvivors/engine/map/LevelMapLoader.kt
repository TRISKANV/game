package com.kotlinsurvivors.engine.map

import android.content.Context
import android.util.Log

/** Reads a level's collision grid from assets/sprites/. Returns null if it is missing or malformed. */
object LevelMapLoader {
    private const val TAG = "KS_LevelMap"

    fun loadLevel1(context: Context): LevelMap? = load(context, Levels.ONE)

    fun load(context: Context, info: LevelInfo): LevelMap? = try {
        val bytes = context.assets.open("sprites/${info.solidFile}").use { it.readBytes() }
        if (info.number == 1) Level1Npc.stampSolid(bytes, info.cells, info.cellSize)   // the neighbour is an obstacle too
        LevelMap(info.cells, info.cellSize, bytes)
    } catch (e: Exception) {
        Log.e(TAG, "Could not load the collision grid of level ${info.number}, playing without it", e)
        null
    }
}
