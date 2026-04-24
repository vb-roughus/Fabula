package app.fabula.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.fabula.data.FabulaRepository
import app.fabula.player.PlayerController

/**
 * Content of the BottomSheet. When collapsed (peek) we stack the mini
 * player above the bottom navigation tabs. When expanded, the full
 * player takes over and hides both.
 */
@Composable
fun PlayerSheet(
    player: PlayerController,
    repository: FabulaRepository,
    isExpanded: Boolean,
    onRequestExpand: () -> Unit,
    onRequestCollapse: () -> Unit,
    onOpenBook: (Int) -> Unit,
    bottomTabsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = isExpanded,
        label = "player-sheet-state",
        modifier = modifier.fillMaxSize()
    ) { expanded ->
        Box(Modifier.fillMaxSize()) {
            if (expanded) {
                FullPlayer(
                    player = player,
                    repository = repository,
                    onCollapse = onRequestCollapse,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    MiniPlayer(
                        player = player,
                        repository = repository,
                        onClick = onRequestExpand,
                        modifier = Modifier.fillMaxWidth()
                    )
                    bottomTabsContent()
                }
            }
        }
    }
}
