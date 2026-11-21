package com.kotlinsurvivors.features.settings.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.R
import com.kotlinsurvivors.core.audio.LocalSoundEffects
import com.kotlinsurvivors.core.audio.Sound
import com.kotlinsurvivors.core.presentation.language.AppLanguage
import com.kotlinsurvivors.features.settings.presentation.viewmodel.SettingsViewModel

/**
 * The settings that matter during a run (sound, joystick size, language, feedback),
 * opened from the pause menu so they can be changed without leaving the game.
 * Uses the same controls and the same saved values as the Settings screen of the main menu.
 */
@Composable
fun PauseSettingsPanel(
    viewModel: SettingsViewModel,
    onClose  : () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val soundOn  by viewModel.soundEffects.collectAsStateWithLifecycle()
    val context  = LocalContext.current
    val sounds   = LocalSoundEffects.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // Swallows taps so nothing behind the panel reacts to them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).fillMaxHeight(0.92f),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF15101F)),
            shape    = RoundedCornerShape(4.dp),
            border   = BorderStroke(2.dp, Color(0xFF3A2278))
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    stringResource(R.string.settings_title),
                    color         = Color(0xFF7CE04A),
                    fontSize      = 20.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 3.sp
                )
                Column(
                    modifier            = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SettingsSection(stringResource(R.string.settings_section_controls))
                    SettingsSlider(
                        label    = stringResource(R.string.settings_joystick),
                        sublabel = "%.0f%%".format(settings.joystickSize * 100f),
                        value    = settings.joystickSize,
                        onValue  = { viewModel.setJoystickSize(it) },
                        range    = 0.6f..1.4f
                    )

                    SettingsSection(stringResource(R.string.settings_section_gameplay))
                    SettingsToggle(
                        label    = stringResource(R.string.settings_sound_effects),
                        sublabel = stringResource(R.string.settings_sound_effects_sub),
                        checked  = soundOn,
                        onToggle = { viewModel.setSoundEffects(it) }
                    )
                    SettingsToggle(
                        label    = stringResource(R.string.settings_vibration),
                        sublabel = stringResource(R.string.settings_vibration_sub),
                        checked  = settings.vibration,
                        onToggle = { viewModel.setVibration(it) }
                    )
                    SettingsToggle(
                        label    = stringResource(R.string.settings_screen_shake),
                        sublabel = stringResource(R.string.settings_screen_shake_sub),
                        checked  = settings.screenShake,
                        onToggle = { viewModel.setScreenShake(it) }
                    )

                    SettingsSection(stringResource(R.string.settings_section_display))
                    SettingsToggle(
                        label    = stringResource(R.string.settings_show_fps),
                        sublabel = stringResource(R.string.settings_show_fps_sub),
                        checked  = settings.showFps,
                        onToggle = { viewModel.setShowFps(it) }
                    )
                    SettingsToggle(
                        label    = stringResource(R.string.settings_damage_numbers),
                        sublabel = stringResource(R.string.settings_damage_numbers_sub),
                        checked  = settings.showDamageNumbers,
                        onToggle = { viewModel.setShowDamageNumbers(it) }
                    )

                    SettingsSection(stringResource(R.string.settings_section_language))
                    LanguageRow(
                        label    = stringResource(R.string.settings_language),
                        sublabel = stringResource(R.string.settings_language_sub),
                        current  = AppLanguage.of(context),
                        onPick   = { tag -> context.findActivity()?.let { AppLanguage.change(it, tag) } }
                    )
                }
                Button(
                    onClick  = { sounds.play(Sound.UI_BACK); onClose() },
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7CE04A), contentColor = Color(0xFF0A0A14)),
                    shape    = RoundedCornerShape(3.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.settings_close), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                }
            }
        }
    }
}
