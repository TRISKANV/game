package com.kotlinsurvivors.engine.rendering

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * The baked picture of Level 1 and the soft light sprite drawn on top of each lamp.
 * level1.png covers the whole world at [com.kotlinsurvivors.engine.map.Level1.IMAGE_SCALE]
 * world pixels per image pixel. Load off the main thread; null when a file is missing,
 * and the renderer then falls back to the plain ground.
 */
class LevelArt private constructor(val level: Bitmap, val glow: Bitmap, val npc: Bitmap?) {
    companion object {
        private const val TAG = "KS_LevelArt"

        fun loadLevel1(context: Context): LevelArt? = try {
            LevelArt(
                level = SpriteAtlas.bitmap(context, "level1.png"),
                glow  = SpriteAtlas.bitmap(context, "glow.png"),
                npc   = try { SpriteAtlas.bitmap(context, "npc_leek.png") } catch (e: Exception) {
                    Log.w(TAG, "No picture for the neighbour, he will not appear", e); null
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the level 1 art", e)
            null
        }
    }
}
