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
class LevelArt private constructor(val level: Bitmap, val glow: Bitmap) {
    companion object {
        private const val TAG = "KS_LevelArt"

        fun loadLevel1(context: Context): LevelArt? = try {
            LevelArt(
                level = SpriteAtlas.bitmap(context, "level1.png"),
                glow  = SpriteAtlas.bitmap(context, "glow.png")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the level 1 art", e)
            null
        }
    }
}
