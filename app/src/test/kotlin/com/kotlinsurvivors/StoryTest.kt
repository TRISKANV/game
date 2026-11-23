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
        // the chat pop-up comes first and covers the file; "yes" does nothing, "no" goes on
        assertTrue(s.chatPopupOpen); assertFalse(s.fileOpen); assertFalse(s.canWalk)
        s.closeFile()
        assertTrue(s.chatPopupOpen)
        s.chatYes()
        assertTrue(s.chatPopupOpen); assertFalse(s.fileOpen)
        s.chatNo()
        assertFalse(s.chatPopupOpen)
        assertTrue(s.fileOpen); assertFalse(s.canWalk)
        s.closeFile()
        assertEquals(StoryPhase.POST_FILE, s.phase)
        s.talkThrough()
        assertTrue(s.glitching)
        // static first, then the message with settled screens, then static again
        assertTrue(s.glitchStatic); assertFalse(s.glitchMessageVisible)
        s.run(StoryDirector.GLITCH_STATIC_SECONDS + 0.2f)
        assertTrue(s.glitchMessageVisible); assertFalse(s.glitchStatic)
        s.run(StoryDirector.GLITCH_SECONDS - 2 * StoryDirector.GLITCH_STATIC_SECONDS)
        assertTrue(s.glitchStatic)
        s.run(1f)
        assertEquals(StoryPhase.POST_GLITCH, s.phase)
        s.talkThrough()

        // second call: it rings by itself, the ambience steps back while they talk, the USB is registered
        assertEquals(StoryPhase.CALL2_INCOMING, s.phase)
        assertTrue(s.phoneRinging); assertFalse(s.canWalk)
        assertFalse(s.usbRegistered)
        assertEquals(1f, s.ambienceVolume, 0f)
        s.answer()
        assertEquals(StoryPhase.CALL2_TALK, s.phase)
        assertTrue(s.ambienceVolume < 1f)
        s.talkThrough()
        assertTrue(s.usbRegistered)
        assertEquals(StoryPhase.CALL2_ENDING, s.phase)
        s.run(2f)
        // a few quiet seconds looking at the computer, with no control
        assertEquals(StoryPhase.LOOK_AT_PC, s.phase)
        assertFalse(s.canWalk)
        s.run(StoryDirector.LOOK_SECONDS + 0.2f)

        // the contact's last notification: it ignores a tap at once, then a tap moves on
        assertEquals(StoryPhase.FINAL_MESSAGE, s.phase)
        assertTrue(s.showAssistantMessage)
        s.tap()
        assertEquals(StoryPhase.FINAL_MESSAGE, s.phase)
        s.run(StoryDirector.MESSAGE_MIN_SECONDS + 0.1f)
        s.tap()
        assertEquals(StoryPhase.FINAL, s.phase)
        assertFalse(s.showAssistantMessage)
        s.talkThrough()
        assertEquals(StoryPhase.MISSION, s.phase)
        assertTrue(s.missionVisible); assertTrue(s.showMissionBanner); assertTrue(s.canWalk)
        assertTrue(Interactable.DOOR in s.usable)

        val cues = s.drainCues()
        // notification of the file, the chat pop-up, the USB, the contact's message and the mission
        assertEquals(5, cues.count { it == StoryCue.NOTIFY })
        assertEquals(1, cues.count { it == StoryCue.GLITCH })
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

    @Test fun theAssistantMessageMovesOnByItselfAfterAWhile() {
        val s = director().toRinging()
        s.interact(Interactable.PHONE); s.answer(); s.talkThrough(); s.run(2f)
        s.interact(Interactable.COMPUTER); s.chatNo(); s.closeFile(); s.talkThrough(); s.run(4.5f); s.talkThrough()
        s.answer(); s.talkThrough(); s.run(2f); s.run(StoryDirector.LOOK_SECONDS + 0.2f)
        assertEquals(StoryPhase.FINAL_MESSAGE, s.phase)
        s.run(StoryDirector.MESSAGE_SECONDS + 0.1f)
        assertEquals(StoryPhase.FINAL, s.phase)
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
                   "story_post_1", "story_post_2", "story_post_3", "story_final_msg", "story_final_1", "story_usb_label", "story_usb_name", "story_notif_body", "story_file_text",
                   "story_glitch", "story_contact", "story_chat_name", "story_chat_request", "story_chat_question", "story_chat_yes", "story_chat_no", "story_mission_exit")
        for (lang in listOf("values", "values-es")) {
            val xml = File("src/main/res/$lang/strings.xml").readText()
            for (k in keys) assertTrue("$lang is missing $k", xml.contains("name=\"$k\""))
        }
    }

    @Test fun theCallsFollowTheScriptOrder() {
        assertEquals(9, StoryDirector.CALL1.size)
        assertEquals(Speaker.ASSISTANT, (StoryDirector.CALL1.first() as Beat.Say).speaker)
        assertEquals("story_call1_09", (StoryDirector.CALL1.last() as Beat.Say).key)
        // the first call alternates strictly; every key is used once
        val first = StoryDirector.CALL1.filterIsInstance<Beat.Say>()
        for (pair in first.zipWithNext()) assertTrue(pair.first.speaker != pair.second.speaker)

        // the second one: 35 lines and 4 silences (after the weight of "no one must know", the decision,
        // the promise to survive, and her taking a breath)
        assertEquals(39, StoryDirector.CALL2.size)
        val says = StoryDirector.CALL2.filterIsInstance<Beat.Say>()
        assertEquals(35, says.size)
        assertEquals(4, StoryDirector.CALL2.count { it is Beat.Silence })
        assertEquals(says.size, says.map { it.key }.toSet().size)
        assertEquals("story_call2_01", says.first().key)
        assertEquals("story_call2_35", says.last().key)
        assertEquals(Speaker.ASSISTANT, says.last().speaker)
        // the USB line is the protagonist's, and it is the one that registers the key item
        assertEquals(Speaker.PROTAGONIST, says.first { it.key == StoryDirector.USB_LINE_KEY }.speaker)
        val usbText = File("src/main/res/values-es/strings.xml").readText()
        assertTrue(usbText.substringAfter("name=\"${StoryDirector.USB_LINE_KEY}\">").substringBefore("<").contains("USB"))
    }

    @Test fun thereIsNoPoliceStationYetSoTheDoorLeadsOnToTheGame() {
        // The mission text says where to go; the door itself still hands over to the next screen.
        val s = director(); s.skip()
        assertTrue(s.interact(Interactable.DOOR))
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
