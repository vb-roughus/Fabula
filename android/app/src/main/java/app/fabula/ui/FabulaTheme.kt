package app.fabula.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Navy-black palette: deep blue background, slightly lighter surface tones,
// bright text with a clearly readable secondary tone (no more "grey on grey").
private val Navy950 = Color(0xFF0A1428)  // background
private val Navy900 = Color(0xFF0F1A30)  // surface
private val Navy800 = Color(0xFF18243F)  // surfaceVariant (cards / chips)
private val Navy700 = Color(0xFF24314F)  // dividers / track
private val Slate300 = Color(0xFFA8B4CC)  // secondary text -- much lighter than the old outline
private val Slate200 = Color(0xFFC9D2E4)
private val OffWhite = Color(0xFFF1F4FA)  // primary text
private val Accent500 = Color(0xFFE88F33)
private val Accent400 = Color(0xFFF5B06B)

val FabulaBackground get() = Navy950

private val DarkScheme = darkColorScheme(
    primary = Accent500,
    onPrimary = Navy950,
    secondary = Accent400,
    onSecondary = Navy950,
    background = Navy950,
    onBackground = OffWhite,
    surface = Navy900,
    onSurface = OffWhite,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate200,
    outline = Slate300
)

private val LightScheme = lightColorScheme(
    primary = Accent500,
    onPrimary = Navy950,
    secondary = Accent400,
    background = OffWhite,
    surface = Slate200,
    onSurface = Navy950
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
