package com.kotlinsurvivors.core.presentation.language

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language of the game's own screens: English or Spanish.
 *
 * Until the player picks one in Settings the game follows the phone (Spanish if the phone
 * is in Spanish, English otherwise). The choice is kept in SharedPreferences, not in
 * DataStore, because it has to be read synchronously, before the first screen is built.
 */
object AppLanguage {
    const val ENGLISH = "en"
    const val SPANISH = "es"

    private const val PREFS = "app_language"
    private const val KEY   = "tag"

    /** What the player chose in Settings, or null if they never did. */
    fun saved(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it == ENGLISH || it == SPANISH }

    /** The language [context] is showing: ENGLISH or SPANISH. */
    fun of(context: Context): String =
        if (context.resources.configuration.locales[0].language == SPANISH) SPANISH else ENGLISH

    /** The language the game will show, even when [context] is the application context that never got [wrap]. */
    fun effective(context: Context): String = saved(context) ?: of(context)

    /** Applies the saved language to [base]; the phone's own language when none was saved. */
    fun wrap(base: Context): Context {
        val tag    = saved(base) ?: return base
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Saves [tag] and rebuilds the screen so every text is read again in the new language. */
    fun change(activity: Activity, tag: String) {
        if (tag != ENGLISH && tag != SPANISH) return
        if (tag == of(activity)) return
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        activity.recreate()
    }
}
