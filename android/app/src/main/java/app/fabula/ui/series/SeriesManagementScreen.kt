package app.fabula.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.FabulaRepository
import app.fabula.data.SeriesRequest
import app.fabula.data.SeriesSummaryDto
import app.fabula.ui.LocalContentBottomInset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesManagementScreen(
    repository: FabulaRepository,
    onBack: () -> Unit
) {
    var series by remember { mutableStateOf<List<SeriesSummaryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SeriesSummaryDto?>(null) }
    val scope = rememberCoroutineScope()
    val seriesRevision by repository.seriesRevision.collectAsState()

    LaunchedEffect(seriesRevision) {
        runCatching {
            val api = repository.apiOrNull() ?: run {
                error = "Kein Server konfiguriert."
                return@runCatching
            }
            series = api.listSeries()
            error = null
        }.onFailure { error = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Serien verwalten") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = LocalContentBottomInset.current.calculateBottomPadding() + 16.dp
            )
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Text(
                        "Neue Serie anlegen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it },
                        label = { Text("Beschreibung (optional)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !creating && newName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                creating = true
                                runCatching {
                                    val api = repository.apiOrNull() ?: return@runCatching
                                    api.createSeries(
                                        SeriesRequest(
                                            name = newName.trim(),
                                            description = newDescription.trim().ifBlank { null }
                                        )
                                    )
                                    repository.bumpSeriesRevision()
                                    newName = ""
                                    newDescription = ""
                                }.onFailure { error = it.message }
                                creating = false
                            }
                        }
                    ) { Text(if (creating) "Lege an..." else "Anlegen") }
                }
            }

            error?.let { msg ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error)
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Vorhandene Serien",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
            }

            val list = series
            if (list == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (list.isEmpty()) {
                item {
                    Text(
                        "Noch keine Serien.",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                items(items = list, key = { it.id }) { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Medium)
                            s.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2
                                )
                            }
                            Text(
                                "${s.bookCount} ${if (s.bookCount == 1) "Hörbuch" else "Hörbücher"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { editing = s }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val api = repository.apiOrNull() ?: return@runCatching
                                    api.deleteSeries(s.id)
                                    repository.bumpSeriesRevision()
                                }.onFailure { error = it.message }
                            }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Löschen",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    val target = editing
    if (target != null) {
        EditSeriesDialog(
            series = target,
            onDismiss = { editing = null },
            onSave = { name, description ->
                scope.launch {
                    runCatching {
                        val api = repository.apiOrNull() ?: return@runCatching
                        api.updateSeries(
                            target.id,
                            SeriesRequest(name = name, description = description)
                        )
                        repository.bumpSeriesRevision()
                    }.onFailure { error = it.message }
                    editing = null
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSeriesDialog(
    series: SeriesSummaryDto,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf(series.name) }
    var description by remember { mutableStateOf(series.description.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Serie bearbeiten") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), description.trim().ifBlank { null }) }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

