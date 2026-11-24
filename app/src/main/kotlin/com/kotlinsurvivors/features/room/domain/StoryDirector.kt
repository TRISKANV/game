package com.kotlinsurvivors.features.room.domain

import com.kotlinsurvivors.features.room.domain.Beat.Say
import com.kotlinsurvivors.features.room.domain.Beat.Silence
import com.kotlinsurvivors.features.room.domain.Speaker.ASSISTANT
import com.kotlinsurvivors.features.room.domain.Speaker.PROTAGONIST

/** Chapter 0 goes through these steps in order. */
enum class StoryPhase {
    INTRO,            // black screen with the ambience, then the room fades in
    WAKE,             // "¿Qué hora es...?"
    EXPLORE,          // free walking; the phone has not rung yet
    RINGING,          // the phone rings: go and answer it
    CALL1_INCOMING,   // incoming-call screen, waiting for the player to answer
    CALL1_TALK,
    CALL1_ENDING,     // hang-up animation
    FILE_WAIT,        // notification on the computer: go and open the file
    FILE_POPUP,       // a chat pop-up that comes up over the file; "no" goes on to the file
    INFECTION,        // "yes": the computer gets infected, a short animation
    RANSOM,           // the ransom screen with its two countdowns; closing it starts the alternative ending
    FILE_OPEN,        // the text of the file
    POST_FILE,
    GLITCH,           // the monitors flicker and a system message shows up
    POST_GLITCH,
    CALL2_INCOMING,   // the phone rings again and the protagonist answers
    CALL2_TALK,       // the long conversation: the evidence, the decision, the promise
    CALL2_ENDING,
    LOOK_AT_PC,       // a few seconds of silence, looking at the computer
    FINAL_MESSAGE,    // the contact's last notification
    FINAL,
    MISSION,          // "Sal de tu habitación": the door works now
    LEAVING,          // fading out to the next place
    DONE,

    // ── Alternative ending: the player said "yes" to the pop-up ──
    ALT_EXPLORE,        // the computer is dead; only the ransom note can be read again
    ALT_RINGING,        // the phone rings
    ALT_CALL_INCOMING,
    ALT_CALL_TALK,      // DEAUTH finds out the computer can't be used any more
    ALT_CALL_ENDING,
    ALT_FINAL,          // his last line
    ENDING              // black screen: "mañana lo arreglo"
}

/** One-shot sounds the screen plays when the story asks for them. */
enum class StoryCue { KEYS, ANSWER, HANGUP, NOTIFY, FILE_OPEN, GLITCH, DOOR, INFECT }

/**
 * The script of Chapter 0 «Sin conexión» as a small state machine.
 *
 * It knows nothing about Android or drawing: the screen feeds it time ([update]), the player's
 * actions ([interact], [answer], [tap], [closeFile], [skip]) and reads back what to show
 * (the properties) and which sounds to play ([drainCues]). There are no enemies in this level.
 */
