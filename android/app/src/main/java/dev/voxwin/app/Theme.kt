package dev.voxwin.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// --- voxwin brand palette (used on devices without Material You dynamic color) ---
private val Indigo = Color(0xFF4C3FD6)
private val IndigoDark = Color(0xFFBEB4FF)
private val Teal = Color(0xFF00897B)
private val TealDark = Color(0xFF4DD0C4)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF140463),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF1B0A6B),
    primaryContainer = Color(0xFF3A2DBE),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = TealDark,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFF6FF7E7),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC8C5D0),
    error = Color(0xFFFFB4AB),
)

/**
 * App theme. Uses Material You dynamic color on Android 12+, falling back to the
 * voxwin brand palette (indigo / teal) on older devices. Follows the system
 * light/dark setting.
 */
@Composable
fun VoxwinTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
