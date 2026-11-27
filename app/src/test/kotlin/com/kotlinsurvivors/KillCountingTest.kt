package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.EnemyType
import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.systems.ExperienceSystem
import com.kotlinsurvivors.engine.ecs.systems.GameEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kills from weapons that do not fire projectiles (the whip, auras...) must reach the combo like any other. */
class KillCountingTest {

    private val world = World()
    private val xp    = ExperienceSystem()

    init { EntityFactory.createPlayer(world, 500f, 500f) }

    @Test fun aWhipKillIsReportedBeforeTheCombo() {
        val id = EntityFactory.createEnemy(world, EnemyType.BASIC, 600f, 500f)
        world.healths[id]!!.isDead = true            // what the whip does: marks the death and reports nothing
        val events = mutableListOf<GameEvent>()

        xp.reapUnreportedKills(world, events)

        assertEquals(1, events.count { it is GameEvent.EnemyKilled && it.entityId == id })
    }

    @Test fun reportingTwiceDoesNotCountTheSameDeathTwice() {
        val id = EntityFactory.createEnemy(world, EnemyType.BASIC, 600f, 500f)
        world.healths[id]!!.isDead = true
        val events = mutableListOf<GameEvent>()

        xp.reapUnreportedKills(world, events)
        xp.reapUnreportedKills(world, events)
        xp.processEvents(world, events)

        assertEquals(1, events.count { it is GameEvent.EnemyKilled })
    }

    @Test fun aKillAlreadyReportedByAProjectileIsNotAddedAgain() {
        val id = EntityFactory.createEnemy(world, EnemyType.BASIC, 600f, 500f)
        world.healths[id]!!.isDead = true
        val events = mutableListOf<GameEvent>(GameEvent.EnemyKilled(id, EnemyType.BASIC, 5, 0f))

        xp.reapUnreportedKills(world, events)

        assertEquals(1, events.count { it is GameEvent.EnemyKilled })
    }
}
