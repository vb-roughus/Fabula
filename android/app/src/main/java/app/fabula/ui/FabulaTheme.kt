package app.fabula.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Vita Brevis brand palette: deep navy (matches the wordmark) for backgrounds
// and a vivid emerald green (matches the "B" mark) as the primary accent.
private val Navy950 = Color(0xFF0B1224)  // background -- closer to the wordmark colour
private val Navy900 = Color(0xFF111A33)  // surface
private val Navy800 = Color(0xFF1A2545)  // surfaceVariant (cards / chips)
private val Navy700 = Color(0xFF243259)  // dividers / track
private val Slate300 = Color(0xFFA8B4CC)  // secondary text
private val Slate200 = Color(0xFFC9D2E4)
private val OffWhite = Color(0xFFF1F4FA)  // primary text
val BrandGreen500 = Color(0xFF31C76B)  // deeper end of the logo gradient
val BrandGreen400 = Color(0xFF5BE391)  // lighter end of the logo gradient
val BrandGreen300 = Color(0xFF8AF0AF)

val FabulaBackground get() = Navy950
val FabulaSurface get() = Navy900
val FabulaSurfaceVariant get() = Navy800

private val DarkScheme = darkColorScheme(
    primary = BrandGreen500,
    onPrimary = Navy950,
    secondary = BrandGreen400,
    onSecondary = Navy950,
    tertiary = BrandGreen300,
    background = Navy950,
    onBackground = OffWhite,
    surface = Navy900,
    onSurface = OffWhite,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate200,
    outline = Slate300
)

private val LightScheme = lightColorScheme(
    primary = BrandGreen500,
    onPrimary = Navy950,
    secondary = BrandGreen400,
    background = OffWhite,
    surface = Slate200,
    onSurface = Navy950
)

@Composable
fun FabulaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme) {
        // Default LocalContentColor is Color.Black, which leaks through every
        // Scaffold we configured with containerColor = Color.Transparent
        // (Material3 falls back to LocalContentColor.current when
        // contentColorFor can't resolve a transparent surface). Pin it to the
        // theme's onBackground so titles are readable on the gradient.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}
