package com.kotlinsurvivors.engine.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.kotlinsurvivors.engine.ecs.components.EnemyType

/**
 * All the pixel-art images the game draws, decoded once.
 *
 * The files live in assets/sprites/ and are cut into cells by size:
 *  - player.png      5 columns (idle + 4 walk frames) x 6 rows (see [Facing])
 *  - enemy_*.png     2 animation frames side by side, one file per [EnemyType]
 *    except enemy_basic.png, the security guard: 8 columns (4 walk, 4 punch) x 8 rows,
 *    one per heading: right, down-right, down, down-left, left, up-left, up, up-right
 *  - pickups.png     12x12 cells in PickupType order
 *  - projectiles.png 12x12 cells in WeaponType order (the last one is the Bible)
 *  - chest.png       16x16 cells: 4 waiting on the floor, 5 opening
 *  - energy_can.png  18x28 cells: the can that heals, 4 frames of a glint sliding down it
 *  - ground.png      32x32 seamless tile
 *  - decals.png      28x28 cells of floor clutter
 *
 * Load it off the main thread with [load]; it returns null when an asset is
 * missing or cannot be decoded, and the renderer then falls back to plain
 * shapes so the game stays playable.
 */
class SpriteAtlas private constructor(
    val player: Bitmap,
    val enemies: Array<Bitmap>,
    val pickups: Bitmap,
    val projectiles: Bitmap,
    val chest: Bitmap,
    val energyCan: Bitmap,
    val ground: Bitmap,
    val decals: Bitmap,
    /** cars.png: one cell of [LevelCars.CELL_W] x [LevelCars.CELL_H] per look, the car facing right. Optional: without it cars are plain boxes. */
    val cars: Bitmap?
) {
    val playerCellW = player.width / PLAYER_COLUMNS
    val playerCellH = player.height / PLAYER_ROWS
    val decalCount  = decals.width / DECAL_CELL

    /** Width of one animation frame of the enemy sprite for [typeOrdinal]. */
    fun enemyFrameWidth(typeOrdinal: Int) = enemies[typeOrdinal].width / ENEMY_FRAMES

    val guardCellW = enemies[EnemyType.BASIC.ordinal].width / GUARD_COLUMNS
    val guardCellH = enemies[EnemyType.BASIC.ordinal].height / GUARD_ROWS

    val dogCellW = enemies[EnemyType.DOG.ordinal].width / DOG_COLUMNS
    val dogCellH = enemies[EnemyType.DOG.ordinal].height / DOG_ROWS

    companion object {
        private const val TAG = "KS_Sprites"
        private const val DIR = "sprites/"

        const val PLAYER_COLUMNS = 5
        const val PLAYER_ROWS    = 6
        const val ENEMY_FRAMES   = 2
        const val GUARD_COLUMNS  = 8
        const val GUARD_ROWS     = 8
        const val GUARD_WALK_FRAMES = 4
        // enemy_dog.png: 8 columns (0-3 trot, 4-5 gallop, 6-7 bite) x 8 rows (one per heading, as the guard)
        const val DOG_COLUMNS = 8
        const val DOG_ROWS    = 8
        const val DOG_TROT_FRAMES = 4
        const val DOG_GALLOP_FIRST = 4
        const val DOG_BITE_FIRST   = 6
        const val ITEM_CELL      = 12
        const val CHEST_CELL     = 16
        const val CAN_CELL_W     = 18
        const val CAN_CELL_H     = 28
        const val CAN_FRAMES     = 4
        const val CHEST_IDLE_FRAMES = 4
        const val CHEST_OPEN_FRAMES = 5
        const val DECAL_CELL     = 28
        const val GROUND_TILE    = 32

        fun load(context: Context): SpriteAtlas? = try {
            SpriteAtlas(
                player      = bitmap(context, "player.png"),
                enemies     = Array(EnemyType.values().size) { bitmap(context, enemyFile(EnemyType.values()[it])) },
                pickups     = bitmap(context, "pickups.png"),
                projectiles = bitmap(context, "projectiles.png"),
                chest       = bitmap(context, "chest.png"),
                energyCan   = bitmap(context, "energy_can.png"),
                ground      = bitmap(context, "ground.png"),
                decals      = bitmap(context, "decals.png"),
                cars        = try { bitmap(context, "cars.png") } catch (e: Exception) { Log.w(TAG, "No car sprites", e); null }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not load sprites, using shapes instead", e)
            null
        }

        /** Decodes one file from assets/sprites/ without any density scaling. */
        fun bitmap(context: Context, name: String): Bitmap {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.assets.open(DIR + name).use { stream ->
                return BitmapFactory.decodeStream(stream, null, options)
                    ?: throw IllegalStateException("Cannot decode $name")
            }
        }

        private fun enemyFile(type: EnemyType) = "enemy_" + type.name.lowercase() + ".png"
    }
}
