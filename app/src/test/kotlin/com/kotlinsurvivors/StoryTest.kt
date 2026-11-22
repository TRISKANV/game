package com.kotlinsurvivors

import com.kotlinsurvivors.features.room.domain.Beat
import com.kotlinsurvivors.features.room.domain.DialogueRunner
import com.kotlinsurvivors.features.room.domain.Interactable
import com.kotlinsurvivors.features.room.domain.RoomInteractables
import com.kotlinsurvivors.features.room.domain.RoomWorld
import com.kotlinsurvivors.features.room.domain.Speaker
import com.kotlinsurvivors.features.room.domain.StoryCue
import com.kotlinsurvivors.features.room.domain.StoryDirector
import com.kotlinsurvivors.features.room.domain.StoryPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialogueRunnerTest {

    private fun runner(vararg beats: Beat) = DialogueRunner(beats.toList(), { "hello world" }, charsPerSecond = 10f)

    @Test fun textAppearsLetterByLetter() {
        val r = runner(Beat.Say(Speaker.ASSISTANT, "k"))
        assertEquals("", r.visibleText)
        r.update(0.5f)
        assertEquals("hello", r.visibleText)
        assertFalse(r.isLineComplete)
        r.update(5f)
        assertEquals("hello world", r.visibleText)
        assertTrue(r.isLineComplete)
    }

    @Test fun firstTapCompletesTheLineSecondTapAdvances() {
        val r = runner(Beat.Say(Speaker.ASSISTANT, "a"), Beat.Say(Speaker.PROTAGONIST, "b"))
        r.update(0.2f)
        r.tap()
        assertTrue(r.isLineComplete)
        assertEquals(Speaker.ASSISTANT, r.speaker)
        r.tap()
        assertEquals(Speaker.PROTAGONIST, r.speaker)
        assertEquals("", r.visibleText)
        r.tap(); r.tap()
        assertTrue(r.isFinished)
    }

    @Test fun silencesPassByThemselvesAndIgnoreTaps() {
        val r = runner(Beat.Silence(500), Beat.Say(Speaker.ASSISTANT, "a"))
        assertTrue(r.isSilence); assertNull(r.speaker)
        r.tap()
        assertTrue(r.isSilence)
        r.update(0.4f); assertTrue(r.isSilence)
        r.update(0.2f)
        assertEquals(Speaker.ASSISTANT, r.speaker)
    }
}

class StoryDirectorTest {

    private val dt = 1f / 30f

    private fun StoryDirector.run(seconds: Float) = repeat((seconds / dt).toInt()) { update(dt) }

    /** Taps through whatever dialogue is on screen until [phase] changes or it is over. */
    private fun StoryDirector.talkThrough() {
        var guard = 0
        val start = phase
        while (phase == start && guard++ < 5000) { update(dt); tap() }
    }

    private fun director() = StoryDirector(textOf = { it }, exploreSeconds = 5f)

    private fun StoryDirector.toRinging(): StoryDirector {
        run(10f)                       // intro
        talkThrough()                  // wake-up dialogue
        assertEquals(StoryPhase.EXPLORE, phase)
        run(5.5f)
        assertEquals(StoryPhase.RINGING, phase)
        return this
    }

    @Test fun startsBlackAndFadesTheRoomIn() {
        val s = director()
        assertEquals(1f, s.blackAlpha, 0f)
        assertFalse(s.canWalk)
        s.run(StoryDirector.BLACK_SECONDS + StoryDirector.FADE_IN_SECONDS / 2f)
        assertTrue(s.blackAlpha in 0.2f..0.8f)
        s.run(3f)
        assertEquals(0f, s.blackAlpha, 0f)
        assertEquals(StoryPhase.WAKE, s.phase)
        assertTrue(s.dialogue != null)
    }

    @Test fun introPlaysTheKeyboardSound() {
        val s = director(); s.run(3f)
        assertEquals(2, s.drainCues().count { it == StoryCue.KEYS })
    }

