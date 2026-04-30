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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import app.fabula.data.BookmarkDto
import app.fabula.data.FabulaRepository
import app.fabula.data.UpdateBookmarkRequest
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import kotlinx.coroutines.launch

/**
 * Modal bottom sheet that lists every bookmark of a book and lets the user
 * jump to one (start playback there), edit its note, or delete it.
 *
 * The sheet drives all server calls itself -- its caller only has to wire
 * up the "play this bookmark" action because that needs access to the
 * PlayerController.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkManagerSheet(
    bookId: Int,
    book: BookDetailDto?,
    repository: FabulaRepository,
    onDismiss: () -> Unit,
    onPlayBookmark: (BookmarkDto) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val bookmarksRevision by repository.bookmarksRevision.collectAsState()
    var bookmarks by remember { mutableStateOf<List<BookmarkDto>>(emptyList()) }

    LaunchedEffect(bookId, bookmarksRevision) {
        runCatching {
            val api = repository.apiOrNull() ?: return@runCatching
            bookmarks = api.listBookmarks(bookId)
        }
    }

    var editing by remember { mutableStateOf<BookmarkDto?>(null) }
    var deleting by remember { mutableStateOf<BookmarkDto?>(null) }

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
                "Lesezeichen verwalten",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    "Noch keine Lesezeichen für dieses Hörbuch.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                    items(items = bookmarks, key = { it.id }) { b ->
                        BookmarkManagerRow(
                            bookmark = b,
                            chapterTitle = book?.let { resolveChapterTitle(it, b) },
                            onPlay = {
                                onPlayBookmark(b)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            },
                            onEdit = { editing = b },
                            onDelete = { deleting = b }
                        )
                    }
                }
            }
        }
    }

    editing?.let { current ->
        EditBookmarkDialog(
            bookmark = current,
            onDismiss = { editing = null },
            onSave = { newNote ->
                editing = null
                scope.launch {
                    runCatching {
                        val api = repository.apiOrNull() ?: return@runCatching
                        api.updateBookmark(current.id, UpdateBookmarkRequest(note = newNote))
                        repository.bumpBookmarksRevision()
                    }
                }
            }
        )
    }

    deleting?.let { current ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Lesezeichen löschen?") },
            text = {
                val pos = formatClock(parseTimeSpan(current.position))
                val noteText = current.note?.takeIf { it.isNotBlank() }
                Text(if (noteText == null) "Position $pos" else "$noteText  ($pos)")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching {
                            val api = repository.apiOrNull() ?: return@runCatching
                            api.deleteBookmark(current.id)
                            repository.bumpBookmarksRevision()
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
private fun BookmarkManagerRow(
    bookmark: BookmarkDto,
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
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClock(parseTimeSpan(bookmark.position)),
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
            if (!bookmark.note.isNullOrBlank()) {
                Text(
                    bookmark.note,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
            } else {
                Text(
                    "Ohne Notiz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontStyle = FontStyle.Italic
                )
            }
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Abspielen")
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
private fun EditBookmarkDialog(
    bookmark: BookmarkDto,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var note by remember(bookmark.id) { mutableStateOf(bookmark.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lesezeichen bearbeiten") },
        text = {
            Column {
                Text(
                    "Position: ${formatClock(parseTimeSpan(bookmark.position))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notiz") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.trim().ifBlank { null }) }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

private fun resolveChapterTitle(book: BookDetailDto, bookmark: BookmarkDto): String? {
    val pos = parseTimeSpan(bookmark.position)
    return book.chapters.firstOrNull {
        pos >= parseTimeSpan(it.start) && pos < parseTimeSpan(it.end)
    }?.title
}
