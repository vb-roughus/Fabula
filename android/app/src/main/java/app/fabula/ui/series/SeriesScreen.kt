package app.fabula.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import app.fabula.data.SeriesSummaryDto
import app.fabula.ui.LocalContentBottomInset
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    repository: FabulaRepository,
    onMenuClick: () -> Unit,
    onSeriesClick: (Int) -> Unit
) {
    var series by remember { mutableStateOf<List<SeriesSummaryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val api = repository.apiOrNull()
            if (api == null) error = "Kein Server konfiguriert."
            else series = api.listSeries()
        } catch (t: Throwable) { error = t.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Serien") },
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
        Box(modifier = Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                series == null -> CircularProgressIndicator()
                series!!.isEmpty() -> Text("Noch keine Serien erkannt.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = 12.dp + LocalContentBottomInset.current.calculateBottomPadding()
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items = series!!, key = { it.id }) { s ->
                        SeriesCard(series = s, repository = repository, onClick = { onSeriesClick(s.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(
    series: SeriesSummaryDto,
    repository: FabulaRepository,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            repository.coverUrl(series)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                series.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            Text(
                "${series.bookCount} Band${if (series.bookCount == 1) "" else "e"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
