package com.kotlinsurvivors.engine.rendering

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kotlinsurvivors.R

/**
 * The baked picture of Level 1 and the soft light sprite drawn on top of each lamp.
 * level1.png covers the whole world at [com.kotlinsurvivors.engine.map.Level1.IMAGE_SCALE]
 * world pixels per image pixel. Load off the main thread; null when a file is missing,
 * and the renderer then falls back to the plain ground.
 */
class LevelArt private constructor(
    val level: Bitmap,
    val glow: Bitmap,
    val npc: Bitmap?,
    /** What the neighbour says, in the language of the phone when the level was loaded. */
    val npcLines: Array<String>
) {
    companion object {
        private const val TAG = "KS_LevelArt"

        /** The neighbour's lines in order; there must be [com.kotlinsurvivors.engine.map.Level1Npc.LINE_COUNT] of them. */
        private val NPC_LINES = intArrayOf(
            R.string.npc_leek_1, R.string.npc_leek_2, R.string.npc_leek_3, R.string.npc_leek_4,
            R.string.npc_leek_5, R.string.npc_leek_6, R.string.npc_leek_7, R.string.npc_leek_8,
            R.string.npc_leek_9, R.string.npc_leek_10, R.string.npc_leek_11, R.string.npc_leek_12,
            R.string.npc_leek_13, R.string.npc_leek_14
        )

        fun loadLevel1(context: Context): LevelArt? = try {
            LevelArt(
                level = SpriteAtlas.bitmap(context, "level1.png"),
                glow  = SpriteAtlas.bitmap(context, "glow.png"),
                npc   = try { SpriteAtlas.bitmap(context, "npc_leek.png") } catch (e: Exception) {
                    Log.w(TAG, "No picture for the neighbour, he will not appear", e); null
                },
                npcLines = Array(NPC_LINES.size) { context.getString(NPC_LINES[it]) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the level 1 art", e)
            null
        }
    }
}