    @Test fun playerWalksOnlyAfterTheWakeUpLines() {
        val s = director(); s.run(10f)
        assertEquals(StoryPhase.WAKE, s.phase); assertFalse(s.canWalk)
        s.talkThrough()
        assertEquals(StoryPhase.EXPLORE, s.phase); assertTrue(s.canWalk)
    }

    @Test fun noFinishedDialogueIsLeftOnScreenWhenTheGameIsFree() {
        val s = director(); s.run(10f); s.talkThrough()
        assertTrue(s.canWalk)
        assertNull(s.dialogue)
    }

    @Test fun optionalRemarksNeverStopTheGame() {
        val s = director(); s.run(10f); s.talkThrough()
        assertTrue(s.interact(Interactable.COMPUTER))
        assertEquals("story_obj_computer", s.bubbleKey)
        assertTrue(s.canWalk)
        assertEquals(StoryPhase.EXPLORE, s.phase)
        s.run(StoryDirector.BUBBLE_SECONDS + 0.2f)
        assertNull(s.bubbleKey)
        s.interact(Interactable.WINDOW); assertEquals("story_obj_window", s.bubbleKey)
        s.interact(Interactable.DESK);   assertEquals("story_obj_desk", s.bubbleKey)
    }

    @Test fun phoneCannotBeUsedUntilItRings() {
        val s = director(); s.run(10f); s.talkThrough()
        assertFalse(s.interact(Interactable.PHONE))
        assertFalse(s.interact(Interactable.DOOR))
        s.run(5.5f)
        assertTrue(s.phoneRinging)
        assertTrue(s.interact(Interactable.PHONE))
        assertEquals(StoryPhase.CALL1_INCOMING, s.phase)
        assertTrue(s.isCallIncoming); assertFalse(s.canWalk)
    }

    @Test fun theWholeChapterRunsInOrder() {
        val s = director().toRinging()

        s.interact(Interactable.PHONE)
        s.answer()
        assertEquals(StoryPhase.CALL1_TALK, s.phase)
        assertEquals(Speaker.ASSISTANT, s.dialogue!!.speaker)
        s.talkThrough()
        assertEquals(StoryPhase.CALL1_ENDING, s.phase)
        assertTrue(s.isCallEnding)
        s.run(2f)
        assertEquals(StoryPhase.FILE_WAIT, s.phase)
        assertTrue(s.showNotification); assertTrue(s.monitorPulsing); assertTrue(s.canWalk)
        assertFalse(s.phoneRinging)
        s.run(5f); assertFalse(s.showNotification); assertTrue(s.monitorPulsing)

        // the computer now opens the file instead of a remark
        assertTrue(s.interact(Interactable.COMPUTER))
        assertTrue(s.fileOpen); assertFalse(s.canWalk)
        s.closeFile()
        assertEquals(StoryPhase.POST_FILE, s.phase)
        s.talkThrough()
        assertTrue(s.glitching)
        s.run(3f)
        assertEquals(StoryPhase.POST_GLITCH, s.phase)
        s.talkThrough()
        assertEquals(StoryPhase.CALL2_INCOMING, s.phase)
        assertTrue(s.phoneRinging)

        s.answer()
        s.talkThrough()
        s.run(2f)
        assertEquals(StoryPhase.FINAL, s.phase)
        s.talkThrough()
        assertEquals(StoryPhase.MISSION, s.phase)
        assertTrue(s.missionVisible); assertTrue(s.showMissionBanner); assertTrue(s.canWalk)
        assertTrue(Interactable.DOOR in s.usable)

        val cues = s.drainCues()
        assertEquals(listOf(StoryCue.NOTIFY, StoryCue.GLITCH, StoryCue.NOTIFY).sorted(), cues.filter { it == StoryCue.NOTIFY || it == StoryCue.GLITCH }.sorted())
        assertEquals(2, cues.count { it == StoryCue.HANGUP })
        assertEquals(2, cues.count { it == StoryCue.ANSWER })

        s.run(5f); assertFalse(s.showMissionBanner); assertTrue(s.missionVisible)
        assertTrue(s.interact(Interactable.DOOR))
        assertEquals(StoryPhase.LEAVING, s.phase)
        s.run(2f)
        assertTrue(s.isDone)
        assertEquals(1f, s.blackAlpha, 0f)
    }

