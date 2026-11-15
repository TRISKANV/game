package com.kotlinsurvivors

import android.app.Application
import com.kotlinsurvivors.core.crash.CrashLogger
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for KotlinSurvivors.
 * Annotated with @HiltAndroidApp to trigger Hilt's code generation and
 * set up the application-level dependency injection component.
 */
@HiltAndroidApp
class KotlinSurvivorsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // First thing after super: from here on every crash, memory warning
        // and the reason the previous process died ends up in crash_log.txt.
        CrashLogger.init(this)
    }
}
