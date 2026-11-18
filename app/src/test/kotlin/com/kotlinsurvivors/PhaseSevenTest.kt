package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.components.PickupType
import com.kotlinsurvivors.engine.ecs.components.WeaponType
import com.kotlinsurvivors.engine.rendering.Facing
import com.kotlinsurvivors.engine.rendering.RenderBuffer
import com.kotlinsurvivors.engine.rendering.RenderKind
import com.kotlinsurvivors.engine.rendering.SpriteAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The renderer cuts the sprite files into cells by fixed sizes. These tests
 * read the real files and fail if an image no longer matches what the
 * renderer expects, instead of finding out as a garbled sprite on the phone.
 */
class SpriteAssetsTest {

    private val dir = File("src/main/assets/sprites")

    private fun image(name: String): BufferedImage {
        val file = File(dir, name)
        assertTrue("Missing sprite file $name", file.isFile)
        return ImageIO.read(file) ?: error("Cannot decode $name")
    }

    @Test
    fun playerSheetHasFiveColumnsAndOneRowPerFacing() {
        val sheet = image("player.png")
        assertEquals(0, sheet.width % SpriteAtlas.PLAYER_COLUMNS)
        assertEquals(0, sheet.height % SpriteAtlas.PLAYER_ROWS)
        assertEquals("one sheet row per Facing value", SpriteAtlas.PLAYER_ROWS - 1, Facing.DOWN_RIGHT)
    }

    @Test
    fun everyEnemyTypeHasTwoFrames() {
        for (type in EnemyType.values()) {
            val sheet = image("enemy_" + type.name.lowercase() + ".png")
            assertEquals("${type.name} needs ${SpriteAtlas.ENEMY_FRAMES} equal frames", 0, sheet.width % SpriteAtlas.ENEMY_FRAMES)
        }
    }

    @Test
    fun pickupSheetCoversEveryPickupType() {
        val sheet = image("pickups.png")
        assertEquals(SpriteAtlas.ITEM_CELL * PickupType.values().size, sheet.width)
    }

    @Test
    fun projectileSheetCoversEveryWeaponType() {
        val sheet = image("projectiles.png")
        assertEquals(SpriteAtlas.ITEM_CELL * WeaponType.values().size, sheet.width)
    }

    @Test
    fun groundTileHasTheExpectedSize() {
        val tile = image("ground.png")
        assertEquals(SpriteAtlas.GROUND_TILE, tile.width)
        assertEquals(SpriteAtlas.GROUND_TILE, tile.height)
    }

    @Test
    fun decalSheetIsMadeOfWholeCells() {
        val sheet = image("decals.png")
        assertEquals(0, sheet.width % SpriteAtlas.DECAL_CELL)
    }

    @Test
    fun interfaceImagesExistAndAreSquare() {
        val files = listOf("ui_joystick_pad.png", "ui_joystick_nub.png", "ui_icon_pause.png", "ui_icon_money.png")
        for (name in files) {
            val img = image(name)
            assertEquals("$name should be square", img.width, img.height)
        }
        assertTrue("Kenney credit file is missing", File(dir, "CREDITS.txt").isFile)
    }

    @Test
    fun roomImageMatchesTheLayoutSize() {
        val room = image("room.png")
        assertEquals(com.kotlinsurvivors.features.room.domain.RoomLayout.WIDTH.toInt(), room.width)
        assertEquals(com.kotlinsurvivors.features.room.domain.RoomLayout.HEIGHT.toInt(), room.height)
    }

    @Test
    fun menuBackgroundAndGroundTileAreOpaque() {
        for (name in listOf("menu_bg.png", "ground.png")) {
            val img = image(name)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                assertEquals("$name has a transparent pixel at $x,$y", 255, (img.getRGB(x, y) ushr 24))
            }
        }
    }
}

class RenderBufferSpriteTest {

    @Test
    fun spriteIdIsStoredPerEntity() {
        val buffer = RenderBuffer()
        buffer.write(RenderKind.ENEMY, 0f, 0f, eSpriteId = EnemyType.TANK.ordinal)
        buffer.write(RenderKind.PLAYER, 0f, 0f, eSpriteId = Facing.LEFT, eFlags = RenderBuffer.FLAG_MOVING)
        buffer.write(RenderKind.PARTICLE, 0f, 0f)

        assertEquals(EnemyType.TANK.ordinal, buffer.spriteId[0])
        assertEquals(Facing.LEFT, buffer.spriteId[1])
        assertTrue(buffer.hasFlag(1, RenderBuffer.FLAG_MOVING))
        assertEquals("entries without a sprite keep -1", -1, buffer.spriteId[2])
    }

    @Test
    fun flagsDoNotOverlap() {
        val flags = listOf(
            RenderBuffer.FLAG_FLASHING, RenderBuffer.FLAG_SHOW_HP_BAR, RenderBuffer.FLAG_HAS_AURA,
            RenderBuffer.FLAG_IS_CRITICAL, RenderBuffer.FLAG_IS_BOSS, RenderBuffer.FLAG_MOVING
        )
        var seen = 0
        for (flag in flags) {
            assertEquals("flag $flag overlaps another", 0, seen and flag)
            seen = seen or flag
        }
    }

    @Test
    fun downwardDiagonalsHaveTheirOwnSprites() {
        assertEquals(Facing.DOWN_RIGHT, Facing.of(1f, 1f))
        assertEquals(Facing.DOWN_LEFT,  Facing.of(-1f, 1f))
        assertEquals(Facing.DOWN_LEFT,  Facing.of(-1f, 1.9f))
        assertEquals(Facing.DOWN_RIGHT, Facing.of(1.9f, 1f))
    }

    @Test
    fun upwardDiagonalsFallBackToTheSideView() {
        assertEquals(Facing.RIGHT, Facing.of(1f, -1f))
        assertEquals(Facing.LEFT,  Facing.of(-1f, -1.5f))
    }

    @Test
    fun mostlyStraightMovementUsesFourMainViews() {
        assertEquals(Facing.DOWN,  Facing.of(0.3f, 1f))
        assertEquals(Facing.UP,    Facing.of(-0.4f, -1f))
        assertEquals(Facing.DOWN,  Facing.of(0f, 1f))
        assertEquals(Facing.RIGHT, Facing.of(1f, 0f))
        assertEquals(Facing.LEFT,  Facing.of(-1f, 0.4f))
    }
}
