package app.fabula.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.fabula.data.parseTimeSpan
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: FabulaRepository,
    onBookClick: (Int) -> Unit
) {
    var books by remember { mutableStateOf<List<BookSummaryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val api = repository.apiOrNull()
            if (api == null) {
                error = "Kein Server konfiguriert."
            } else {
                books = api.listBooks(page = 1, pageSize = 500).items
            }
        } catch (t: Throwable) {
            error = t.message
        }
    }

    val continueListening = books
        ?.filter { it.progress != null && !it.progress.finished && parseTimeSpan(it.progress.position) > 1.0 }
        ?.sortedByDescending { it.progress?.updatedAt ?: "" }
        ?.take(10)
        ?: emptyList()

    val recentlyAdded = books
        ?.sortedByDescending { it.id }
        ?.take(10)
        ?: emptyList()

    Scaffold(topBar = { TopAppBar(title = { Text("Startseite") }) }) { insets ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(insets),
            contentAlignment = Alignment.Center
        ) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                books == null -> CircularProgressIndicator()
                books!!.isEmpty() -> Text("Noch keine Hörbücher in deiner Bibliothek.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    if (continueListening.isNotEmpty()) {
                        item {
                            SectionHeader("Weiter hören")
                        }
                        items(items = continueListening, key = { it.id }) { book ->
                            BookRow(book = book, repository = repository, onClick = { onBookClick(book.id) })
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }

                    if (recentlyAdded.isNotEmpty()) {
                        item {
                            SectionHeader("Zuletzt hinzugefügt")
                        }
                        items(items = recentlyAdded, key = { "added-${it.id}" }) { book ->
                            BookRow(book = book, repository = repository, onClick = { onBookClick(book.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun BookRow(
    book: BookSummaryDto,
    repository: FabulaRepository,
    onClick: () -> Unit
) {
    val durationSec = parseTimeSpan(book.duration)
    val positionSec = book.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    val pct = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    val started = positionSec > 1.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            repository.coverUrl(book)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            if (started) {
                Spacer(Modifier.size(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(1.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
}