    @Test fun theFileCanBeOpenedFromEitherSideOfTheChair() {
        val s = director().toRinging()
        s.interact(Interactable.PHONE); s.answer(); s.talkThrough(); s.run(2f)
        assertEquals(StoryPhase.FILE_WAIT, s.phase)
        // left of the chair, where the desk remark used to win
        assertEquals(Interactable.COMPUTER, RoomInteractables.near(300f, 155f, s.usable))
        // right of the chair
        assertEquals(Interactable.COMPUTER, RoomInteractables.near(370f, 155f, s.usable))
        assertTrue(Interactable.DESK !in s.usable)
    }

    @Test fun theDoorIsLockedUntilTheMission() {
        val s = director().toRinging()
        assertFalse(Interactable.DOOR in s.usable)
        s.interact(Interactable.PHONE); s.answer(); s.talkThrough(); s.run(2f)
        assertFalse(Interactable.DOOR in s.usable)
    }

    @Test fun answeringIsIgnoredWhenNoCallIsRinging() {
        val s = director(); s.run(10f); s.talkThrough()
        s.answer()
        assertEquals(StoryPhase.EXPLORE, s.phase)
    }

    @Test fun skipGoesStraightToTheMission() {
        val s = director()
        s.skip()
        assertEquals(StoryPhase.MISSION, s.phase)
        assertEquals(0f, s.blackAlpha, 0f)
        assertTrue(s.canWalk); assertNull(s.dialogue)
    }

    @Test fun everyLineOfTheScriptIsAKnownStringInBothLanguages() {
        val keys = (StoryDirector.CALL1 + StoryDirector.CALL2).filterIsInstance<Beat.Say>().map { it.key } +
            listOf("story_wake_1", "story_wake_2", "story_obj_computer", "story_obj_desk", "story_obj_window",
                   "story_post_1", "story_post_2", "story_post_3", "story_final", "story_notif_body", "story_file_text",
                   "story_glitch", "story_contact", "story_mission_exit")
        for (lang in listOf("values", "values-es")) {
            val xml = File("src/main/res/$lang/strings.xml").readText()
            for (k in keys) assertTrue("$lang is missing $k", xml.contains("name=\"$k\""))
        }
    }

    @Test fun theCallsFollowTheScriptOrder() {
        assertEquals(15, StoryDirector.CALL1.size)
        assertEquals(Speaker.ASSISTANT, (StoryDirector.CALL1.first() as Beat.Say).speaker)
        assertEquals(9, StoryDirector.CALL2.size)
        assertEquals("story_call2_09", (StoryDirector.CALL2.last() as Beat.Say).key)
    }
}

class RoomInteractablesTest {

    @Test fun everyObjectCanBeReachedOnFoot() {
        val world = RoomWorld()
        for ((target, zone) in RoomInteractables.zones) {
            var found = false
            var px = zone.x - RoomInteractables.REACH
            while (px <= zone.right + RoomInteractables.REACH && !found) {
                var py = zone.y - RoomInteractables.REACH
                while (py <= zone.bottom + RoomInteractables.REACH && !found) {
                    if (world.canStandAt(px, py) && RoomInteractables.near(px, py, setOf(target)) == target) found = true
                    py += 2f
                }
                px += 2f
            }
            assertTrue("$target cannot be reached", found)
        }
    }

    @Test fun theStartingSpotIsNextToNothing() {
        assertNull(RoomInteractables.near(RoomWorld().x, RoomWorld().y, Interactable.values().toSet()))
    }

    @Test fun onlyEnabledObjectsCount() {
        val d = RoomInteractables.zones.getValue(Interactable.DOOR)
        val x = d.x + d.w / 2f; val y = d.y + d.h / 2f
        assertEquals(Interactable.DOOR, RoomInteractables.near(x, y, setOf(Interactable.DOOR)))
        assertNull(RoomInteractables.near(x, y, setOf(Interactable.PHONE)))
        assertNotNull(RoomInteractables.near(x, y, Interactable.values().toSet()))
    }
}
