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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fabula.data.BookDetailDto
import app.fabula.data.BookmarkDto
import app.fabula.data.ChapterDto
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.formatClock
import app.fabula.data.formatDurationHuman
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
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
    var bookmarks by remember { mutableStateOf<List<BookmarkDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var addBookmarkOpen by remember { mutableStateOf(false) }
    var bookmarkNote by remember { mutableStateOf("") }
    var hasAutoScrolled by remember(bookId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val playerState by player.state.collectAsState()
    val bookmarksRevision by repository.bookmarksRevision.collectAsState()
    val scope = rememberCoroutineScope()

    // Position the bookmark would be saved at: current playback position when
    // playing this book, otherwise the saved progress on the book itself
    // (falls back to 0 when neither is available).
    val pendingBookmarkPosition: Double = when {
        playerState.book?.id == bookId -> playerState.positionInBook
        else -> book?.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    }

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

    LaunchedEffect(bookId, bookmarksRevision) {
        runCatching {
            val api = repository.apiOrNull() ?: return@runCatching
            bookmarks = api.listBookmarks(bookId)
        }
    }

    LaunchedEffect(book?.id) {
        val current = book ?: return@LaunchedEffect
        if (playerState.book?.id != current.id) {
            player.loadBook(current)
        }
    }

    // Auto-scroll the chapter list to wherever playback left off (or where it
    // is right now if this book is the active one). Runs once per book id;
    // re-keys when bookId changes via the `remember(bookId)` above.
    LaunchedEffect(book?.id, bookmarks.size, playerState.book?.id, playerState.currentChapter?.index) {
        if (hasAutoScrolled) return@LaunchedEffect
        val current = book ?: return@LaunchedEffect
        if (current.chapters.isEmpty()) return@LaunchedEffect

        val activeIdx: Int? = if (playerState.book?.id == current.id) {
            playerState.currentChapter?.index
        } else {
            val pos = current.progress?.let { parseTimeSpan(it.position) } ?: 0.0
            if (pos > 1.0) {
                current.chapters.indexOfFirst { c ->
                    pos >= parseTimeSpan(c.start) && pos < parseTimeSpan(c.end)
                }.takeIf { it >= 0 }
            } else null
        }

        if (activeIdx == null || activeIdx <= 0) {
            hasAutoScrolled = true
            return@LaunchedEffect
        }

        // LazyColumn structure: cover, title, brand+duration, action,
        // (optional description), (optional bookmarks header + bookmarks),
        // chapter header, chapters[N], spacer.
        var target = 4
        if (!current.description.isNullOrBlank()) target += 1
        if (bookmarks.isNotEmpty()) target += 1 + bookmarks.size
        target += 1  // chapter header
        target += activeIdx

        listState.scrollToItem(target)
        hasAutoScrolled = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { moreMenuOpen = true }) {
                            Icon(Icons.Filled.MoreHoriz, contentDescription = "Mehr")
                        }
                        DropdownMenu(
                            expanded = moreMenuOpen,
                            onDismissRequest = { moreMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Lesezeichen hier setzen") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Bookmark, contentDescription = null)
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    bookmarkNote = ""
                                    addBookmarkOpen = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
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
                    bookmarks = bookmarks,
                    listState = listState,
                    onPlay = {
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            if (playerState.isPlaying && playerState.book?.id == b.id) {
                                player.pause()
                            } else {
                                player.play()
                                onPlaybackStarted()
                            }
                        }
                    },
                    onChapterClick = { chapter ->
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            player.jumpToChapter(chapter)
                            player.play()
                            onPlaybackStarted()
                        }
                    },
                    onBookmarkClick = { bookmark ->
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            player.seekInBook(parseTimeSpan(bookmark.position))
                            player.play()
                            onPlaybackStarted()
                        }
                    },
                    onBookmarkDelete = { bookmark ->
                        scope.launch {
                            runCatching {
                                val api = repository.apiOrNull() ?: return@runCatching
                                api.deleteBookmark(bookmark.id)
                                repository.bumpBookmarksRevision()
                            }
                        }
                    }
                )
            }
        }
    }

    if (addBookmarkOpen) {
        AlertDialog(
            onDismissRequest = { addBookmarkOpen = false },
            title = { Text("Lesezeichen hinzufügen") },
            text = {
                Column {
                    Text(
                        "Position: ${formatClock(pendingBookmarkPosition)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bookmarkNote,
                        onValueChange = { bookmarkNote = it },
                        label = { Text("Notiz (optional)") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val note = bookmarkNote.trim().ifBlank { null }
                    val pos = pendingBookmarkPosition
                    addBookmarkOpen = false
                    bookmarkNote = ""
                    scope.launch {
                        runCatching {
                            val api = repository.apiOrNull() ?: return@runCatching
                            api.createBookmark(
                                bookId,
                                CreateBookmarkRequest(
                                    position = toTimeSpanString(pos),
                                    note = note
                                )
                            )
                            repository.bumpBookmarksRevision()
                        }
                    }
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { addBookmarkOpen = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun BookContent(
    book: BookDetailDto,
    repository: FabulaRepository,
    isCurrent: Boolean,
    isPlaying: Boolean,
    currentChapterIndex: Int?,
    bookmarks: List<BookmarkDto>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: () -> Unit,
    onChapterClick: (ChapterDto) -> Unit,
    onBookmarkClick: (BookmarkDto) -> Unit,
    onBookmarkDelete: (BookmarkDto) -> Unit
) {
    val totalSeconds = parseTimeSpan(book.duration)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = app.fabula.ui.LocalContentBottomInset.current.calculateBottomPadding()
        )
    ) {
        item {
            // Cover block, centered, large.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 8.dp)
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
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 3
                )
                book.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            // "Fabula" brand row + author
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append("Fabula")
                        if (book.authors.isNotEmpty()) {
                            append(" · ")
                            append(book.authors.joinToString(", "))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildString {
                        append(formatDurationHuman(totalSeconds))
                        if (book.chapters.isNotEmpty()) {
                            append(" · ${book.chapters.size} Kapitel")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        item {
            ActionRow(onPlay = onPlay, isPlaying = isCurrent && isPlaying)
        }

        if (!book.description.isNullOrBlank()) {
            item {
                Text(
                    book.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }

        if (bookmarks.isNotEmpty()) {
            item {
                Text(
                    "Lesezeichen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            items(items = bookmarks, key = { "bookmark-${it.id}" }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    chapterTitle = chapterAt(book, parseTimeSpan(bookmark.position))?.title,
                    onClick = { onBookmarkClick(bookmark) },
                    onDelete = { onBookmarkDelete(bookmark) }
                )
            }
        }

        if (book.chapters.isNotEmpty()) {
            item {
                Text(
                    "Kapitel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            items(items = book.chapters, key = { "chapter-${it.index}" }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isActive = isCurrent && currentChapterIndex == chapter.index,
                    onClick = { onChapterClick(chapter) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun chapterAt(book: BookDetailDto, posSec: Double): ChapterDto? =
    book.chapters.firstOrNull {
        posSec >= parseTimeSpan(it.start) && posSec < parseTimeSpan(it.end)
    }

@Composable
private fun ActionRow(onPlay: () -> Unit, isPlaying: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { /* bookmark placeholder */ }) {
            Icon(
                Icons.Filled.BookmarkBorder,
                contentDescription = "Lesezeichen",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = { /* download placeholder */ }, enabled = false) {
            Icon(
                Icons.Filled.Download,
                contentDescription = "Herunterladen",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = { /* more placeholder */ }) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "Mehr",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Abspielen",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterDto,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val chapterDurationSec = parseTimeSpan(chapter.end) - parseTimeSpan(chapter.start)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${chapter.index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(32.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1
                )
                Text(
                    formatClock(chapterDurationSec.coerceAtLeast(0.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        IconButton(onClick = { /* chapter menu placeholder */ }) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "Mehr",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: BookmarkDto,
    chapterTitle: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val posSec = parseTimeSpan(bookmark.position)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(28.dp)
                .padding(end = 4.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClock(posSec),
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Lesezeichen löschen",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
