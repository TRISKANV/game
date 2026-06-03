package com.kotlinsurvivors.features.settings.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.features.settings.presentation.viewmodel.SettingsViewModel

/**
 * SettingsScreen
 *
 * Allows the player to configure:
 *  - Screen shake intensity
 *  - Show FPS counter
 *  - Joystick size
 *  - Vibration on hit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SETTINGS",
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color         = Color(0xFFAAAAAA)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color(0xFFAAAAAA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A14))
            )
        },
        containerColor = Color(0xFF0A0A14)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SettingsSection("GAMEPLAY")

            SettingsToggle(
                label    = "Screen Shake",
                sublabel = "Shake screen when taking damage",
                checked  = settings.screenShake,
                onToggle = { viewModel.setScreenShake(it) }
            )

            SettingsToggle(
                label    = "Vibration",
                sublabel = "Vibrate on hit and level-up",
                checked  = settings.vibration,
                onToggle = { viewModel.setVibration(it) }
            )

            Spacer(Modifier.height(8.dp))
            SettingsSection("CONTROLS")

            SettingsSlider(
                label    = "Joystick Size",
                sublabel = "%.0f%%".format(settings.joystickSize * 100f),
                value    = settings.joystickSize,
                onValue  = { viewModel.setJoystickSize(it) },
                range    = 0.6f..1.4f
            )

            Spacer(Modifier.height(8.dp))
            SettingsSection("DISPLAY")

            SettingsToggle(
                label    = "Show FPS",
                sublabel = "Display frames per second counter",
                checked  = settings.showFps,
                onToggle = { viewModel.setShowFps(it) }
            )

            SettingsToggle(
                label    = "Show Damage Numbers",
                sublabel = "Show floating damage values",
                checked  = settings.showDamageNumbers,
                onToggle = { viewModel.setShowDamageNumbers(it) }
            )

            Spacer(Modifier.height(8.dp))
            SettingsSection("ABOUT")

            SettingsInfoRow("Version",    "1.0.0")
            SettingsInfoRow("Engine",     "Custom ECS / Compose Canvas")
            SettingsInfoRow("License",    "MIT Open Source")

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        color         = Color(0xFF4FC3F7),
        fontSize      = 11.sp,
        fontWeight    = FontWeight.ExtraBold,
        letterSpacing = 2.sp,
        modifier      = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    Divider(color = Color(0xFF4FC3F7).copy(alpha = 0.15f))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsToggle(
    label    : String,
    sublabel : String,
    checked  : Boolean,
    onToggle : (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label,    color = Color(0xFFE0E0E0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sublabel, color = Color(0xFF666666), fontSize = 12.sp)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onToggle,
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color(0xFF4FC3F7),
                checkedTrackColor   = Color(0xFF4FC3F7).copy(alpha = 0.3f),
                uncheckedThumbColor = Color(0xFF555555),
                uncheckedTrackColor = Color(0xFF222233)
            )
        )
    }
}

@Composable
private fun SettingsSlider(
    label    : String,
    sublabel : String,
    value    : Float,
    onValue  : (Float) -> Unit,
    range    : ClosedFloatingPointRange<Float>
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label,    color = Color(0xFFE0E0E0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sublabel, color = Color(0xFF4FC3F7),  fontSize = 13.sp)
        }
        Slider(
            value         = value,
            onValueChange = onValue,
            valueRange    = range,
            colors        = SliderDefaults.colors(
                thumbColor       = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7)
            )
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 13.sp)
        Text(value, color = Color(0xFFAAAAAA), fontSize = 13.sp)
    }
}
