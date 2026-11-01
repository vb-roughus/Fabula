package app.fabula.ui.book

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fabula.data.ApplyMetadataRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.MetadataCandidateDto
import app.fabula.data.MetadataFieldDto
import app.fabula.data.MetadataPreviewDto
import app.fabula.data.formatDurationHuman
import app.fabula.ui.LocalContentBottomInset
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looking a book up in Audible's catalogue and taking over chosen fields.
 * Admin-only; the menu entry that leads here is shown to admins alone.
 *
 * Two steps on one screen: the search with its results, and -- for the result
 * tapped -- a field-by-field comparison. Nothing is written until the button
 * on the second step; back from there returns to the results, not out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSearchScreen(
    bookId: Int,
    repository: FabulaRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var asin by rememberSaveable { mutableStateOf("") }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var bookDurationSec by remember { mutableStateOf<Double?>(null) }

    var searching by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<MetadataCandidateDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var preview by remember { mutableStateOf<MetadataPreviewDto?>(null) }
    var openingAsin by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var applying by remember { mutableStateOf(false) }
    var notTaken by remember { mutableStateOf<List<String>?>(null) }

    // Start from what the book already says: its title, its first author, and
    // an ASIN if the tags carried one -- which finds exactly the right record.
    LaunchedEffect(bookId) {
        val book = runCatching { repository.apiOrNull()?.getBook(bookId) }.getOrNull() ?: return@LaunchedEffect
        bookDurationSec = app.fabula.data.parseTimeSpan(book.duration)
        if (!prefilled) {
            title = book.title
            author = book.authors.firstOrNull().orEmpty()
            asin = book.asin.orEmpty()
            prefilled = true
        }
    }

    fun search() {
        focusManager.clearFocus()
        scope.launch {
            searching = true
            error = null
            try {
                val api = repository.apiOrNull() ?: throw IllegalStateException("Kein Server konfiguriert.")
                candidates = api.searchMetadata(
                    id = bookId,
                    title = title.trim().ifEmpty { null },
                    author = author.trim().ifEmpty { null },
                    asin = asin.trim().ifEmpty { null }
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.readableMessage()
            } finally {
                searching = false
            }
        }
    }

    fun open(candidate: MetadataCandidateDto) {
        scope.launch {
            openingAsin = candidate.asin
            error = null
            try {
                val api = repository.apiOrNull() ?: throw IllegalStateException("Kein Server konfiguriert.")
                val loaded = api.previewMetadata(bookId, candidate.asin)
                // Empty fields start ticked; anything that would overwrite
                // what the book has is the admin's to tick.
                selected = loaded.fields.filter { it.preselected }.map { it.key }.toSet()
                preview = loaded
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.readableMessage()
            } finally {
                openingAsin = null
            }
        }
    }

    fun apply(current: MetadataPreviewDto) {
        scope.launch {
            applying = true
            error = null
            try {
                val api = repository.apiOrNull() ?: throw IllegalStateException("Kein Server konfiguriert.")
                val result = api.applyMetadata(bookId, current.asin, ApplyMetadataRequest(selected.toList()))
                // The book screen, the library and the series lists reload on
                // this -- title, cover and series may all have changed.
                repository.bumpSeriesRevision()
                if (result.skipped.isEmpty()) {
                    onBack()
                } else {
                    notTaken = result.skipped.map { key ->
                        current.fields.firstOrNull { it.key == key }?.label ?: key
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.readableMessage()
            } finally {
                applying = false
            }
        }
    }

    BackHandler(enabled = preview != null) { preview = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (preview == null) "Metadaten suchen" else "Felder übernehmen") },
                navigationIcon = {
                    IconButton(onClick = { if (preview != null) preview = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        val bottomInset = LocalContentBottomInset.current.calculateBottomPadding()
        val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + bottomInset)
        val current = preview
        if (current == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item("form") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Titel") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text("Autor") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = asin,
                            onValueChange = { asin = it },
                            label = { Text("ASIN (optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Mit einer ASIN wird genau dieser Audible-Eintrag geladen, sonst wird nach " +
                                "Titel und Autor gesucht. Treffer mit passender Laufzeit stehen oben.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Button(
                            enabled = !searching && (title.isNotBlank() || author.isNotBlank() || asin.isNotBlank()),
                            onClick = { search() }
                        ) { Text(if (searching) "Suche…" else "Bei Audible suchen") }
                    }
                }
                error?.let { message ->
                    item("error") {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                candidates?.let { found ->
                    if (found.isEmpty()) {
                        item("empty") {
                            Text(
                                "Nichts gefunden. Ein kürzerer Titel oder ohne Autor findet oft mehr.",
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    items(found, key = { it.asin }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            opening = openingAsin == candidate.asin,
                            onClick = { if (openingAsin == null) open(candidate) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item("header") { PreviewHeader(current, bookDurationSec) }
                item("hint") {
                    Text(
                        "Leere Felder sind angehakt. Was das Buch schon hat, wird nur ersetzt, wenn du es " +
                            "anhakst. Übernommene Felder bleiben auch bei einem neuen Scan erhalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (current.fields.isEmpty()) {
                    item("nothing") {
                        Text("Dieser Eintrag bringt nichts Neues.", color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(current.fields, key = { it.key }) { field ->
                    FieldRow(
                        field = field,
                        checked = field.key in selected,
                        resolve = { url -> if (url.startsWith("/")) repository.resolveUrl(url) else url },
                        onToggle = {
                            selected = if (field.key in selected) selected - field.key else selected + field.key
                        }
                    )
                }
                error?.let { message ->
                    item("error") {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("apply") {
                    Button(
                        enabled = selected.isNotEmpty() && !applying,
                        onClick = { apply(current) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                applying -> "Wird übernommen…"
                                selected.size == 1 -> "1 Feld übernehmen"
                                else -> "${selected.size} Felder übernehmen"
                            }
                        )
                    }
                }
            }
        }
    }

    notTaken?.let { labels ->
        AlertDialog(
            onDismissRequest = { notTaken = null; onBack() },
            title = { Text("Teilweise übernommen") },
            text = {
                Text(
                    "Nicht übernommen: ${labels.joinToString(", ")}. Audible hatte dafür nichts, " +
                        "oder das Cover liess sich nicht laden."
                )
            },
            confirmButton = {
                TextButton(onClick = { notTaken = null; onBack() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun CandidateRow(
    candidate: MetadataCandidateDto,
    opening: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Cover(url = candidate.coverUrl, size = 64)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                candidate.title ?: candidate.asin,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            candidate.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (candidate.authors.isNotEmpty()) {
                Text(candidate.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            if (candidate.narrators.isNotEmpty()) {
                Text(
                    "Gelesen von ${candidate.narrators.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            candidate.series?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(
                        candidate.runtimeMinutes?.let { formatDurationHuman(it * 60.0) },
                        candidate.year?.toString()
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (candidate.runtimeMatches) RuntimeBadge()
            }
        }
        if (opening) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun RuntimeBadge() {
    Text(
        "Laufzeit passt",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun PreviewHeader(preview: MetadataPreviewDto, bookDurationSec: Double?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(url = preview.coverUrl, size = 72)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                preview.title ?: preview.asin,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            preview.runtimeMinutes?.let { minutes ->
                Text(
                    buildString {
                        append("Audible: ${formatDurationHuman(minutes * 60.0)}")
                        bookDurationSec?.let { append(" · Dieses Buch: ${formatDurationHuman(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            when {
                preview.runtimeMatches -> RuntimeBadge()
                // Not proof of the wrong edition -- but worth a second look
                // before overwriting anything.
                preview.runtimeMinutes != null && bookDurationSec != null -> Text(
                    "Laufzeit weicht ab – evtl. andere Ausgabe",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FieldRow(
    field: MetadataFieldDto,
    checked: Boolean,
    resolve: (String) -> String?,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (field.key == "cover") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(url = field.current?.let(resolve), size = 72)
                        Text("Bisher", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(url = field.proposed?.let(resolve), size = 72)
                        Text("Neu", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                Text(
                    field.proposed.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (field.key == "description") 6 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    field.current?.let { "Bisher: $it" } ?: "Bisher leer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Cover(url: String?, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp)
            )
        } else {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * The server's own explanation where it gave one -- "Audible ist nicht
 * erreichbar." says more than "HTTP 502".
 */
private fun Throwable.readableMessage(): String {
    if (this is retrofit2.HttpException) {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val serverText = body?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        }
        return serverText ?: when (code()) {
            403 -> "Nur für Admins."
            404 -> "Nicht gefunden."
            else -> "Fehler ${code()}"
        }
    }
    return message ?: "Unbekannter Fehler"
}
