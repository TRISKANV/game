package com.kotlinsurvivors

import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.audio.SoundEffects
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.systems.MovementSystem
import com.kotlinsurvivors.engine.ecs.systems.WeaponSystem
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.LevelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundTest {

    private class Recorder : SoundEffects {
        val played = mutableListOf<Sound>()
        override fun play(sound: Sound) { played.add(sound) }
    }

    @Test fun everySoundHasItsFileAndNamesAreUnique() {
        val dir = File("src/main/assets/sfx")
        for (s in Sound.values()) assertTrue("missing sfx/${s.file}.ogg", File(dir, s.file + ".ogg").length() > 500)
        assertEquals(Sound.values().size, Sound.values().map { it.file }.toSet().size)
        // and no file is left in the folder that no sound uses
        val unused = dir.listFiles()!!.map { it.name.removeSuffix(".ogg") } - Sound.values().map { it.file }.toSet()
        assertTrue("unused sound files: $unused", unused.isEmpty())
    }

    @Test fun volumesStayInRange() {
        for (s in Sound.values()) assertTrue("${s.name} volume", s.volume in 0.1f..1f)
    }

    @Test fun theStarterWhipCracksOnlyWhenSomebodyIsInReach() {
        val world = World()
        EntityFactory.createPlayer(world, 500f, 500f)
        val rec = Recorder()
        val weapons = WeaponSystem(rec)
        repeat(120) { weapons.update(world, 1f / 60f) }
        assertTrue("whip sounded with nobody near", rec.played.isEmpty())
        EntityFactory.createEnemy(world, EnemyType.BASIC, 560f, 500f)
        repeat(5) { weapons.update(world, 1f / 60f) }
        assertEquals(listOf(Sound.SHOT_WHIP), rec.played)
    }

    private fun wall(): LevelMap {
        val n = 64
        val grid = ByteArray(n * n)
        for (i in 0 until n) { grid[i] = 1; grid[(n - 1) * n + i] = 1; grid[i * n] = 1; grid[i * n + n - 1] = 1 }
        for (y in 0 until n) { grid[y * n + 32] = 1; grid[y * n + 33] = 1 }   // closed wall, no gap
        return LevelMap(n, 16f, grid)
    }

    @Test fun dronesFlyOverWallsAndGroundEnemiesDoNot() {
        val world = World()
        EntityFactory.createPlayer(world, 800f, 500f)
        val movement = MovementSystem(1024f, 1024f).also { it.levelMap = wall() }
        val drone  = EntityFactory.createEnemy(world, EnemyType.FAST, 300f, 500f)
        val walker = EntityFactory.createEnemy(world, EnemyType.TANK, 300f, 600f)
        repeat(60 * 20) { movement.update(world, 1f / 60f, JoystickState()) }
        assertTrue("the drone did not cross", world.transforms[drone]!!.x > 560f)
        assertTrue("the walker went through the wall", world.transforms[walker]!!.x < 520f)
        assertFalse(EnemyType.TANK.isFlying)
    }
}
