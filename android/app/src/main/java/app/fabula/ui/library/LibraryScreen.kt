package app.fabula.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.BookSummaryDto
import app.fabula.data.FabulaRepository
import app.fabula.data.formatDurationHuman
import app.fabula.data.parseTimeSpan
import coil3.compose.AsyncImage

private sealed interface LibraryState {
    data object Loading : LibraryState
    data class Loaded(val books: List<BookSummaryDto>) : LibraryState
    data class Error(val message: String) : LibraryState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: FabulaRepository,
    onBookClick: (Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    var state by remember { mutableStateOf<LibraryState>(LibraryState.Loading) }

    LaunchedEffect(Unit) {
        state = try {
            val api = repository.apiOrNull()
            if (api == null) {
                LibraryState.Error("Kein Server konfiguriert.")
            } else {
                LibraryState.Loaded(api.listBooks(page = 1, pageSize = 200).items)
            }
        } catch (t: Throwable) {
            LibraryState.Error(t.message ?: "Unbekannter Fehler")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fabula") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
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
            when (val s = state) {
                LibraryState.Loading -> CircularProgressIndicator()
                is LibraryState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is LibraryState.Loaded -> {
                    if (s.books.isEmpty()) {
                        Text("Noch keine Hörbücher. Lege eine Bibliothek auf dem Server an und starte einen Scan.")
                    } else {
                        BookGrid(books = s.books, repository = repository, onClick = onBookClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookGrid(
    books: List<BookSummaryDto>,
    repository: FabulaRepository,
    onClick: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = books, key = { it.id }) { book ->
            BookCard(book = book, repository = repository, onClick = { onClick(book.id) })
        }
    }
}

@Composable
private fun BookCard(
    book: BookSummaryDto,
    repository: FabulaRepository,
    onClick: () -> Unit
) {
    val durationSec = parseTimeSpan(book.duration)
    val positionSec = book.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    val finished = book.progress?.finished == true
    val started = positionSec > 1.0
    val pct = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
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
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp)
                )
            }

            if (finished) {
                Text(
                    "FERTIG",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (started && !finished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct.toFloat())
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            Text(
                buildString {
                    append(formatDurationHuman(durationSec))
                    if (started && !finished) append(" · ${(pct * 100).toInt()} %")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
