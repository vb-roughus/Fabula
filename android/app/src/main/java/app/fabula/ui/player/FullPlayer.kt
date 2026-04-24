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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import app.fabula.player.PlayerController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var bookmarkSavedFlash by remember { mutableStateOf(false) }

    val pos = state.positionInBook

    val chapters = book.chapters
    val chapterIdx = state.currentChapter?.index ?: -1
    val prevChapter = chapters.getOrNull(chapterIdx - 1)
    val nextChapter = chapters.getOrNull(chapterIdx + 1)

    val chapterStart = state.currentChapter?.let { parseTimeSpan(it.start) } ?: 0.0
    val chapterEnd = state.currentChapter?.let { parseTimeSpan(it.end) } ?: state.durationInBook
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0.0)
    val chapterPos = (pos - chapterStart).coerceIn(0.0, chapterDuration)
    val sliderValue = scrubFraction
        ?: if (chapterDuration > 0) (chapterPos / chapterDuration).toFloat().coerceIn(0f, 1f) else 0f

    val whiteText = Color.White
    val mutedText = Color.White.copy(alpha = 0.65f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // Top row: collapse, "AUS DEM HÖRBUCH" / book title, more menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Minimieren",
                    tint = whiteText
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (book.series != null) "AUS DER SERIE" else "WIRD ABGESPIELT",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedText,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Text(
                    text = book.series ?: book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = whiteText,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
            IconButton(onClick = { /* more placeholder */ }) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "Mehr",
                    tint = whiteText
                )
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
        ) {
            repository.coverUrl(book)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.weight(0.4f))

        // Title row + bookmark icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.currentChapter?.title ?: book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = whiteText,
                    maxLines = 1
                )
                Text(
                    text = book.authors.joinToString(", ").ifBlank { book.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedText,
                    maxLines = 1
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        val api = repository.apiOrNull() ?: return@launch
                        runCatching {
                            api.createBookmark(
                                book.id,
                                CreateBookmarkRequest(
                                    position = toTimeSpanString(state.positionInBook),
                                    note = null
                                )
                            )
                            bookmarkSavedFlash = true
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = "Lesezeichen setzen",
                    tint = if (bookmarkSavedFlash) MaterialTheme.colorScheme.primary else whiteText
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Slim chapter-relative scrubber: time / slider / time on a single line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatClock(chapterPos),
                style = MaterialTheme.typography.labelSmall,
                color = mutedText,
                modifier = Modifier.width(40.dp)
            )
            Slider(
                value = sliderValue,
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = {
                    scrubFraction?.let { fraction ->
                        player.seekInBook(chapterStart + fraction * chapterDuration)
                    }
                    scrubFraction = null
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = whiteText,
                    activeTrackColor = whiteText,
                    inactiveTrackColor = whiteText.copy(alpha = 0.25f)
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(whiteText)
                    )
                },
                track = { sliderState ->
                    val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                    val fraction = if (range > 0f)
                        ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                    else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(whiteText.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(whiteText)
                        )
                    }
                }
            )
            Text(
                formatClock(chapterDuration),
                style = MaterialTheme.typography.labelSmall,
                color = mutedText,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.End
            )
        }

        Spacer(Modifier.height(16.dp))

        // Main controls row: -30s | prev chapter | PLAY/PAUSE | next chapter | +30s
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player.skip(-30.0) }) {
                Icon(
                    Icons.Filled.Replay30,
                    contentDescription = "30 Sek. zurück",
                    tint = whiteText,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(
                onClick = { prevChapter?.let { player.jumpToChapter(it) } },
                enabled = prevChapter != null
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Vorheriges Kapitel",
                    tint = if (prevChapter != null) whiteText else whiteText.copy(alpha = 0.3f),
                    modifier = Modifier.size(40.dp)
                )
            }
            // Big circular play button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(whiteText)
                    .clickable { player.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Abspielen",
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(
                onClick = { nextChapter?.let { player.jumpToChapter(it) } },
                enabled = nextChapter != null
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Nächstes Kapitel",
                    tint = if (nextChapter != null) whiteText else whiteText.copy(alpha = 0.3f),
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = { player.skip(30.0) }) {
                Icon(
                    Icons.Filled.Forward30,
                    contentDescription = "30 Sek. vor",
                    tint = whiteText,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.weight(0.3f))

        // Bottom utility row: speed picker on left
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { speedMenuOpen = true }) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        tint = whiteText,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${"%.2f".format(currentSpeed).trimEnd('0').trimEnd('.', ',')}×",
                        color = whiteText,
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
            Spacer(Modifier.weight(1f))
        }
    }
}