class StoryDirector(
    private val textOf: (String) -> String = { it },
    private val exploreSeconds: Float = EXPLORE_SECONDS
) {
    var phase = StoryPhase.INTRO
        private set

    /** Dialogue that takes the screen (wake-up, thoughts after the file, the two calls). */
    var dialogue: DialogueRunner? = null
        private set

    /** Optional remark about an object; it never stops the player from walking. */
    var bubbleKey: String? = null
        private set

    /** Seconds since the mission appeared; drives the "MISIÓN ACTUALIZADA" banner. */
    var missionClock = 0f
        private set

    /** Seconds since the notification arrived. */
    var notificationClock = 0f
        private set

    private var clock = 0f
    private var bubbleClock = 0f
    private var keysPlayed = 0
    private val cues = ArrayDeque<StoryCue>()

    // ── What to show ─────────────────────────────────────────────────────
    /** 1 = fully black. The intro fades the room in; leaving fades it out. */
    val blackAlpha: Float
        get() = when (phase) {
            StoryPhase.INTRO   -> if (clock < BLACK_SECONDS) 1f else (1f - (clock - BLACK_SECONDS) / FADE_IN_SECONDS).coerceIn(0f, 1f)
            StoryPhase.LEAVING -> (clock / LEAVE_SECONDS).coerceIn(0f, 1f)
            StoryPhase.DONE    -> 1f
            StoryPhase.ENDING  -> (clock / ENDING_FADE_OUT_SECONDS).coerceIn(0f, 1f)
            else               -> 0f
        }

    val phoneRinging: Boolean
        get() = phase == StoryPhase.RINGING || phase == StoryPhase.ALT_RINGING || isCallIncoming

    val isCallIncoming: Boolean
        get() = phase == StoryPhase.CALL1_INCOMING || phase == StoryPhase.CALL2_INCOMING || phase == StoryPhase.ALT_CALL_INCOMING
    val isCallTalking: Boolean
        get() = phase == StoryPhase.CALL1_TALK || phase == StoryPhase.CALL2_TALK || phase == StoryPhase.ALT_CALL_TALK
    val isCallEnding: Boolean
        get() = phase == StoryPhase.CALL1_ENDING || phase == StoryPhase.CALL2_ENDING || phase == StoryPhase.ALT_CALL_ENDING

    /** 1 normally; lower while somebody is talking on the phone, so the voices come first; the ending lets the rain fade. */
    val ambienceVolume: Float
        get() = when {
            isCallTalking -> CALL_AMBIENCE_VOLUME
            phase == StoryPhase.ENDING -> ENDING_AMBIENCE_VOLUME
            else -> 1f
        }

    // ── The alternative ending ───────────────────────────────────────────
    val isEnding: Boolean get() = phase == StoryPhase.ENDING

    /** How many of the three closing lines are on the screen, counting the one fading in. */
    val endingLinesShown: Int
        get() = if (!isEnding) 0 else ((clock - ENDING_FIRST_LINE_AT) / ENDING_LINE_EVERY + 1f).toInt().coerceIn(0, ENDING_LINES)

    /** 0..1 opacity of closing line [index] (0-based): it fades in when it appears. */
    fun endingLineAlpha(index: Int): Float =
        if (!isEnding) 0f else ((clock - ENDING_FIRST_LINE_AT - index * ENDING_LINE_EVERY) / ENDING_FADE_SECONDS).coerceIn(0f, 1f)

    private val endingTitleAt get() = ENDING_FIRST_LINE_AT + ENDING_LINES * ENDING_LINE_EVERY + 0.6f

    /** 0..1 opacity of the "FINAL ALTERNATIVO" card, which comes after the last line. */
    val endingTitleAlpha: Float get() = if (!isEnding) 0f else ((clock - endingTitleAt) / ENDING_FADE_SECONDS).coerceIn(0f, 1f)

    /** The way back to the menu shows up once the card has been read. */
    val endingMenuVisible: Boolean get() = isEnding && clock >= endingTitleAt + ENDING_MENU_DELAY

    /** The USB with the evidence, once the protagonist has mentioned it: the key item of the story. */
    var usbRegistered = false
        private set
    val isCallScreen: Boolean get() = isCallIncoming || isCallTalking || isCallEnding

    /** The notification banner is up for a few seconds, the monitor keeps pulsing until the file is opened. */
    val showNotification: Boolean get() = phase == StoryPhase.FILE_WAIT && notificationClock < NOTIFICATION_SECONDS
    val monitorPulsing: Boolean get() = phase == StoryPhase.FILE_WAIT

    val chatPopupOpen: Boolean get() = phase == StoryPhase.FILE_POPUP
    val fileOpen: Boolean get() = phase == StoryPhase.FILE_OPEN

    // ── The infection: animation, ransom screen and the clocks that keep running afterwards ──
    /** True from the moment the player says "yes" to the pop-up. */
    var infected = false
        private set

    /** Seconds the infection has been going, the base of both countdowns. */
    var infectedSeconds = 0f
        private set

    /** The ransom screen is up on the computer, to read it again later when [reviewing]. */
    private var reviewing = false

    val infecting: Boolean get() = phase == StoryPhase.INFECTION
    val infectionProgress: Float get() = if (infecting) (clock / INFECTION_SECONDS).coerceIn(0f, 1f) else if (infected) 1f else 0f
    val showRansom: Boolean get() = phase == StoryPhase.RANSOM || reviewing

    /** Seconds left before the payment doubles / before the files are lost. They only ever go down. */
    val paymentDoublesIn: Long get() = Countdown.PAYMENT_DOUBLES_SECONDS - infectedSeconds.toLong()
    val filesLostIn: Long get() = Countdown.FILES_LOST_SECONDS - infectedSeconds.toLong()
    val glitching: Boolean get() = phase == StoryPhase.GLITCH

    /**
     * The glitch goes in three beats so the monitors and the message agree: static, then the screens
     * settle and the message is readable, then static again. The screen draws whichever applies.
     */
    val glitchMessageVisible: Boolean get() = glitching && clock >= GLITCH_STATIC_SECONDS && clock < GLITCH_SECONDS - GLITCH_STATIC_SECONDS
    val glitchStatic: Boolean get() = glitching && !glitchMessageVisible

    /** The last notification of the chapter, shown until the player taps or it times out. */
    val showAssistantMessage: Boolean get() = phase == StoryPhase.FINAL_MESSAGE

    val missionVisible: Boolean get() = phase == StoryPhase.MISSION || phase == StoryPhase.LEAVING
    val showMissionBanner: Boolean get() = missionVisible && missionClock < BANNER_SECONDS

    val isDone: Boolean get() = phase == StoryPhase.DONE

    /** Whether the joystick and the use button work. */
    val canWalk: Boolean
        get() = !reviewing && (phase == StoryPhase.EXPLORE || phase == StoryPhase.RINGING ||
                phase == StoryPhase.FILE_WAIT || phase == StoryPhase.MISSION ||
                phase == StoryPhase.ALT_EXPLORE || phase == StoryPhase.ALT_RINGING)

    /** The objects the player may use right now. */
    val usable: Set<Interactable>
        get() = if (reviewing) emptySet() else when (phase) {
            StoryPhase.EXPLORE   -> OPTIONAL
            StoryPhase.RINGING   -> OPTIONAL + Interactable.PHONE
            StoryPhase.FILE_WAIT -> setOf(Interactable.COMPUTER, Interactable.WINDOW)   // the desk remark would get in the way
            StoryPhase.MISSION   -> OPTIONAL + Interactable.DOOR
            // the infected computer only shows the ransom note; the desk remark is gone with the file
            StoryPhase.ALT_EXPLORE -> setOf(Interactable.COMPUTER, Interactable.WINDOW)
            StoryPhase.ALT_RINGING -> setOf(Interactable.COMPUTER, Interactable.WINDOW, Interactable.PHONE)
            else                 -> emptySet()
        }

    fun drainCues(): List<StoryCue> = cues.toList().also { cues.clear() }

    // ── Time ─────────────────────────────────────────────────────────────
    fun update(dt: Float) {
        clock += dt
        if (bubbleKey != null) {
            bubbleClock += dt
            if (bubbleClock >= BUBBLE_SECONDS) bubbleKey = null
        }
        dialogue?.update(dt)
        if (infected) infectedSeconds += dt

        when (phase) {
            StoryPhase.INTRO -> {
                if (keysPlayed == 0 && clock >= 0.6f) { keysPlayed = 1; cues += StoryCue.KEYS }
                if (keysPlayed == 1 && clock >= 1.9f) { keysPlayed = 2; cues += StoryCue.KEYS }
                if (clock >= BLACK_SECONDS + FADE_IN_SECONDS) {
                    startDialogue(StoryPhase.WAKE, listOf(
                        Say(PROTAGONIST, "story_wake_1"), Silence(700), Say(PROTAGONIST, "story_wake_2")))
                }
            }
            StoryPhase.WAKE         -> if (dialogue.finished()) enter(StoryPhase.EXPLORE)
            StoryPhase.EXPLORE      -> if (clock >= exploreSeconds) enter(StoryPhase.RINGING)
            StoryPhase.CALL1_TALK   -> if (dialogue.finished()) { cues += StoryCue.HANGUP; dialogue = null; enter(StoryPhase.CALL1_ENDING) }
            StoryPhase.CALL1_ENDING -> if (clock >= HANGUP_SECONDS) { cues += StoryCue.NOTIFY; notificationClock = 0f; enter(StoryPhase.FILE_WAIT) }
            StoryPhase.FILE_WAIT    -> notificationClock += dt
            StoryPhase.INFECTION    -> if (clock >= INFECTION_SECONDS) { cues += StoryCue.NOTIFY; enter(StoryPhase.RANSOM) }
            StoryPhase.ALT_EXPLORE  -> if (clock >= ALT_EXPLORE_SECONDS) enter(StoryPhase.ALT_RINGING)
            StoryPhase.ALT_CALL_TALK -> if (dialogue.finished()) { cues += StoryCue.HANGUP; dialogue = null; enter(StoryPhase.ALT_CALL_ENDING) }
            StoryPhase.ALT_CALL_ENDING -> if (clock >= HANGUP_SECONDS) startDialogue(StoryPhase.ALT_FINAL, listOf(Say(PROTAGONIST, "story_alt_final")))
            StoryPhase.ALT_FINAL    -> if (dialogue.finished()) enter(StoryPhase.ENDING)
            StoryPhase.POST_FILE    -> if (dialogue.finished()) { cues += StoryCue.GLITCH; enter(StoryPhase.GLITCH) }
            StoryPhase.GLITCH       -> if (clock >= GLITCH_SECONDS) startDialogue(StoryPhase.POST_GLITCH, listOf(Say(PROTAGONIST, "story_post_3")))
            StoryPhase.POST_GLITCH  -> if (dialogue.finished()) enter(StoryPhase.CALL2_INCOMING)
            StoryPhase.CALL2_TALK   -> {
                if (!usbRegistered && dialogue?.currentKey == USB_LINE_KEY) { usbRegistered = true; cues += StoryCue.NOTIFY }
                if (dialogue.finished()) { cues += StoryCue.HANGUP; dialogue = null; enter(StoryPhase.CALL2_ENDING) }
            }
            StoryPhase.CALL2_ENDING -> if (clock >= HANGUP_SECONDS) enter(StoryPhase.LOOK_AT_PC)
            StoryPhase.LOOK_AT_PC   -> if (clock >= LOOK_SECONDS) { cues += StoryCue.NOTIFY; enter(StoryPhase.FINAL_MESSAGE) }
            StoryPhase.FINAL_MESSAGE -> if (clock >= MESSAGE_SECONDS) startFinalDialogue()
            StoryPhase.FINAL        -> if (dialogue.finished()) startMission()
            StoryPhase.MISSION      -> missionClock += dt
            StoryPhase.LEAVING      -> { missionClock += dt; if (clock >= LEAVE_SECONDS) enter(StoryPhase.DONE) }
            else -> Unit
        }
    }

    // ── Player actions ───────────────────────────────────────────────────
    /** The player used [target]. Returns false if it is not available now. */
    fun interact(target: Interactable): Boolean {
        if (target !in usable) return false
        when {
            target == Interactable.PHONE && phase == StoryPhase.ALT_RINGING -> enter(StoryPhase.ALT_CALL_INCOMING)
            target == Interactable.PHONE -> enter(StoryPhase.CALL1_INCOMING)
            target == Interactable.COMPUTER && phase == StoryPhase.FILE_WAIT -> {
                cues += StoryCue.NOTIFY
                enter(StoryPhase.FILE_POPUP)
            }
            target == Interactable.COMPUTER && infected -> { cues += StoryCue.NOTIFY; reviewing = true }   // read the ransom note again
            target == Interactable.DOOR -> { cues += StoryCue.DOOR; enter(StoryPhase.LEAVING) }
            else -> {
                bubbleKey = when (target) {
                    Interactable.COMPUTER -> "story_obj_computer"
                    Interactable.DESK     -> "story_obj_desk"
                    else                  -> "story_obj_window"
                }
                bubbleClock = 0f
            }
        }
        return true
    }

    /** The player pressed "answer" on the incoming-call screen. */
    fun answer() {
        when (phase) {
            StoryPhase.CALL1_INCOMING -> { cues += StoryCue.ANSWER; startDialogue(StoryPhase.CALL1_TALK, CALL1) }
            StoryPhase.CALL2_INCOMING -> { cues += StoryCue.ANSWER; startDialogue(StoryPhase.CALL2_TALK, CALL2) }
            StoryPhase.ALT_CALL_INCOMING -> { cues += StoryCue.ANSWER; startDialogue(StoryPhase.ALT_CALL_TALK, ALT_CALL) }
            else -> Unit
        }
    }

    /** A tap on the dialogue box, or on the assistant's last notification. */
    fun tap() {
        if (phase == StoryPhase.FINAL_MESSAGE && clock >= MESSAGE_MIN_SECONDS) startFinalDialogue()
        else dialogue?.tap()
    }

    /** "No" on the chat pop-up: it goes away and the file is there underneath. */
    fun chatNo() {
        if (phase != StoryPhase.FILE_POPUP) return
        cues += StoryCue.FILE_OPEN
        enter(StoryPhase.FILE_OPEN)
    }

    /** "Yes" on the chat pop-up: the computer gets infected and the ransom clocks start. */
    fun chatYes() {
        if (phase != StoryPhase.FILE_POPUP) return
        infected = true
        infectedSeconds = 0f
        cues += StoryCue.INFECT
        enter(StoryPhase.INFECTION)
    }

    /**
     * The player closes the ransom screen. The first time, the story takes its alternative branch:
     * the computer is lost, he can only wait for the phone. Later it just goes back to the room.
     */
    fun closeRansom() {
        when {
            reviewing -> reviewing = false
            phase == StoryPhase.RANSOM -> enter(StoryPhase.ALT_EXPLORE)
        }
    }

    /** The player closed the file. */
    fun closeFile() {
        if (phase != StoryPhase.FILE_OPEN) return
        startDialogue(StoryPhase.POST_FILE, listOf(
            Say(PROTAGONIST, "story_post_1"), Silence(600), Say(PROTAGONIST, "story_post_2")))
    }

    /** Jumps straight to the mission, for players who have seen the introduction already. */
    fun skip() {
        if (phase == StoryPhase.MISSION || phase == StoryPhase.LEAVING || phase == StoryPhase.DONE) return
        if (infected) return   // there is no way round the alternative ending
        dialogue = null
        bubbleKey = null
        startMission()
    }

    // ── Internals ────────────────────────────────────────────────────────
    private fun DialogueRunner?.finished() = this == null || isFinished

    private fun enter(next: StoryPhase) {
        phase = next
        clock = 0f
        // A dialogue that has ended must not linger: the screen would keep catching the player's touches.
        if (dialogue?.isFinished == true) dialogue = null
    }

    private fun startDialogue(next: StoryPhase, beats: List<Beat>) {
        dialogue = DialogueRunner(beats, textOf)
        enter(next)
    }

    private fun startFinalDialogue() = startDialogue(StoryPhase.FINAL, listOf(Say(PROTAGONIST, "story_final_1")))

    private fun startMission() {
        dialogue = null
        missionClock = 0f
        cues += StoryCue.NOTIFY
        enter(StoryPhase.MISSION)
    }

    companion object {
        const val EXPLORE_SECONDS = 18f
        const val INFECTION_SECONDS = 5f
        const val ALT_EXPLORE_SECONDS = 6f
        const val ENDING_FADE_OUT_SECONDS = 2.5f
        const val ENDING_FIRST_LINE_AT = 3.2f
        const val ENDING_LINE_EVERY = 3.4f
        const val ENDING_FADE_SECONDS = 1.4f
        const val ENDING_MENU_DELAY = 2.2f
        const val ENDING_LINES = 3
        const val ENDING_AMBIENCE_VOLUME = 0.3f
        const val BLACK_SECONDS = 2.8f
        const val FADE_IN_SECONDS = 1.6f
        const val HANGUP_SECONDS = 1.3f
        const val CALL_AMBIENCE_VOLUME = 0.4f
        const val LOOK_SECONDS = 3.5f
        const val USB_LINE_KEY = "story_call2_11"
        const val GLITCH_SECONDS = 3.2f
        const val GLITCH_STATIC_SECONDS = 0.8f
        const val MESSAGE_SECONDS = 7f
        const val MESSAGE_MIN_SECONDS = 1.2f
        const val LEAVE_SECONDS = 1.2f
        const val NOTIFICATION_SECONDS = 4f
        const val BANNER_SECONDS = 3.5f
        const val BUBBLE_SECONDS = 3.5f

        private val OPTIONAL = setOf(Interactable.COMPUTER, Interactable.DESK, Interactable.WINDOW)

        /**
         * Turns a compact script into beats: "A" and "P" are the next line of the assistant or the
         * protagonist (keys story_<prefix>_01, 02...), "S900" is a silence of 900 ms.
         */
        private fun script(prefix: String, spec: String): List<Beat> {
            var n = 0
            return spec.split(" ").map { token ->
                when (token[0]) {
                    'S'  -> Silence(token.substring(1).toLong())
                    'A'  -> Say(ASSISTANT, "story_${prefix}_%02d".format(++n))
                    else -> Say(PROTAGONIST, "story_${prefix}_%02d".format(++n))
                }
            }
        }

        /** First call: the assistant asks the protagonist to look at his computer. 9 lines. */
        val CALL1: List<Beat> = script("call1", "A P A P A P A P A")

        /**
         * Second call: 35 lines. He explains (without details) what he got into, says the evidence is
         * on a USB, that he will hand it to the police, and that he left a message for his family; she
         * promises to watch over him. The pauses mark the moments that weigh.
         */
        /** Alternative call: he tells her the computer is lost and she sends him to bed. 13 lines. */
        val ALT_CALL: List<Beat> = script("alt", "A P A P A P S1000 A P A P A P A")

        val CALL2: List<Beat> = script("call2",
            "A P A P A P A P S900 P A P A P A P S900 P A P A P A P S900 A P A P A S1200 A P A P A A P A")
    }
}
