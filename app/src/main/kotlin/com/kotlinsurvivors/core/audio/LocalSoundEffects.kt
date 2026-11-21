package com.kotlinsurvivors.core.audio

import androidx.compose.runtime.staticCompositionLocalOf

/** The sound output screens use for their own effects (button clicks, the roulette). Set once in MainActivity. */
val LocalSoundEffects = staticCompositionLocalOf<SoundEffects> { SoundEffects.None }
