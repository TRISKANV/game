package com.kotlinsurvivors.core.presentation.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Brand Colors ─────────────────────────────────────────────────────────────
val GreenPrimary     = Color(0xFF7CE04A)   // toxic green, matches the main character
val GreenPrimaryDark = Color(0xFF2E7D32)
val PurpleAccent    = Color(0xFFCE93D8)
val GoldAccent      = Color(0xFFFFD740)
val GreenXP         = Color(0xFF66BB6A)
val RedDanger       = Color(0xFFEF5350)
val DarkBg          = Color(0xFF08080F)
val DarkSurface     = Color(0xFF11111B)
val DarkCard        = Color(0xFF181826)

private val DarkColorScheme = darkColorScheme(
    primary          = GreenPrimary,
    onPrimary        = Color.Black,
    primaryContainer = GreenPrimaryDark,
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
    outline          = Color(0xFF2E3A2E)
)

@Composable
fun KotlinSurvivorsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
