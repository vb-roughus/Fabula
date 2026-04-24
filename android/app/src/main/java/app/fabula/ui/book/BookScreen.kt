package app.fabula.ui.book

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import app.fabula.data.FabulaRepository
import app.fabula.data.formatClock
import app.fabula.data.formatDurationHuman
import app.fabula.data.parseTimeSpan
import app.fabula.player.PlayerController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Int,
    repository: FabulaRepository,
    player: PlayerController,
    onBack: () -> Unit,
    onPlaybackStarted: () -> Unit
) {
    var book by remember { mutableStateOf<BookDetailDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val playerState by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(bookId) {
        try {
            val api = repository.apiOrNull() ?: run {
                error = "Kein Server konfiguriert."
                return@LaunchedEffect
            }
            book = api.getBook(bookId)
        } catch (t: Throwable) {
            error = t.message
        }
    }

    LaunchedEffect(book?.id) {
        val current = book ?: return@LaunchedEffect
        if (playerState.book?.id != current.id) {
            player.loadBook(current)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.Center
        ) {
            val b = book
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                b == null -> CircularProgressIndicator()
                else -> BookContent(
                    book = b,
                    repository = repository,
                    isCurrent = playerState.book?.id == b.id,
                    isPlaying = playerState.isPlaying,
                    currentChapterIndex = playerState.currentChapter?.index,
                    onPlay = {
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            player.play()
                            onPlaybackStarted()
                        }
                    },
                    onChapterClick = { chapter ->
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            player.jumpToChapter(chapter)
                            player.play()
                            onPlaybackStarted()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BookContent(
    book: BookDetailDto,
    repository: FabulaRepository,
    isCurrent: Boolean,
    isPlaying: Boolean,
    currentChapterIndex: Int?,
    onPlay: () -> Unit,
    onChapterClick: (ChapterDto) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .aspectRatio(1f)
                ) {
                    val coverUrl = repository.coverUrl(book)
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = book.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (book.authors.isNotEmpty()) {
                        Text("von ${book.authors.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (book.narrators.isNotEmpty()) {
                        Text(
                            "gesprochen von ${book.narrators.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    book.series?.let { series ->
                        Text(
                            buildString {
                                append(series)
                                book.seriesPosition?.let { append(" – Teil $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text(
                        formatDurationHuman(parseTimeSpan(book.duration)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPlay) {
                        Text(if (isCurrent && isPlaying) "Wird abgespielt" else "Abspielen")
                    }
                }
            }
        }

        if (!book.description.isNullOrBlank()) {
            item {
                Text(
                    book.description,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (book.chapters.isNotEmpty()) {
            item {
                Text(
                    "Kapitel",
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(items = book.chapters, key = { it.index }) { chapter ->
                val active = isCurrent && currentChapterIndex == chapter.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChapterClick(chapter) }
                        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${chapter.index + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        formatClock(parseTimeSpan(chapter.start)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
