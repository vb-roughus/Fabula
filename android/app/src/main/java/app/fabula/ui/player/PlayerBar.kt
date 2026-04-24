package app.fabula.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import app.fabula.player.PlayerController

@Composable
fun PlayerBar(
    player: PlayerController,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by player.state.collectAsState()
    val book = state.book ?: return

    val chapters = book.chapters
    val chapterIdx = state.currentChapter?.index ?: -1
    val prevChapter = chapters.getOrNull(chapterIdx - 1)
    val nextChapter = chapters.getOrNull(chapterIdx + 1)

    // Progress relative to the current chapter (falls back to book-wide if
    // no chapter is defined at the current position).
    val chapterStart = state.currentChapter?.let { parseTimeSpan(it.start) } ?: 0.0
    val chapterEnd = state.currentChapter?.let { parseTimeSpan(it.end) } ?: state.durationInBook
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0.0)
    val chapterPos = (state.positionInBook - chapterStart).coerceIn(0.0, chapterDuration)
    val pct = if (chapterDuration > 0) (chapterPos / chapterDuration).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenBook(book.id) }
                    .padding(start = 8.dp)
            ) {
                Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    state.currentChapter?.title ?: formatClock(state.positionInBook),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }

            IconButton(
                onClick = { prevChapter?.let { player.jumpToChapter(it) } },
                enabled = prevChapter != null
            ) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Vorheriges Kapitel") }

            IconButton(onClick = { player.skip(-30.0) }) {
                Icon(Icons.Filled.Replay30, contentDescription = "30 Sek. zurück")
            }

            IconButton(
                onClick = { player.togglePlayPause() },
                modifier = Modifier.size(48.dp)
            ) {
                if (state.isPlaying) {
                    Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(32.dp))
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Abspielen", modifier = Modifier.size(32.dp))
                }
            }

            IconButton(onClick = { player.skip(30.0) }) {
                Icon(Icons.Filled.Forward30, contentDescription = "30 Sek. vor")
            }

            IconButton(
                onClick = { nextChapter?.let { player.jumpToChapter(it) } },
                enabled = nextChapter != null
            ) { Icon(Icons.Filled.SkipNext, contentDescription = "Nächstes Kapitel") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatClock(chapterPos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Text(
                "-" + formatClock((chapterDuration - chapterPos).coerceAtLeast(0.0)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
