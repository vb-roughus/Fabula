package app.fabula.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.BookDetailDto
import app.fabula.data.FabulaRepository
import app.fabula.data.HighlightDto
import app.fabula.data.UpdateHighlightRequest
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet listing every highlighted passage of a book. Lets the
 * user jump to one (start playback at its beginning), edit its description and
 * note, or delete it. Mirrors [BookmarkManagerSheet] but for ranges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightManagerSheet(
    bookId: Int,
    book: BookDetailDto?,
    repository: FabulaRepository,
    onDismiss: () -> Unit,
    onPlayHighlight: (HighlightDto) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val highlightsRevision by repository.highlightsRevision.collectAsState()
    var highlights by remember { mutableStateOf<List<HighlightDto>>(emptyList()) }

    LaunchedEffect(bookId, highlightsRevision) {
        runCatching {
            val api = repository.apiOrNull() ?: return@runCatching
            highlights = api.listHighlights(bookId)
        }
    }

    var editing by remember { mutableStateOf<HighlightDto?>(null) }
    var deleting by remember { mutableStateOf<HighlightDto?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                "Markierungen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            if (highlights.isEmpty()) {
                Text(
                    "Noch keine Markierungen für dieses Hörbuch.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(items = highlights, key = { it.id }) { h ->
                        HighlightManagerRow(
                            highlight = h,
                            chapterTitle = book?.let { resolveChapterTitle(it, h) },
                            onPlay = {
                                onPlayHighlight(h)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            },
                            onEdit = { editing = h },
                            onDelete = { deleting = h }
                        )
                    }
                }
            }
        }
    }

    editing?.let { current ->
        EditHighlightDialog(
            highlight = current,
            onDismiss = { editing = null },
            onSave = { newTitle, newNote ->
                editing = null
                scope.launch {
                    runCatching {
                        val api = repository.apiOrNull() ?: return@runCatching
                        api.updateHighlight(current.id, UpdateHighlightRequest(title = newTitle, note = newNote))
                        repository.bumpHighlightsRevision()
                    }
                }
            }
        )
    }

    deleting?.let { current ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Markierung löschen?") },
            text = {
                val range = formatRange(current)
                val label = current.title?.takeIf { it.isNotBlank() }
                Text(if (label == null) range else "$label  ($range)")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching {
                            val api = repository.apiOrNull() ?: return@runCatching
                            api.deleteHighlight(current.id)
                            repository.bumpHighlightsRevision()
                        }
                    }
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun HighlightManagerRow(
    highlight: HighlightDto,
    chapterTitle: String?,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Filled.Highlight,
            contentDescription = null,
            tint = HighlightColor
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRange(highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                if (!chapterTitle.isNullOrBlank()) {
                    Text(
                        " · $chapterTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }
            if (!highlight.title.isNullOrBlank()) {
                Text(
                    highlight.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
            }
            if (!highlight.note.isNullOrBlank()) {
                Text(
                    highlight.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3
                )
            }
            if (highlight.title.isNullOrBlank() && highlight.note.isNullOrBlank()) {
                Text(
                    "Ohne Beschreibung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontStyle = FontStyle.Italic
                )
            }
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Ab Markierung abspielen")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Bearbeiten") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Löschen") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun EditHighlightDialog(
    highlight: HighlightDto,
    onDismiss: () -> Unit,
    onSave: (title: String?, note: String?) -> Unit
) {
    var title by remember(highlight.id) { mutableStateOf(highlight.title ?: "") }
    var note by remember(highlight.id) { mutableStateOf(highlight.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Markierung bearbeiten") },
        text = {
            Column {
                Text(
                    formatRange(highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Beschreibung") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notiz") },
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title.trim().ifBlank { null }, note.trim().ifBlank { null })
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

private fun formatRange(h: HighlightDto): String =
    "${formatClock(parseTimeSpan(h.start))} – ${formatClock(parseTimeSpan(h.end))}"

private fun resolveChapterTitle(book: BookDetailDto, highlight: HighlightDto): String? {
    val probe = parseTimeSpan(highlight.start) + 0.010  // FP boundary tolerance
    return book.chapters.firstOrNull {
        probe >= parseTimeSpan(it.start) && probe < parseTimeSpan(it.end)
    }?.title
}
