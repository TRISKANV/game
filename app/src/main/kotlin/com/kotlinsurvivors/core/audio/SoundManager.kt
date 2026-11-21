package com.kotlinsurvivors.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the effects of [Sound] through a [SoundPool]: short clips decoded once into memory
 * and mixed with very little delay, which is what a game needs.
 *
 * One instance for the whole app. The on/off switch from Settings is kept in
 * SharedPreferences so it is known as soon as the app starts.
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) : SoundEffects {

    private companion object {
        const val TAG         = "KS_Sound"
        const val PREFS       = "sound_prefs"
        const val KEY_ENABLED = "effects_enabled"
        const val MAX_STREAMS = 12
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    /** Whether sound effects are on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /** SoundPool id of every loaded sound; filled once the file has decoded. */
    private val ids       = HashMap<Sound, Int>()
    private val ready     = HashSet<Int>()
    private val lastPlay  = LongArray(Sound.values().size)

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(ready) { ready.add(sampleId) }
            else Log.w(TAG, "Sample $sampleId did not load (status $status)")
        }
        for (sound in Sound.values()) {
            try {
                context.assets.openFd("sfx/${sound.file}.ogg").use { fd ->
                    ids[sound] = pool.load(fd, 1)
                }
            } catch (e: Exception) {
                // A missing file must not take the game down: that sound is simply silent.
                Log.w(TAG, "Could not load sfx/${sound.file}.ogg", e)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    override fun play(sound: Sound) {
        if (!_enabled.value) return
        val id = ids[sound] ?: return
        val now = SystemClock.uptimeMillis()
        synchronized(lastPlay) {
            if (now - lastPlay[sound.ordinal] < sound.minGapMs) return
            lastPlay[sound.ordinal] = now
        }
        if (!synchronized(ready) { id in ready }) return
        pool.play(id, sound.volume, sound.volume, 1, 0, 1f)
    }
}
