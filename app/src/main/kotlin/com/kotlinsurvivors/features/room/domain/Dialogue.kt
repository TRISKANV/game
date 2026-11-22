package com.kotlinsurvivors.features.room.domain

/** Who is talking. The name shown in the dialogue box comes from this. */
enum class Speaker { PROTAGONIST, ASSISTANT }

/** One step of a conversation: a line somebody says, or a silent pause. */
sealed interface Beat {
    /** [key] is the name of the string resource holding the line, so every language shares the same script. */
    data class Say(val speaker: Speaker, val key: String) : Beat

    /** A narrative pause: nothing is shown and the dialogue moves on by itself after [ms]. */
    data class Silence(val ms: Long) : Beat
}

/**
 * Plays a list of [Beat]s the way story dialogue works: the text appears letter by letter,
 * a tap completes the line that is being typed and a second tap moves on to the next one,
 * and silences pass by themselves.
 *
 * No Android dependencies: [textOf] turns a line key into its text (the screen reads the string
 * resource, the tests use the key itself), which keeps the typewriter rules testable.
 */
class DialogueRunner(
    private val beats: List<Beat>,
    private val textOf: (String) -> String,
    private val charsPerSecond: Float = CHARS_PER_SECOND
) {
    private var index = 0
    private var clock = 0f

    val isFinished: Boolean get() = index >= beats.size

    private val current: Beat? get() = beats.getOrNull(index)

    /** The speaker of the line on screen, or null during a silence or once finished. */
    val speaker: Speaker? get() = (current as? Beat.Say)?.speaker

    val isSilence: Boolean get() = current is Beat.Silence

    /** The complete text of the current line ("" during a silence). */
    val fullText: String get() = (current as? Beat.Say)?.let { textOf(it.key) } ?: ""

    /** How many letters of the current line are revealed so far. */
    val visibleChars: Int get() = (clock * charsPerSecond).toInt().coerceAtMost(fullText.length)

    val visibleText: String get() = fullText.take(visibleChars)

    /** True when the whole current line is showing and a tap will move on. */
    val isLineComplete: Boolean get() = current is Beat.Say && visibleChars >= fullText.length

    fun update(dt: Float) {
        val beat = current ?: return
        clock += dt
        if (beat is Beat.Silence && clock * 1000f >= beat.ms) advance()
    }

    /** Completes the line being typed; if it is already complete, goes to the next beat. */
    fun tap() {
        if (current !is Beat.Say) return
        if (isLineComplete) advance() else clock = fullText.length / charsPerSecond
    }

    private fun advance() {
        index++
        clock = 0f
    }

    companion object {
        const val CHARS_PER_SECOND = 38f
    }
}
