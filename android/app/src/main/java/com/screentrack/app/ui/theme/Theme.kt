package com.screentrack.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ClayPrimaryDark,
    onPrimary = Color(0xFF2C1810),
    primaryContainer = Color(0xFF5D2E1E),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = ClaySecondaryDark,
    onSecondary = Color(0xFF142C1E),
    secondaryContainer = Color(0xFF2A4A38),
    onSecondaryContainer = Color(0xFFCCE8D7),
    tertiary = ClayTertiaryDark,
    onTertiary = Color(0xFF33200B),
    tertiaryContainer = Color(0xFF563B19),
    onTertiaryContainer = Color(0xFFFFE0B6),
    background = ClayBackgroundDark,
    onBackground = ClayTextPrimaryDark,
    surface = ClaySurfaceDark,
    onSurface = ClayTextPrimaryDark,
    surfaceVariant = Color(0xFF332F2A),
    onSurfaceVariant = ClayTextSecondaryDark,
    outline = ClayCardBorderDark,
    outlineVariant = Color(0xFF262320)
)

private val LightColorScheme = lightColorScheme(
    primary = ClayPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDECE5),
    onPrimaryContainer = Color(0xFF4C2114),
    secondary = ClaySecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EFE7),
    onSecondaryContainer = Color(0xFF1D3B2B),
    tertiary = ClayTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBF0DF),
    onTertiaryContainer = Color(0xFF4D3312),
    background = ClayBackgroundLight,
    onBackground = ClayTextPrimary,
    surface = ClaySurfaceLight,
    onSurface = ClayTextPrimary,
    surfaceVariant = Color(0xFFEDE4D8),
    onSurfaceVariant = ClayTextSecondary,
    outline = ClayCardBorderLight,
    outlineVariant = Color(0xFFDED3C4)
)

@Composable
fun ScreenTrackTheme(
    themeMode: String = "clay_light",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "clay_dark" -> true
        "system" -> systemDark
        else -> false // "clay_light" -> Signature Warm Cream Claymorphism
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
