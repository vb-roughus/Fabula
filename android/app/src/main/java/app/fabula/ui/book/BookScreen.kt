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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fabula.FabulaApp
import app.fabula.data.AssignSeriesRequest
import app.fabula.data.BookDetailDto
import app.fabula.data.BookDownloadState
import app.fabula.data.BookmarkDto
import app.fabula.data.ChapterDto
import app.fabula.data.DownloadStatus
import app.fabula.data.FabulaRepository
import app.fabula.data.HighlightDto
import app.fabula.data.SeriesSummaryDto
import app.fabula.data.SetFinishedRequest
import app.fabula.data.UpdateProgressRequest
import app.fabula.data.formatClock
import app.fabula.data.formatDurationHuman
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import app.fabula.player.PlayerController
import app.fabula.ui.player.BookmarkManagerSheet
import app.fabula.ui.player.HighlightColor
import app.fabula.ui.player.HighlightManagerSheet
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
    var highlights by remember { mutableStateOf<List<HighlightDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var addBookmarkOpen by remember { mutableStateOf(false) }
    var bookmarkNote by remember { mutableStateOf("") }
    var assignSeriesOpen by remember { mutableStateOf(false) }
    var bookmarkManagerOpen by remember { mutableStateOf(false) }
    var highlightManagerOpen by remember { mutableStateOf(false) }
    val highlightsRevision by repository.highlightsRevision.collectAsState()
    var resetProgressConfirmOpen by remember { mutableStateOf(false) }
    var seriesList by remember { mutableStateOf<List<SeriesSummaryDto>>(emptyList()) }
    val seriesRevision by repository.seriesRevision.collectAsState()
    var hasAutoScrolled by remember(bookId) { mutableStateOf(false) }
    // True once the server copy has arrived -- or the attempt has finished
    // without one, which is the normal offline case. `book` is filled in twice:
    // first from the cached manifest, then from the server. The manifest's
    // progress is only as fresh as the last online visit to this screen (and is
    // absent entirely for a book downloaded before it was ever started), so the
    // one-shots below must not spend themselves on that first value.
    var resumeSettled by remember(bookId) { mutableStateOf(false) }
    // Chapter page-flip intro: play once per book open, landing on the resume
    // chapter. `introHandled` is saveable so it doesn't replay after process
    // death / restore; `showFlipIntro` drives the visible overlay.
    val flipIntroEnabled by repository.chapterFlipIntroEnabled.collectAsState(initial = true)
    var introHandled by rememberSaveable(bookId) { mutableStateOf(false) }
    var showFlipIntro by remember(bookId) { mutableStateOf(false) }
    var flipTargetIndex by remember(bookId) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val playerState by player.state.collectAsState()
    val bookmarksRevision by repository.bookmarksRevision.collectAsState()
    val scope = rememberCoroutineScope()

    val app = LocalContext.current.applicationContext as FabulaApp
    val offlineStore = app.offlineStore
    val downloads = app.downloadManager
    val downloadStates by downloads.states.collectAsState()
    val downloadState = downloadStates[bookId]
    val downloadedFileIds by offlineStore.downloadedFileIds.collectAsState()
    val isAdmin by repository.isAdmin.collectAsState(initial = false)
    val progressStore = app.progressStore
    val progressRevision by progressStore.revision.collectAsState()
    val uploadSyncer = app.uploadSyncer
    val pendingUploads = app.pendingUploads
    var cancelDownloadConfirmOpen by remember { mutableStateOf(false) }
    var deleteDownloadConfirmOpen by remember { mutableStateOf(false) }

    // local() is a plain read, not a flow -- keying the remember on the store's
    // revision is what makes this pick up a progress write.
    val localProgress = remember(bookId, progressRevision) { progressStore.local(bookId) }

    // Where playback would continue. Prefers an unsynced local entry over the
    // server value -- the same rule loadBook applies -- so the scroll, the
    // flip intro and the chapter highlight all point at the position that will
    // actually be resumed.
    // Remembered rather than recomputed: playerState ticks several times a
    // second while playing, and scanning the chapter list parses a TimeSpan per
    // entry.
    val activeBookId = playerState.book?.id
    val activeChapter = playerState.currentChapter?.index
    val resumeIndex: Int? = remember(book, localProgress, activeBookId, activeChapter) {
        book?.let { b ->
            val stored = when {
                localProgress != null && !localProgress.synced -> localProgress.positionSec
                else -> b.progress?.let { parseTimeSpan(it.position) }
                    ?: localProgress?.positionSec ?: 0.0
            }
            resumeChapterIndex(
                book = b,
                isActiveBook = activeBookId == b.id,
                activeChapterIndex = activeChapter,
                storedPositionSec = stored
            )
        }
    }

    // Position the bookmark would be saved at: current playback position when
    // playing this book, otherwise the saved progress on the book itself
    // (falls back to 0 when neither is available).
    val pendingBookmarkPosition: Double = when {
        playerState.book?.id == bookId -> playerState.positionInBook
        else -> book?.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    }

    LaunchedEffect(bookId, seriesRevision) {
        // Paint the downloaded copy first: without this a fully synced book
        // could not even be opened offline, which would defeat the feature.
        val cached = offlineStore.cachedBook(bookId)
        if (cached != null && book == null) {
            book = cached
            downloads.refreshFromDisk(cached)
        }
        try {
            val api = repository.apiOrNull() ?: run {
                if (book == null) error = "Kein Server konfiguriert."
                resumeSettled = true
                return@LaunchedEffect
            }
            val fresh = api.getBook(bookId)
            book = fresh
            offlineStore.refreshCachedBook(fresh)
            downloads.refreshFromDisk(fresh)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c  // effect cancelled (navigation) -- not an error
        } catch (t: Throwable) {
            // Offline with a cached copy is a normal state, not an error.
            if (book == null) error = t.message
        }
        // Either the fresh copy is in or there won't be one; the resume
        // position is now as good as it gets.
        resumeSettled = true
    }

    // Pending entries are appended to whatever the server returns, so one
    // created offline shows up straight away instead of looking like it was
    // dropped. They carry negative ids, which is how the rows tell them apart.
    LaunchedEffect(bookId, bookmarksRevision) {
        val server = runCatching {
            val api = repository.apiOrNull() ?: return@runCatching emptyList()
            api.listBookmarks(bookId)
        }.getOrDefault(emptyList())
        bookmarks = server + pendingUploads.bookmarksFor(bookId).map { it.toDto() }
    }

    LaunchedEffect(bookId, highlightsRevision) {
        val server = runCatching {
            val api = repository.apiOrNull() ?: return@runCatching emptyList()
            api.listHighlights(bookId)
        }.getOrDefault(emptyList())
        highlights = server + pendingUploads.highlightsFor(bookId).map { it.toDto() }
    }

    // Pre-load the series list when the assign dialog is opened.
    LaunchedEffect(assignSeriesOpen, seriesRevision) {
        if (assignSeriesOpen) {
            runCatching {
                val api = repository.apiOrNull() ?: return@runCatching
                seriesList = api.listSeries()
            }
        }
    }

    // Refresh the player's media items when THIS book is the one already
    // playing and its file ids changed underneath us: a server rescan deletes
    // and re-creates the AudioFile rows, so the loaded MediaItems would point
    // at /api/stream/<oldId> and 404.
    //
    // Deliberately does NOT adopt the book just because its page was opened --
    // that would stop whatever is currently playing. Browsing has to leave
    // playback alone; the play button and the chapter/bookmark rows load the
    // book themselves when the user actually asks for it.
    val fileIds = book?.files?.map { it.id }
    LaunchedEffect(book?.id, fileIds) {
        val current = book ?: return@LaunchedEffect
        val active = playerState.book ?: return@LaunchedEffect
        if (active.id != current.id) return@LaunchedEffect
        if (active.files.map { it.id } != current.files.map { it.id }) {
            player.loadBook(current)
        }
    }

    // Auto-scroll the chapter list to wherever playback left off (or where it
    // is right now if this book is the active one). Runs once per book id;
    // re-keys when bookId changes via the `remember(bookId)` above.
    LaunchedEffect(book?.id, bookmarks.size, resumeIndex, resumeSettled) {
        if (hasAutoScrolled) return@LaunchedEffect
        val current = book ?: return@LaunchedEffect
        if (current.chapters.isEmpty()) return@LaunchedEffect
        // "Nothing to resume yet" is not "nothing to resume". Returning without
        // setting the flag leaves this armed for the fresh copy; spending it on
        // the cached manifest is what stopped the list scrolling at all.
        if (!resumeSettled) return@LaunchedEffect

        val chapterIdx = resumeIndex
        if (chapterIdx == null || chapterIdx <= 0) {
            hasAutoScrolled = true
            return@LaunchedEffect
        }

        // LazyColumn structure: cover, title, brand+duration, action,
        // (optional description), (optional bookmarks section header
        // -- collapsed by default so it only contributes one item),
        // chapter header, chapters[N], spacer.
        var target = 4
        if (!current.description.isNullOrBlank()) target += 1
        if (bookmarks.isNotEmpty()) target += 1
        target += 1  // chapter header
        target += chapterIdx

        listState.scrollToItem(target)
        hasAutoScrolled = true
    }

    // Kick off the chapter page-flip intro once the book has loaded. It lands on
    // the same chapter the list scrolls to and the row highlights -- a flip that
    // stopped somewhere else would read as a bug.
    LaunchedEffect(book?.id, flipIntroEnabled, resumeIndex, resumeSettled) {
        if (introHandled) return@LaunchedEffect
        val current = book ?: return@LaunchedEffect
        // Same reason as the auto-scroll: landing the flip on the cached
        // manifest's idea of the position would put it on chapter one.
        if (!resumeSettled) return@LaunchedEffect
        introHandled = true
        if (!flipIntroEnabled || current.chapters.isEmpty()) return@LaunchedEffect

        flipTargetIndex = (resumeIndex ?: 0).coerceIn(0, current.chapters.lastIndex)
        showFlipIntro = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            // The sync status lives in the app bar rather than as a LazyColumn
            // item: it stays visible while scrolling the chapter list, and it
            // leaves BookContent's item count -- which the auto-scroll below
            // counts by hand -- untouched.
            Column {
            TopAppBar(
                title = {
                    val s = downloadState
                    if (s != null && s.status != DownloadStatus.Complete) {
                        Text(
                            text = downloadStatusLabel(s),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.status == DownloadStatus.Failed)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                },
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
                            DropdownMenuItem(
                                text = { Text("Lesezeichen verwalten") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    bookmarkManagerOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Markierungen verwalten") },
                                leadingIcon = {
                                    Icon(Icons.Filled.BorderColor, contentDescription = null)
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    highlightManagerOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (book?.progress?.finished == true) "Als ungehört markieren"
                                        else "Als gehört markieren"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (book?.progress?.finished == true)
                                            Icons.Outlined.RemoveDone
                                        else Icons.Filled.DoneAll,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    val current = book ?: return@DropdownMenuItem
                                    val next = current.progress?.finished != true
                                    // Keep the player's local finished flag
                                    // in sync, otherwise the next 4s auto-save
                                    // would overwrite the server value.
                                    player.setFinishedFlag(current.id, next)
                                    // Also record it locally, so the change
                                    // survives a failed request and is handed
                                    // over on the next reconnect.
                                    player.recordExplicitProgress(
                                        bookId = current.id,
                                        positionSec = if (next) parseTimeSpan(current.duration)
                                            else pendingBookmarkPosition,
                                        finished = next
                                    )
                                    scope.launch {
                                        runCatching {
                                            val api = repository.apiOrNull() ?: return@runCatching
                                            api.setFinished(
                                                current.id,
                                                SetFinishedRequest(next, repository.deviceId())
                                            )
                                        }
                                        // Refresh the book so the menu label
                                        // and any inline progress UI update.
                                        runCatching {
                                            val api = repository.apiOrNull() ?: return@runCatching
                                            book = api.getBook(current.id)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Fortschritt zurücksetzen") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    resetProgressConfirmOpen = true
                                }
                            )
                            // Admin-only: the assignment is catalogue-wide.
                            if (isAdmin) DropdownMenuItem(
                                text = {
                                    Text(
                                        if (book?.series.isNullOrBlank()) "Serie zuweisen"
                                        else "Serie ändern"
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.LibraryBooks, contentDescription = null)
                                },
                                onClick = {
                                    moreMenuOpen = false
                                    assignSeriesOpen = true
                                }
                            )
                            if (downloadState != null) {
                                DropdownMenuItem(
                                    text = { Text("Downloads entfernen") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        moreMenuOpen = false
                                        deleteDownloadConfirmOpen = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            val running = downloadState?.takeIf {
                it.status == DownloadStatus.Running || it.status == DownloadStatus.Paused
            }
            if (running != null) {
                LinearProgressIndicator(
                    progress = { running.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
            }
        },
        containerColor = Color.Transparent,
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
                    resumeChapterIndex = resumeIndex,
                    bookmarks = bookmarks,
                    highlights = highlights,
                    downloadState = downloadState,
                    downloadedFileIds = downloadedFileIds,
                    onDownloadClick = {
                        when (downloadState?.status) {
                            null -> downloads.enqueue(b)
                            DownloadStatus.Complete -> deleteDownloadConfirmOpen = true
                            DownloadStatus.Queued,
                            DownloadStatus.Running,
                            DownloadStatus.Paused -> cancelDownloadConfirmOpen = true
                            // Interrupted or failed: pick up where we stopped.
                            DownloadStatus.Partial,
                            DownloadStatus.Failed -> downloads.enqueue(b)
                        }
                    },
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
                    // Same dialog as the app bar entry, so the note can be
                    // filled in before saving.
                    onAddBookmark = {
                        bookmarkNote = ""
                        addBookmarkOpen = true
                    },
                    onChapterClick = { chapter ->
                        scope.launch {
                            if (playerState.book?.id != b.id) player.loadBook(b)
                            player.jumpToChapter(chapter)
                            player.play()
                            onPlaybackStarted()
                        }
                    },
                    onChapterBookmark = { chapter ->
                        // Queued like every other bookmark, so it survives
                        // being offline.
                        uploadSyncer.createBookmark(
                            bookId = b.id,
                            position = chapter.start,
                            note = chapter.title.takeIf { it.isNotBlank() }
                        )
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
                        if (bookmark.id < 0) {
                            // Never reached the server; drop it from the queue.
                            pendingUploads.removeBookmark(bookmark.id)
                            repository.bumpBookmarksRevision()
                        } else {
                            scope.launch {
                                runCatching {
                                    val api = repository.apiOrNull() ?: return@runCatching
                                    api.deleteBookmark(bookmark.id)
                                    repository.bumpBookmarksRevision()
                                }
                            }
                        }
                    }
                )
            }
        }
    }

        if (showFlipIntro) {
            book?.let { b ->
                ChapterFlipIntro(
                    coverUrl = repository.coverUrl(b),
                    chapters = b.chapters,
                    targetIndex = flipTargetIndex,
                    onFinished = { showFlipIntro = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (cancelDownloadConfirmOpen) {
        AlertDialog(
            onDismissRequest = { cancelDownloadConfirmOpen = false },
            title = { Text("Download abbrechen?") },
            text = {
                Text(
                    "Bereits geladene Tracks bleiben erhalten. Du kannst später " +
                    "an derselben Stelle fortsetzen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cancelDownloadConfirmOpen = false
                    downloads.cancel(bookId)
                }) { Text("Abbrechen") }
            },
            dismissButton = {
                TextButton(onClick = { cancelDownloadConfirmOpen = false }) { Text("Weiter laden") }
            }
        )
    }

    if (deleteDownloadConfirmOpen) {
        AlertDialog(
            onDismissRequest = { deleteDownloadConfirmOpen = false },
            title = { Text("Downloads entfernen?") },
            text = {
                Text(
                    "Die heruntergeladenen Dateien werden gelöscht. Das Hörbuch " +
                    "bleibt auf dem Server und lässt sich weiterhin streamen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteDownloadConfirmOpen = false
                    downloads.remove(bookId)
                }) { Text("Entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDownloadConfirmOpen = false }) { Text("Behalten") }
            }
        )
    }

    if (resetProgressConfirmOpen) {
        AlertDialog(
            onDismissRequest = { resetProgressConfirmOpen = false },
            title = { Text("Fortschritt zurücksetzen?") },
            text = {
                Text(
                    "Die gespeicherte Hörposition wird auf den Anfang " +
                    "zurückgesetzt. Lesezeichen bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = book ?: return@TextButton
                    resetProgressConfirmOpen = false
                    scope.launch {
                        runCatching {
                            val api = repository.apiOrNull() ?: return@runCatching
                            api.saveProgress(
                                current.id,
                                UpdateProgressRequest(
                                    position = "00:00:00",
                                    finished = false,
                                    device = repository.deviceId()
                                )
                            )
                        }
                        // If this book is the one in the player, rewind it
                        // too so the UI doesn't snap back on the next save,
                        // and clear the finished flag explicitly (a fresh
                        // load wouldn't run otherwise).
                        if (playerState.book?.id == current.id) {
                            player.seekInBook(0.0)
                            player.setFinishedFlag(current.id, false)
                        }
                        // Record the reset locally too; otherwise an unsynced
                        // local entry would resurrect the old position.
                        player.recordExplicitProgress(current.id, 0.0, false)
                        // Refresh the book so the inline progress UI and the
                        // "Als gehört markieren" toggle reflect the new state.
                        runCatching {
                            val api = repository.apiOrNull() ?: return@runCatching
                            book = api.getBook(current.id)
                        }
                    }
                }) { Text("Zurücksetzen") }
            },
            dismissButton = {
                TextButton(onClick = { resetProgressConfirmOpen = false }) {
                    Text("Abbrechen")
                }
            }
        )
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
                    // Queued, so it exists even without a connection.
                    uploadSyncer.createBookmark(
                        bookId = bookId,
                        position = toTimeSpanString(pos),
                        note = note
                    )
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { addBookmarkOpen = false }) { Text("Abbrechen") }
            }
        )
    }

    if (assignSeriesOpen) {
        AssignSeriesDialog(
            availableSeries = seriesList,
            currentSeriesId = book?.seriesId,
            currentPosition = book?.seriesPosition,
            onDismiss = { assignSeriesOpen = false },
            onSave = { newSeriesId, newPosition ->
                assignSeriesOpen = false
                scope.launch {
                    runCatching {
                        val api = repository.apiOrNull() ?: return@runCatching
                        api.assignBookSeries(
                            bookId,
                            AssignSeriesRequest(seriesId = newSeriesId, seriesPosition = newPosition)
                        )
                        repository.bumpSeriesRevision()
                    }
                }
            }
        )
    }

    if (bookmarkManagerOpen) {
        BookmarkManagerSheet(
            bookId = bookId,
            book = book,
            repository = repository,
            onDismiss = { bookmarkManagerOpen = false },
            onPlayBookmark = { bm ->
                val current = book ?: return@BookmarkManagerSheet
                scope.launch {
                    if (playerState.book?.id != current.id) player.loadBook(current)
                    player.seekInBook(parseTimeSpan(bm.position))
                    player.play()
                    onPlaybackStarted()
                }
            }
        )
    }

    if (highlightManagerOpen) {
        HighlightManagerSheet(
            bookId = bookId,
            book = book,
            repository = repository,
            onDismiss = { highlightManagerOpen = false },
            onPlayHighlight = { h ->
                val current = book ?: return@HighlightManagerSheet
                scope.launch {
                    if (playerState.book?.id != current.id) player.loadBook(current)
                    player.seekInBook(parseTimeSpan(h.start))
                    player.play()
                    onPlaybackStarted()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignSeriesDialog(
    availableSeries: List<SeriesSummaryDto>,
    currentSeriesId: Int?,
    currentPosition: Double?,
    onDismiss: () -> Unit,
    onSave: (seriesId: Int?, seriesPosition: Double?) -> Unit
) {
    var dropdownOpen by remember { mutableStateOf(false) }
    var selectedSeriesId by remember(currentSeriesId) { mutableStateOf(currentSeriesId) }
    var positionText by remember(currentPosition) {
        mutableStateOf(currentPosition?.let { formatPosition(it) } ?: "")
    }

    val selectedName = availableSeries.firstOrNull { it.id == selectedSeriesId }?.name
        ?: "– Keine Serie –"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Serie zuweisen") },
        text = {
            androidx.compose.foundation.layout.Column {
                ExposedDropdownMenuBox(
                    expanded = dropdownOpen,
                    onExpandedChange = { dropdownOpen = !dropdownOpen }
                ) {
                    OutlinedTextField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Serie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    DropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("– Keine Serie –") },
                            onClick = {
                                selectedSeriesId = null
                                dropdownOpen = false
                            }
                        )
                        availableSeries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = {
                                    selectedSeriesId = s.id
                                    dropdownOpen = false
                                }
                            )
                        }
                    }
                }

                if (selectedSeriesId != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = positionText,
                        onValueChange = { positionText = it },
                        label = { Text("Position (z. B. 1, 2, 3.5)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pos = positionText.trim().replace(',', '.').toDoubleOrNull()
                onSave(selectedSeriesId, if (selectedSeriesId == null) null else pos)
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

private fun formatPosition(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
private fun BookContent(
    book: BookDetailDto,
    repository: FabulaRepository,
    isCurrent: Boolean,
    isPlaying: Boolean,
    /**
     * Chapter playback would continue at -- the live one while this book plays,
     * otherwise the one at the stored progress. Null when the book hasn't been
     * started. Marking it does not require loading the book into the player.
     */
    resumeChapterIndex: Int?,
    bookmarks: List<BookmarkDto>,
    highlights: List<HighlightDto>,
    downloadState: BookDownloadState?,
    downloadedFileIds: Set<Int>,
    onDownloadClick: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPlay: () -> Unit,
    onAddBookmark: () -> Unit,
    onChapterClick: (ChapterDto) -> Unit,
    onChapterBookmark: (ChapterDto) -> Unit,
    onBookmarkClick: (BookmarkDto) -> Unit,
    onBookmarkDelete: (BookmarkDto) -> Unit
) {
    val totalSeconds = parseTimeSpan(book.duration)
    var bookmarksExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
    // Precomputed: the per-chapter check is O(chapters x files), which would
    // otherwise re-run for every ChapterRow on every recomposition.
    val offlineFlags = remember(book, downloadedFileIds, downloadState?.currentFileId) {
        offlineChapterFlags(book, downloadedFileIds, downloadState?.currentFileId)
    }
    // Group by local date, oldest day first; within each day, oldest first.
    // Newest bookmark therefore renders at the very bottom of the section.
    val bookmarkGroups = remember(bookmarks) {
        bookmarks
            .sortedBy { it.createdAt }
            .groupBy { localDateKey(it.createdAt) }
            .toSortedMap()
    }

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
            ActionRow(
                onPlay = onPlay,
                isPlaying = isCurrent && isPlaying,
                downloadState = downloadState,
                onDownloadClick = onDownloadClick,
                onAddBookmark = onAddBookmark
            )
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
            item("bookmarks-header") {
                BookmarksSectionHeader(
                    count = bookmarks.size,
                    expanded = bookmarksExpanded,
                    onToggle = { bookmarksExpanded = !bookmarksExpanded }
                )
            }
            if (bookmarksExpanded) {
                bookmarkGroups.forEach { (dateKey, dayBookmarks) ->
                    item("bm-date-$dateKey") {
                        Text(
                            text = formatDayHeader(dateKey),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(items = dayBookmarks, key = { "bookmark-${it.id}" }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            chapterTitle = chapterAt(book, parseTimeSpan(bookmark.position))?.title,
                            onClick = { onBookmarkClick(bookmark) },
                            onDelete = { onBookmarkDelete(bookmark) }
                        )
                    }
                }
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
                    isActive = chapter.index == resumeChapterIndex,
                    hasHighlight = chapterHasHighlight(chapter, highlights),
                    isOffline = offlineFlags.offline.getOrElse(chapter.index) { false },
                    isDownloading = offlineFlags.loading.getOrElse(chapter.index) { false },
                    downloadProgress = if (offlineFlags.loading.getOrElse(chapter.index) { false })
                        downloadState?.currentFileProgress else null,
                    onClick = { onChapterClick(chapter) },
                    onBookmarkHere = { onChapterBookmark(chapter) }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun chapterAt(book: BookDetailDto, posSec: Double): ChapterDto? {
    val probe = posSec + 0.010  // see PlayerController.chapterAt
    return book.chapters.firstOrNull {
        probe >= parseTimeSpan(it.start) && probe < parseTimeSpan(it.end)
    }
}

/**
 * Per-chapter "available offline" flags, indexed by chapter index.
 *
 * A chapter counts as offline only when *every* audio file overlapping its
 * time range is on disk. That is honest in both directions: a single-file m4b
 * with 30 chapters flips them all at once when its one track lands, while a
 * 60-track mp3 book fills in progressively.
 */
/** Short status line shown in the book screen's app bar while syncing. */
private fun downloadStatusLabel(state: BookDownloadState): String = when (state.status) {
    DownloadStatus.Queued -> "Download in Warteschlange"
    DownloadStatus.Running ->
        if (state.totalTracks > 0)
            "Synchronisiert ${state.percent} % · Track ${(state.doneTracks + 1).coerceAtMost(state.totalTracks)} von ${state.totalTracks}"
        else "Synchronisiert ${state.percent} %"
    DownloadStatus.Paused -> "${state.error ?: "Pausiert"} · ${state.percent} %"
    DownloadStatus.Partial -> "Download pausiert · ${state.percent} %"
    DownloadStatus.Failed -> state.error ?: "Download fehlgeschlagen"
    DownloadStatus.Complete -> "Offline verfügbar"
}

/** True when any highlighted passage overlaps this chapter's time range. */
private fun chapterHasHighlight(chapter: ChapterDto, highlights: List<HighlightDto>): Boolean {
    if (highlights.isEmpty()) return false
    val cs = parseTimeSpan(chapter.start)
    val ce = parseTimeSpan(chapter.end)
    return highlights.any { h ->
        parseTimeSpan(h.start) < ce && parseTimeSpan(h.end) > cs
    }
}

@Composable
private fun BookmarksSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Lesezeichen ($count)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Lesezeichen einklappen" else "Lesezeichen ausklappen",
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

private val DAY_HEADER_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d. MMMM yyyy", java.util.Locale.GERMAN)

private fun localDateKey(createdAt: String): java.time.LocalDate =
    runCatching {
        java.time.Instant.parse(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }.getOrElse {
        // ISO offset / partial timestamps -- fall back to the date prefix.
        runCatching { java.time.LocalDate.parse(createdAt.take(10)) }
            .getOrDefault(java.time.LocalDate.MIN)
    }

private fun formatDayHeader(date: java.time.LocalDate): String =
    if (date == java.time.LocalDate.MIN) "Unbekanntes Datum" else date.format(DAY_HEADER_FORMATTER)

@Composable
private fun ActionRow(
    onPlay: () -> Unit,
    isPlaying: Boolean,
    downloadState: BookDownloadState?,
    onDownloadClick: () -> Unit,
    onAddBookmark: () -> Unit
) {
    // Same reasoning as the chapter rows: seeded so an already-downloaded book
    // doesn't celebrate on open, and saveable because this row sits in a
    // LazyColumn item too -- scrolling through a long chapter list disposes it.
    var completeCelebrated by rememberSaveable {
        mutableStateOf(downloadState?.status == DownloadStatus.Complete)
    }
    // Arm it again when the download goes away, so re-downloading celebrates.
    LaunchedEffect(downloadState?.status) {
        if (downloadState?.status != DownloadStatus.Complete) completeCelebrated = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onAddBookmark) {
            Icon(
                Icons.Filled.BookmarkBorder,
                contentDescription = "Lesezeichen setzen",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = onDownloadClick) {
            when (downloadState?.status) {
                DownloadStatus.Running, DownloadStatus.Queued, DownloadStatus.Paused -> {
                    Box(contentAlignment = Alignment.Center) {
                        if (downloadState.status == DownloadStatus.Queued) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(26.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { downloadState.percent / 100f },
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Download abbrechen",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                DownloadStatus.Complete -> WhirlInIcon(
                    icon = Icons.Filled.DownloadDone,
                    contentDescription = "Offline verfügbar – tippen zum Entfernen",
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 26.dp,
                    play = !completeCelebrated,
                    onEntranceFinished = { completeCelebrated = true }
                )
                DownloadStatus.Partial -> Icon(
                    Icons.Filled.DownloadForOffline,
                    contentDescription = "Download fortsetzen",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                DownloadStatus.Failed -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "Download fehlgeschlagen – erneut versuchen",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(26.dp)
                )
                null -> Icon(
                    Icons.Filled.Download,
                    contentDescription = "Herunterladen",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        // The former second "Mehr" button here duplicated the app bar's
        // overflow menu and did nothing; removed rather than wired twice.

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
    hasHighlight: Boolean,
    isOffline: Boolean,
    isDownloading: Boolean,
    /** 0..1 for this chapter's track, or null while the size is unknown. */
    downloadProgress: Float?,
    onClick: () -> Unit,
    onBookmarkHere: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val chapterDurationSec = parseTimeSpan(chapter.end) - parseTimeSpan(chapter.start)

    // Seeded with isOffline at first composition: a chapter that was already
    // downloaded when the screen opened must not celebrate again. Saveable
    // because LazyColumn disposes off-screen rows -- with a plain remember the
    // whirl would replay every time the row scrolled back into view (and only
    // on longer scrolls, since nearby rows survive in the prefetch window,
    // which makes it easy to miss when testing by hand).
    var offlineMarkCelebrated by rememberSaveable(chapter.index) { mutableStateOf(isOffline) }
    // Arm it again when the download goes away, so re-downloading celebrates.
    LaunchedEffect(isOffline) { if (!isOffline) offlineMarkCelebrated = false }

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
        if (isDownloading) {
            if (downloadProgress != null) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                // Size unknown (older server, no Content-Length): keep spinning
                // rather than show a ring frozen at zero.
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        if (isOffline) {
            WhirlInIcon(
                icon = Icons.Filled.DownloadDone,
                contentDescription = "Offline verfügbar",
                tint = MaterialTheme.colorScheme.primary,
                iconSize = 20.dp,
                play = !offlineMarkCelebrated,
                onEntranceFinished = { offlineMarkCelebrated = true }
            )
            Spacer(Modifier.width(4.dp))
        }
        if (hasHighlight) {
            Icon(
                Icons.Filled.BorderColor,
                contentDescription = "Enthält Markierungen",
                tint = HighlightColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "Mehr",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Ab hier abspielen") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Lesezeichen hier setzen") },
                    leadingIcon = { Icon(Icons.Filled.BookmarkBorder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onBookmarkHere()
                    }
                )
            }
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
                // Negative id = queued locally, not on the server yet.
                if (bookmark.id < 0) {
                    Text(
                        " · wird übertragen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = FontStyle.Italic,
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
