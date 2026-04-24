package app.fabula.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fabula.data.FabulaRepository
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import app.fabula.player.PlayerController
import coil3.compose.AsyncImage

private val SPEED_CHOICES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

@Composable
fun FullPlayer(
    player: PlayerController,
    repository: FabulaRepository,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by player.state.collectAsState()
    val book = state.book ?: return

    // Local drag position so the slider stays smooth while the user scrubs.
    // Stored as book-wide seconds; the slider projects into chapter-relative.
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var speedMenuOpen by remember { mutableStateOf(false) }

    val pos = scrubPosition?.toDouble() ?: state.positionInBook

    val chapters = book.chapters
    val chapterIdx = state.currentChapter?.index ?: -1
    val prevChapter = chapters.getOrNull(chapterIdx - 1)
    val nextChapter = chapters.getOrNull(chapterIdx + 1)

    // The slider reflects progress within the current chapter. When no chapter
    // is defined at the current position, fall back to the whole book.
    val chapterStart = state.currentChapter?.let { parseTimeSpan(it.start) } ?: 0.0
    val chapterEnd = state.currentChapter?.let { parseTimeSpan(it.end) } ?: state.durationInBook
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0.0)
    val chapterPos = (pos - chapterStart).coerceIn(0.0, chapterDuration)
    val sliderValue = if (chapterDuration > 0) (chapterPos / chapterDuration).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Minimieren")
            }
            Spacer(Modifier.weight(1f))
            book.series?.let {
                Text(
                    it + (book.seriesPosition?.let { pos -> " #${pos}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center
                )
            } ?: Text(
                "Wird abgespielt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp))  // balance the row
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val coverUrl = repository.coverUrl(book)
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    book.title,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Column {
            Text(
                book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            state.currentChapter?.let {
                Text(
                    it.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
            }
        }

        Column {
            Slider(
                value = sliderValue,
                onValueChange = { fraction ->
                    scrubPosition = (chapterStart + fraction * chapterDuration).toFloat()
                },
                onValueChangeFinished = {
                    scrubPosition?.let { player.seekInBook(it.toDouble()) }
                    scrubPosition = null
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatClock(chapterPos),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "-" + formatClock((chapterDuration - chapterPos).coerceAtLeast(0.0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { prevChapter?.let { player.jumpToChapter(it) } },
                enabled = prevChapter != null
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Vorheriges Kapitel",
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { player.skip(-30.0) }) {
                Icon(
                    Icons.Filled.Replay30,
                    contentDescription = "30 Sek. zurück",
                    modifier = Modifier.size(36.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { player.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                if (state.isPlaying) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Abspielen",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            IconButton(onClick = { player.skip(30.0) }) {
                Icon(
                    Icons.Filled.Forward30,
                    contentDescription = "30 Sek. vor",
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(
                onClick = { nextChapter?.let { player.jumpToChapter(it) } },
                enabled = nextChapter != null
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Nächstes Kapitel",
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { speedMenuOpen = true }) {
                    Text(
                        "${"%.2f".format(currentSpeed).trimEnd('0').trimEnd('.')}×",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false }
                ) {
                    SPEED_CHOICES.forEach { s ->
                        DropdownMenuItem(
                            text = { Text("${s}×") },
                            onClick = {
                                currentSpeed = s
                                player.setSpeed(s)
                                speedMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
