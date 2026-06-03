package com.kotlinsurvivors.core.presentation.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Brand Colors ─────────────────────────────────────────────────────────────
val BluePrimary     = Color(0xFF4FC3F7)
val BluePrimaryDark = Color(0xFF0288D1)
val PurpleAccent    = Color(0xFFCE93D8)
val GoldAccent      = Color(0xFFFFD740)
val GreenXP         = Color(0xFF66BB6A)
val RedDanger       = Color(0xFFEF5350)
val DarkBg          = Color(0xFF0A0A14)
val DarkSurface     = Color(0xFF12121E)
val DarkCard        = Color(0xFF1A1A2E)

private val DarkColorScheme = darkColorScheme(
    primary          = BluePrimary,
    onPrimary        = Color.Black,
    primaryContainer = BluePrimaryDark,
    secondary        = PurpleAccent,
    onSecondary      = Color.Black,
    tertiary         = GoldAccent,
    background       = DarkBg,
    surface          = DarkSurface,
    surfaceVariant   = DarkCard,
    onBackground     = Color(0xFFE0E0E0),
    onSurface        = Color(0xFFE0E0E0),
    error            = RedDanger,
    onError          = Color.White,
    outline          = Color(0xFF333355)
)

@Composable
fun KotlinSurvivorsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
