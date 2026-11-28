package com.kotlinsurvivors

import com.kotlinsurvivors.engine.ecs.EntityFactory
import com.kotlinsurvivors.engine.ecs.World
import com.kotlinsurvivors.engine.ecs.components.CarComponent
import com.kotlinsurvivors.engine.ecs.components.CarState
import com.kotlinsurvivors.engine.ecs.systems.CarRules
import com.kotlinsurvivors.engine.ecs.systems.CarSystem
import com.kotlinsurvivors.engine.input.JoystickState
import com.kotlinsurvivors.engine.map.LevelCars
import com.kotlinsurvivors.features.game.domain.model.CarPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cars: the lock, the wheel, the health and what is left of a car at the end. */
class CarTest {

    private val idle = JoystickState()

    private fun scene(): Triple<World, CarSystem, Pair<Int, Int>> {
        val world = World(); val system = CarSystem()
        val pid = EntityFactory.createPlayer(world, 500f, 500f)
        val cid = EntityFactory.createCar(world, 560f, 500f, 0f, 0, 120, floatArrayOf(520f, 480f, 600f, 520f))
        return Triple(world, system, pid to cid)
    }

    @Test fun everyLevelListsWholeCars() {
        for (level in 1..2) assertEquals(0, LevelCars.of(level).size % LevelCars.STRIDE)
        assertTrue(LevelCars.of(1).isNotEmpty() && LevelCars.of(2).isNotEmpty())
        assertTrue(LevelCars.of(3).isEmpty())
    }

    @Test fun theLockNeedsTheButtonHeldTheWholeTime() {
        var p = 0f
        repeat(10) { p = CarRules.pickProgress(p, 0.1f, true) }
        assertTrue(p in 0.4f..0.5f)
        repeat(10) { p = CarRules.pickProgress(p, 0.1f, false) }
        assertEquals(0f, p, 0.0001f)
        repeat(30) { p = CarRules.pickProgress(p, 0.1f, true) }
        assertEquals(1f, p, 0.0001f)
    }

    @Test fun holdingTheButtonOpensTheCarAndPutsThePlayerAtTheWheel() {
        val (world, system, ids) = scene()
        val (pid, cid) = ids
        system.update(world, 0.016f, idle, true, true)
        assertEquals(CarPrompt.LOCKPICK, system.prompt)
        repeat(200) { system.update(world, 0.016f, idle, false, true) }
        assertEquals(cid, world.players[pid]!!.drivingCar)
        assertEquals(CarState.DRIVEN, world.cars[cid]!!.state)
        assertEquals(CarPrompt.EXIT, system.prompt)
    }

    @Test fun theButtonKnowsWhichCarItIsAbout() {
        val (world, system, ids) = scene()
        val (_, cid) = ids
        system.update(world, 0.016f, idle, false, false)
        assertEquals(world.cars[cid]!!.look, system.promptLook)
        system.update(world, 0.016f, idle, true, true)
        repeat(200) { system.update(world, 0.016f, idle, false, true) }
        assertEquals("at the wheel it is the car being driven", world.cars[cid]!!.look, system.promptLook)
        system.reset()
        assertEquals(-1, system.promptLook)
    }

    @Test fun aButtonAlreadyDownWhenTheCarComesIntoReachPicksNothing() {
        val (world, system, _) = scene()
        // the finger is down (or the engine was left thinking so) but no press happened next to this car
        repeat(300) { system.update(world, 0.016f, idle, false, true) }
        assertEquals(0f, system.progress, 0.0001f)
        assertEquals(CarState.LOCKED, world.cars.values.first().state)
    }

    @Test fun aNewPressAfterLettingGoStartsAgain() {
        val (world, system, _) = scene()
        system.update(world, 0.016f, idle, true, true)
        repeat(30) { system.update(world, 0.016f, idle, false, true) }
        system.update(world, 0.016f, idle, false, false)
        repeat(30) { system.update(world, 0.016f, idle, false, true) }          // held again without a new press
        assertEquals(0f, system.progress, 0.0001f)
        system.update(world, 0.016f, idle, true, true)
        repeat(10) { system.update(world, 0.016f, idle, false, true) }
        assertTrue(system.progress > 0f)
    }

    @Test fun lettingGoOfTheButtonLosesTheWork() {
        val (world, system, _) = scene()
        system.update(world, 0.016f, idle, true, true)
        repeat(60) { system.update(world, 0.016f, idle, false, true) }
        assertTrue(system.progress > 0f)
        repeat(120) { system.update(world, 0.016f, idle, false, false) }
        assertEquals(0f, system.progress, 0.0001f)
    }

    @Test fun aBlowToTheDriverInterruptsTheLock() {
        val (world, system, ids) = scene()
        system.update(world, 0.016f, idle, true, true)
        repeat(60) { system.update(world, 0.016f, idle, false, true) }
        world.healths[ids.first]!!.invincibleTimer = 0.5f
        system.update(world, 0.016f, idle, false, true)
        assertEquals(0f, system.progress, 0.0001f)
    }

    @Test fun theCarSpeedsUpAndTurnsNoFasterThanItsRate() {
        val car = CarComponent(look = 0, heading = 0f)
        CarRules.drive(car, true, 0f, 1f, 0.1f)                      // asks for 90 degrees
        assertTrue(car.heading <= CarRules.TURN_RATE * 0.1f + 0.0001f)
        repeat(300) { CarRules.drive(car, true, 1f, 0f, 0.05f) }
        assertTrue(car.speed > CarRules.MAX_SPEED * 0.9f)
        repeat(100) { CarRules.drive(car, false, 0f, 0f, 0.05f) }
        assertEquals(0f, car.speed, 0.0001f)
    }

    @Test fun aCarWithNoHealthLeftIsWreckedAndThrowsTheDriverOutUnhurt() {
        val (world, system, ids) = scene()
        val (pid, cid) = ids
        world.cars[cid]!!.state = CarState.OPEN
        system.update(world, 0.016f, idle, true, false)            // gets in
        assertEquals(cid, world.players[pid]!!.drivingCar)
        world.cars[cid]!!.hp = 0
        system.update(world, 0.016f, idle, false, false)
        assertEquals(CarState.WRECK, world.cars[cid]!!.state)
        assertEquals(-1, world.players[pid]!!.drivingCar)
        assertTrue(world.healths[pid]!!.invincibleTimer > 0f)
        assertEquals(world.healths[pid]!!.max, world.healths[pid]!!.current)
        repeat(5) { system.update(world, 0.016f, idle, true, true) }
        assertFalse("a wreck cannot be used", world.players[pid]!!.drivingCar >= 0)
    }

    @Test fun gettingOutLeavesTheCarOpenWhereItStopped() {
        val (world, system, ids) = scene()
        val (pid, cid) = ids
        world.cars[cid]!!.state = CarState.OPEN
        system.update(world, 0.016f, idle, true, false)
        system.update(world, 0.016f, idle, true, false)            // the same button gets out
        assertEquals(-1, world.players[pid]!!.drivingCar)
        assertEquals(CarState.OPEN, world.cars[cid]!!.state)
    }
}
