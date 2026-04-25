package app.fabula.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Bottom padding that scrolling content (LazyColumn / LazyGrid) should add
 * to its [androidx.compose.foundation.layout.PaddingValues] so the last item
 * can scroll up past the translucent bottom navigation bar and mini player.
 *
 * Set by [Navigation]; defaults to 0 so isolated previews still render.
 */
val LocalContentBottomInset = compositionLocalOf { PaddingValues(0.dp) }
