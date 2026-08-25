package com.secrethero.neurocode.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.model.AppDesign
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

/** Gemini-inspired Material 3 Expressive dark palette (UI reference 0.7.0). */
private val ModernDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF041E49),
    primaryContainer = Color(0xFF282A2C),
    onPrimaryContainer = Color(0xFFA8C7FA),
    secondary = Color(0xFFC58AF9),
    onSecondary = Color(0xFF2A004F),
    secondaryContainer = Color(0xFF3A2B52),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = Color(0xFF6DD58C),
    onTertiary = Color(0xFF00391B),
    tertiaryContainer = Color(0xFF1E4D33),
    onTertiaryContainer = Color(0xFFA6F2BB),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF1E1F20),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF282A2C),
    onSurfaceVariant = Color(0xFF8E918F),
    outline = Color(0xFF47494B),
    outlineVariant = Color(0xFF2C2E30),
    inverseSurface = Color(0xFFE3E3E3),
    inverseOnSurface = Color(0xFF131314),
)

/** Gemini-inspired light palette so theme mode keeps working in the modern design. */
private val ModernLightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF6B4FA1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF2A004F),
    tertiary = Color(0xFF146C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA6F2BB),
    onTertiaryContainer = Color(0xFF00391B),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF0F4F9),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE1E3E6),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFDCDEE0),
    inverseSurface = Color(0xFF1F1F1F),
    inverseOnSurface = Color(0xFFF0F4F9),
)

/** Softer, rounder shapes used by the modern (Material 3 Expressive) design. */
private val ModernShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun NeuroCodeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    appDesign: AppDesign = AppDesign.CLASSIC,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        appDesign == AppDesign.MODERN && dark -> ModernDarkColors
        appDesign == AppDesign.MODERN -> ModernLightColors
        dark -> DarkColors
        else -> LightColors
    }
    val shapes = if (appDesign == AppDesign.MODERN) ModernShapes else MaterialTheme.shapes
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        content = content,
    )
}
