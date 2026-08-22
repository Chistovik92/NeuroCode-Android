package com.secrethero.neurocode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.secrethero.neurocode.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65E5C4),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF0B4F42),
    onPrimaryContainer = Color(0xFFB8F5E6),
    secondary = Color(0xFFB9AFFF),
    onSecondary = Color(0xFF241D66),
    secondaryContainer = Color(0xFF33296E),
    onSecondaryContainer = Color(0xFFE4DEFF),
    tertiary = Color(0xFFFFB86C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3A00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF4A0003),
    errorContainer = Color(0xFF690009),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF111722),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFFB9C2CF),
    outline = Color(0xFF3B4657),
    inverseSurface = Color(0xFFE6EDF7),
    inverseOnSurface = Color(0xFF111722),
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
