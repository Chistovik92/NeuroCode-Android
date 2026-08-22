package com.secrethero.neurocode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF65E5C4),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF0B4F42),
    secondary = Color(0xFFB9AFFF),
    tertiary = Color(0xFFFFB86C),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF111722),
    surfaceVariant = Color(0xFF1A2230),
    onBackground = Color(0xFFE6EDF7),
    onSurface = Color(0xFFE6EDF7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B59),
    secondary = Color(0xFF5D4FB0),
    tertiary = Color(0xFF8A4F00),
    background = Color(0xFFF7FAFC),
    surface = Color.White,
)

@Composable
fun NeuroCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
