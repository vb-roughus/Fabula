package app.fabula.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.BookSummaryDto
import app.fabula.data.FabulaRepository
import app.fabula.data.parseTimeSpan
import app.fabula.ui.LocalContentBottomInset
import coil3.compose.AsyncImage

private val TileWidth = 140.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: FabulaRepository,
    onMenuClick: () -> Unit,
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
        ?.take(15)
        ?: emptyList()

    val recentlyAdded = books
        ?.sortedByDescending { it.id }
        ?.take(15)
        ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Startseite") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menü")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 8.dp,
                        bottom = 8.dp + LocalContentBottomInset.current.calculateBottomPadding()
                    )
                ) {
                    if (continueListening.isNotEmpty()) {
                        item("h-continue") { SectionHeader("Weiter hören") }
                        item("r-continue") {
                            BookTilesRow(
                                books = continueListening,
                                repository = repository,
                                onBookClick = onBookClick
                            )
                        }
                    }

                    if (recentlyAdded.isNotEmpty()) {
                        item("h-added") { SectionHeader("Zuletzt hinzugefügt") }
                        item("r-added") {
                            BookTilesRow(
                                books = recentlyAdded,
                                repository = repository,
                                onBookClick = onBookClick,
                                keyPrefix = "added"
                            )
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
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun BookTilesRow(
    books: List<BookSummaryDto>,
    repository: FabulaRepository,
    onBookClick: (Int) -> Unit,
    keyPrefix: String = "tile"
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = books, key = { "$keyPrefix-${it.id}" }) { book ->
            BookTile(
                book = book,
                repository = repository,
                onClick = { onBookClick(book.id) }
            )
        }
    }
}

@Composable
private fun BookTile(
    book: BookSummaryDto,
    repository: FabulaRepository,
    onClick: () -> Unit
) {
    val durationSec = parseTimeSpan(book.duration)
    val positionSec = book.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    val pct = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    val started = positionSec > 1.0 && book.progress?.finished != true
    val finished = book.progress?.finished == true

    Column(
        modifier = Modifier
            .width(TileWidth)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            repository.coverUrl(book)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
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

            if (started) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
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
    }
}
