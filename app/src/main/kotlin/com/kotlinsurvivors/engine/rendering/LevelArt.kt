package com.kotlinsurvivors.engine.rendering

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kotlinsurvivors.R
import com.kotlinsurvivors.engine.map.LevelInfo
import com.kotlinsurvivors.engine.map.Levels

/**
 * The baked picture of a level and the soft light sprite drawn on top of each lamp.
 * The picture covers the whole world at [LevelInfo.imageScale] world pixels per image pixel.
 * Load off the main thread; null when a file is missing, and the renderer then falls back to
 * the plain ground.
 */
class LevelArt private constructor(
    val info: LevelInfo,
    val level: Bitmap,
    val glow: Bitmap,
    val npc: Bitmap?,
    /** The barrier that shuts the end of the street in Level 1 until the first boss is down, or the shutter of the station's street door. */
    val exitBarrier: Bitmap?,
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

        fun loadLevel1(context: Context): LevelArt? = load(context, Levels.ONE)

        fun load(context: Context, info: LevelInfo): LevelArt? = try {
            val isFirst = info.number == 1
            LevelArt(
                info  = info,
                level = SpriteAtlas.bitmap(context, info.artFile),
                glow  = SpriteAtlas.bitmap(context, "glow.png"),
                npc   = if (!isFirst) null else try { SpriteAtlas.bitmap(context, "npc_leek.png") } catch (e: Exception) {
                    Log.w(TAG, "No picture for the neighbour, he will not appear", e); null
                },
                exitBarrier = when (info.number) {
                    1    -> "level1_exit_barrier.png"
                    3    -> "station_shutter.png"
                    else -> null
                }?.let { file ->
                    try { SpriteAtlas.bitmap(context, file) } catch (e: Exception) {
                        Log.w(TAG, "No picture for the barrier $file", e); null
                    }
                },
                npcLines = if (isFirst) Array(NPC_LINES.size) { context.getString(NPC_LINES[it]) } else emptyArray()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the art of level ${info.number}", e)
            null
        }
    }
}
