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
    fun playerSheetHasFiveColumnsAndFourRows() {
        val sheet = image("player.png")
        assertEquals(0, sheet.width % SpriteAtlas.PLAYER_COLUMNS)
        assertEquals(0, sheet.height % SpriteAtlas.PLAYER_ROWS)
        assertTrue(Facing.RIGHT == SpriteAtlas.PLAYER_ROWS - 1)
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
}
