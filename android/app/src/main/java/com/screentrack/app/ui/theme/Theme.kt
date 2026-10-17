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

private val DarkColorScheme = darkColorScheme(
    primary = ClayPrimaryDark,
    secondary = ClaySecondaryDark,
    tertiary = ClayTertiaryDark,
    background = ClayBackgroundDark,
    surface = ClaySurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = ClayPrimary,
    secondary = ClaySecondary,
    tertiary = ClayTertiary,
    background = ClayBackgroundLight,
    surface = ClaySurfaceLight
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
