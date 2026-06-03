package com.kotlinsurvivors

import android.app.Application
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
        // Future: initialize crash reporting, analytics, etc.
    }
}
