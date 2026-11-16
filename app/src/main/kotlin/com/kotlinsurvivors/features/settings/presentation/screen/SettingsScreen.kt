package com.kotlinsurvivors.features.settings.presentation.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlinsurvivors.core.crash.CrashLogger
import com.kotlinsurvivors.features.settings.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SettingsScreen
 *
 * Allows the player to configure:
 *  - Screen shake intensity
 *  - Show FPS counter
 *  - Joystick size
 *  - Vibration on hit
 *
 * Hidden developer feature: tapping the "MIT Open Source" row five times in
 * a row opens the diagnostic log viewer (see [CrashLogger]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showLogViewer by remember { mutableStateOf(false) }
    var tapCount      by remember { mutableIntStateOf(0) }
    var lastTapAt     by remember { mutableLongStateOf(0L) }

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

            SettingsInfoRow("Version", "1.0.0")
            SettingsInfoRow("Engine",  "Custom ECS / Compose Canvas")
            SettingsInfoRow(
                label   = "License",
                value   = "MIT Open Source",
                onClick = {
                    val now = SystemClock.elapsedRealtime()
                    // Taps must be close together, otherwise the counter restarts.
                    tapCount  = if (now - lastTapAt > TAP_WINDOW_MS) 1 else tapCount + 1
                    lastTapAt = now
                    if (tapCount >= TAPS_TO_OPEN_LOG) {
                        tapCount      = 0
                        showLogViewer = true
                    }
                }
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLogViewer) {
        CrashLogViewerDialog(onDismiss = { showLogViewer = false })
    }
}

private const val TAPS_TO_OPEN_LOG = 5
private const val TAP_WINDOW_MS    = 1500L
private const val SHARE_MAX_LINES  = 800

// ── Hidden diagnostic log viewer ─────────────────────────────────────────────

@Composable
private fun CrashLogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    var lines      by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState  = rememberLazyListState()

    // Reads the file off the main thread, then jumps to the newest line.
    LaunchedEffect(refreshKey) {
        lines = withContext(Dispatchers.IO) { CrashLogger.readLogLines() }
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside   = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(3.dp),
            color = Color(0xFF0A0A14)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "DIAGNOSTIC LOG",
                    color         = Color(0xFF7CE04A),
                    fontSize      = 13.sp,
                    fontWeight    = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    CrashLogger.getLogFilePath(),
                    color    = Color(0xFF777777),
                    fontSize = 10.sp
                )
                Text(
                    "${lines.size} lines — newest at the bottom",
                    color    = Color(0xFF777777),
                    fontSize = 10.sp
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogButton("Refresh") { refreshKey++ }
                    LogButton("Copy") {
                        copyToClipboard(context, tailText(lines))
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    }
                    LogButton("Share") { shareText(context, tailText(lines)) }
                    LogButton("Clear") {
                        scope.launch {
                            withContext(Dispatchers.IO) { CrashLogger.clearLog() }
                            refreshKey++
                        }
                    }
                    LogButton("Close", onDismiss)
                }

                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    state    = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(3.dp))
                        .padding(6.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            text       = line,
                            color      = lineColor(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick        = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier       = Modifier.height(32.dp)
    ) {
        Text(text, fontSize = 12.sp)
    }
}

/** Highlights the lines that matter most when hunting a crash. */
private fun lineColor(line: String): Color = when {
    "CRASH" in line || "UNCAUGHT" in line || "Caused by" in line -> Color(0xFFEF5350)
    "EXIT REASON" in line || "PREVIOUS SESSION" in line          -> Color(0xFFFFCA28)
    "onTrimMemory" in line || "onLowMemory" in line              -> Color(0xFFFFA726)
    "SESSION START" in line || "FOREGROUND" in line || "BACKGROUND" in line -> Color(0xFF7CE04A)
    "STATS" in line                                              -> Color(0xFF81C784)
    else                                                         -> Color(0xFFBBBBBB)
}

private fun tailText(lines: List<String>): String =
    lines.takeLast(SHARE_MAX_LINES).joinToString("\n")

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("KotlinSurvivors log", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "KotlinSurvivors diagnostic log")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share log"))
}

// ── Regular settings widgets ─────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        color         = Color(0xFF7CE04A),
        fontSize      = 11.sp,
        fontWeight    = FontWeight.ExtraBold,
        letterSpacing = 2.sp,
        modifier      = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    Divider(color = Color(0xFF7CE04A).copy(alpha = 0.15f))
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
                checkedThumbColor   = Color(0xFF7CE04A),
                checkedTrackColor   = Color(0xFF7CE04A).copy(alpha = 0.3f),
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
            Text(sublabel, color = Color(0xFF7CE04A),  fontSize = 13.sp)
        }
        Slider(
            value         = value,
            onValueChange = onValue,
            valueRange    = range,
            colors        = SliderDefaults.colors(
                thumbColor       = Color(0xFF7CE04A),
                activeTrackColor = Color(0xFF7CE04A)
            )
        )
    }
}

/**
 * Read-only label/value row. When [onClick] is set the row reacts to taps
 * without any visual feedback (no ripple), which keeps the hidden log
 * viewer hidden.
 */
@Composable
private fun SettingsInfoRow(
    label   : String,
    value   : String,
    onClick : (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication        = null,
            onClick           = onClick
        )
    } else {
        Modifier
    }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 13.sp)
        Text(value, color = Color(0xFFAAAAAA), fontSize = 13.sp)
    }
}
