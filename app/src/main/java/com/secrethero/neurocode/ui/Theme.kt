package com.secrethero.neurocode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.secrethero.neurocode.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3FB950),
    onPrimary = Color(0xFF04260F),
    primaryContainer = Color(0xFF0F5323),
    onPrimaryContainer = Color(0xFF7EE787),
    secondary = Color(0xFF79C0FF),
    onSecondary = Color(0xFF08203F),
    secondaryContainer = Color(0xFF14345C),
    onSecondaryContainer = Color(0xFFC6E2FF),
    tertiary = Color(0xFFFFB86C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3A00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFF85149),
    onError = Color(0xFF490202),
    errorContainer = Color(0xFF67060C),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF161B22),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F8EA),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF5A4BAE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4DEFF),
    onSecondaryContainer = Color(0xFF180F55),
    tertiary = Color(0xFF8A4F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FAFC),
    onBackground = Color(0xFF18202B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18202B),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF414C59),
    outline = Color(0xFF71808F),
    inverseSurface = Color(0xFF18202B),
    inverseOnSurface = Color(0xFFF7FAFC),
)

@Composable
fun NeuroCodeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
