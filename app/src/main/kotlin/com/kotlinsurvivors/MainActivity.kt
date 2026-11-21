package com.kotlinsurvivors

import javax.inject.Inject
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import com.kotlinsurvivors.core.audio.SoundManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kotlinsurvivors.core.presentation.language.AppLanguage
import com.kotlinsurvivors.core.presentation.theme.KotlinSurvivorsTheme
import com.kotlinsurvivors.features.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity that hosts the entire game UI via Jetpack Compose.
 * Configured for landscape orientation, immersive full-screen mode,
 * and maximum hardware performance.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var soundManager: SoundManager

    /** Puts the language chosen in Settings on every resource the activity reads. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep screen on during gameplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set hardware layer for better rendering performance
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        // Edge to edge
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Full immersive mode - hide system bars
        hideSystemBars()

        setContent {
            CompositionLocalProvider(LocalSoundEffects provides soundManager) {
                KotlinSurvivorsTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}
