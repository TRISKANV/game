package com.kotlinsurvivors.features.settings.presentation.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore(name = "settings")

data class GameSettings(
    val screenShake       : Boolean = true,
    val vibration         : Boolean = true,
    val showFps           : Boolean = false,
    val showDamageNumbers : Boolean = true,
    val joystickSize      : Float   = 1.0f
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private object Keys {
        val SCREEN_SHAKE        = booleanPreferencesKey("screen_shake")
        val VIBRATION           = booleanPreferencesKey("vibration")
        val SHOW_FPS            = booleanPreferencesKey("show_fps")
        val SHOW_DAMAGE_NUMBERS = booleanPreferencesKey("show_damage_numbers")
        val JOYSTICK_SIZE       = floatPreferencesKey("joystick_size")
    }

    val settings: StateFlow<GameSettings> = context.dataStore.data
        .map { prefs ->
            GameSettings(
                screenShake       = prefs[Keys.SCREEN_SHAKE]        ?: true,
                vibration         = prefs[Keys.VIBRATION]           ?: true,
                showFps           = prefs[Keys.SHOW_FPS]            ?: false,
                showDamageNumbers = prefs[Keys.SHOW_DAMAGE_NUMBERS] ?: true,
                joystickSize      = prefs[Keys.JOYSTICK_SIZE]       ?: 1.0f
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GameSettings())

    fun setScreenShake(value: Boolean) = save { it[Keys.SCREEN_SHAKE] = value }
    fun setVibration(value: Boolean)   = save { it[Keys.VIBRATION]    = value }
    fun setShowFps(value: Boolean)     = save { it[Keys.SHOW_FPS]     = value }
    fun setShowDamageNumbers(value: Boolean) = save { it[Keys.SHOW_DAMAGE_NUMBERS] = value }
    fun setJoystickSize(value: Float)  = save { it[Keys.JOYSTICK_SIZE] = value }

    private fun save(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        viewModelScope.launch {
            context.dataStore.edit { prefs -> block(prefs) }
        }
    }
}
