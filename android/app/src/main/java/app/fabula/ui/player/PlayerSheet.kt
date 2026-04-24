package app.fabula.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import app.fabula.data.FabulaRepository
import app.fabula.player.PlayerController

/**
 * Renders the mini (collapsed) and full (expanded) players stacked, cross-fading
 * based on whether the BottomSheet is expanded. The mini version responds to tap
 * by expanding the sheet.
 */
@Composable
fun PlayerSheet(
    player: PlayerController,
    repository: FabulaRepository,
    isExpanded: Boolean,
    onRequestExpand: () -> Unit,
    onRequestCollapse: () -> Unit,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val expansion by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        label = "player-sheet-expansion"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Mini bar at the top of the sheet, visible when collapsed.
        PlayerBar(
            player = player,
            onOpenBook = onOpenBook,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(1f - expansion)
                .clickable(enabled = !isExpanded) { onRequestExpand() }
        )

        // Full player fills the sheet, visible when expanded.
        if (expansion > 0.01f) {
            FullPlayer(
                player = player,
                repository = repository,
                onCollapse = onRequestCollapse,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(expansion)
            )
        }
    }
}
