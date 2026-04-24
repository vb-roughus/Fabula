package app.fabula.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink900 = Color(0xFF0B0F14)
private val Ink800 = Color(0xFF121821)
private val Ink700 = Color(0xFF1A222E)
private val Ink400 = Color(0xFF6D7A8C)
private val Ink200 = Color(0xFFD0D6E0)
private val Ink100 = Color(0xFFEEF1F6)
private val Accent500 = Color(0xFFE88F33)
private val Accent400 = Color(0xFFF5B06B)

private val DarkScheme = darkColorScheme(
    primary = Accent500,
    onPrimary = Ink900,
    secondary = Accent400,
    onSecondary = Ink900,
    background = Ink900,
    onBackground = Ink100,
    surface = Ink800,
    onSurface = Ink100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Ink200,
    outline = Ink400
)

private val LightScheme = lightColorScheme(
    primary = Accent500,
    onPrimary = Ink900,
    secondary = Accent400,
    background = Ink100,
    surface = Ink200,
    onSurface = Ink900
)

@Composable
fun FabulaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
