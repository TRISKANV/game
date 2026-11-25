package com.kotlinsurvivors.engine.map

/**
 * What the game needs to know about one level: how big it is, how its picture and collision
 * grid are laid out, where the run starts and where the lamps are. Pure data, so the engine,
 * the renderer and the tests all read the same numbers.
 */
class LevelInfo(
    val number     : Int,
    val worldSize  : Float,
    val cellSize   : Float,
    val cells      : Int,
    /** World pixels covered by one pixel of the level picture. */
    val imageScale : Float,
    val spawnX     : Float,
    val spawnY     : Float,
    /** Lamp positions as x, y pairs in world pixels. */
    val lamps      : FloatArray,
    /** Files in assets/sprites/. */
    val artFile    : String,
    val solidFile  : String
)

object Levels {
    val ONE = LevelInfo(
        number = 1, worldSize = Level1.WORLD_SIZE, cellSize = Level1.CELL_SIZE, cells = Level1.CELLS,
        imageScale = Level1.IMAGE_SCALE, spawnX = Level1.SPAWN_X, spawnY = Level1.SPAWN_Y, lamps = Level1.LAMPS,
        artFile = "level1.png", solidFile = "level1_solid.bin"
    )

    val TWO = LevelInfo(
        number = 2, worldSize = Level2.WORLD_SIZE, cellSize = Level2.CELL_SIZE, cells = Level2.CELLS,
        imageScale = Level2.IMAGE_SCALE, spawnX = Level2.SPAWN_X, spawnY = Level2.SPAWN_Y, lamps = Level2.LAMPS,
        artFile = "level2.png", solidFile = "level2_solid.bin"
    )

    fun of(number: Int): LevelInfo = if (number == 2) TWO else ONE
}
