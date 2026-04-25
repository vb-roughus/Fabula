package app.fabula.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
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
import app.fabula.data.FabulaRepository
import app.fabula.data.SeriesBookDto
import app.fabula.data.SeriesDetailDto
import app.fabula.ui.LocalContentBottomInset
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    repository: FabulaRepository,
    onBack: () -> Unit,
    onBookClick: (Int) -> Unit
) {
    var series by remember { mutableStateOf<SeriesDetailDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seriesId) {
        try {
            val api = repository.apiOrNull()
            if (api == null) error = "Kein Server konfiguriert."
            else series = api.getSeries(seriesId)
        } catch (t: Throwable) { error = t.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(series?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                series == null -> CircularProgressIndicator()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = LocalContentBottomInset.current.calculateBottomPadding()
                    )
                ) {
                    series!!.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item {
                            Text(
                                desc,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    items(items = series!!.books, key = { it.id }) { book ->
                        SeriesBookRow(book = book, repository = repository, onClick = { onBookClick(book.id) })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SeriesBookRow(
    book: SeriesBookDto,
    repository: FabulaRepository,
    onClick: () -> Unit
) {
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
            book.coverUrl?.let { repository.resolveUrl(it) }?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                book.position?.let {
                    Text(
                        "#$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
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
}
