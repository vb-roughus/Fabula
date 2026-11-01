package app.fabula.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fabula.FabulaApp
import app.fabula.data.BookSummaryDto
import app.fabula.data.FabulaRepository
import app.fabula.data.LibraryType
import app.fabula.data.formatDurationHuman
import app.fabula.data.parseTimeSpan
import app.fabula.data.toSummary
import app.fabula.ui.DownloadBadge
import app.fabula.ui.DownloadBadgeState
import app.fabula.ui.LocalContentBottomInset
import app.fabula.ui.rememberDownloadBadges
import app.fabula.ui.OfflineBanner
import app.fabula.ui.OfflineEmptyMessage
import androidx.compose.runtime.CompositionLocalProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private sealed interface LibraryState {
    data object Loading : LibraryState
    data class Loaded(val books: List<BookSummaryDto>) : LibraryState
    data class Error(val message: String) : LibraryState
}

private sealed interface SearchState {
    /** Nothing typed yet. */
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val books: List<BookSummaryDto>) : SearchState
    data class Error(val message: String) : SearchState
}

/** How long typing has to pause before the server is asked. */
private const val SEARCH_DEBOUNCE_MS = 300L

private fun List<BookSummaryDto>.ofType(type: LibraryType?): List<BookSummaryDto> =
    if (type == null) this else this.filter { it.type == type }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: FabulaRepository,
    onMenuClick: () -> Unit,
    onBookClick: (Int) -> Unit
) {
    var state by remember { mutableStateOf<LibraryState>(LibraryState.Loading) }
    var filter by remember { mutableStateOf<LibraryType?>(null) }

    // Saveable, so turning the phone does not throw away what was being typed.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchState by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val trimmedQuery = query.trim()
    val searchActive = searching && trimmedQuery.isNotEmpty()

    // Fallback for when the server is unreachable: the books already on disk.
    val offlineStore = (LocalContext.current.applicationContext as FabulaApp).offlineStore
    val offlineRevision by offlineStore.revision.collectAsState()
    val offlineBooks = remember(offlineRevision) {
        offlineStore.listDownloadedBooks().map { it.book.toSummary() }
    }
    // Collected once here; passing it down beats a collector per cover.
    val badgeFor = rememberDownloadBadges()
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Refetch after a successful manual reconnect from the drawer.
    val reconnects by repository.reconnects.collectAsState()

    LaunchedEffect(refreshTrigger, reconnects) {
        // On a pull-to-refresh we keep the current list visible (don't fall
        // back to the Loading spinner) and only swap it once the reload lands.
        try {
            val api = repository.apiOrNull()
            state = if (api == null) {
                LibraryState.Error("Kein Server konfiguriert.")
            } else {
                // Books in a series are not listed here -- the Series tab is
                // the way to them, and the search finds them by name.
                LibraryState.Loaded(
                    standaloneBooks(
                        api.listBooks(standalone = true, page = 1, pageSize = 200).items
                    )
                )
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c  // effect cancelled (navigation/refresh) -- not an error
        } catch (t: Throwable) {
            state = LibraryState.Error(t.message ?: "Unbekannter Fehler")
        } finally {
            isRefreshing = false
        }
    }

    // The search asks the server rather than filtering what is loaded: the list
    // above holds one page, and a search that can only find what happens to be
    // on screen is not much of a search. Keyed on the text, so typing on
    // cancels the request still waiting out its pause.
    LaunchedEffect(trimmedQuery, refreshTrigger, reconnects) {
        if (trimmedQuery.isEmpty()) {
            searchState = SearchState.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        // Keep the previous results on screen while the next ones load; a
        // spinner between every keystroke would make the grid flicker.
        if (searchState !is SearchState.Results) searchState = SearchState.Loading
        searchState = try {
            val api = repository.apiOrNull()
            if (api == null) {
                SearchState.Error("Kein Server konfiguriert.")
            } else {
                // Not narrowed to standalone books: a search finds everything.
                SearchState.Results(api.listBooks(search = trimmedQuery, page = 1, pageSize = 200).items)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            SearchState.Error(t.message ?: "Unbekannter Fehler")
        }
    }

    // Dropping focus is what closes the keyboard; it is also the stable way to.
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }
    val closeSearch = {
        searching = false
        query = ""
        focusManager.clearFocus()
    }
    // Back leaves the search first, rather than the screen.
    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { focusManager.clearFocus() },
                            focusRequester = searchFocus
                        )
                    } else {
                        Text("Bibliothek")
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = closeSearch) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schliessen")
                        }
                    } else {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menü")
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Suchen")
                        }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp)
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            FilterChipsRow(filter = filter, onFilterChange = { filter = it })
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    refreshTrigger++
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (searchActive) {
                        SearchResults(
                            state = searchState,
                            query = trimmedQuery,
                            filter = filter,
                            offlineBooks = offlineBooks,
                            repository = repository,
                            badgeFor = badgeFor,
                            onBookClick = onBookClick
                        )
                    } else when (val s = state) {
                        LibraryState.Loading -> CircularProgressIndicator()
                        is LibraryState.Error -> {
                            // Offline, every downloaded book is listed -- series
                            // volumes included. Hiding them here would strand
                            // them: the Series tab has to ask the server, so it
                            // is no way to a downloaded book without a connection.
                            val offlineVisible = offlineBooks.ofType(filter)
                            if (offlineBooks.isEmpty()) {
                                OfflineEmptyMessage(s.message)
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    OfflineBanner(s.message)
                                    if (offlineVisible.isEmpty()) {
                                        Text(
                                            "In dieser Kategorie ist nichts offline gespeichert.",
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    } else {
                                        BookGrid(
                                            books = offlineVisible,
                                            repository = repository,
                                            badgeFor = badgeFor,
                                            onClick = onBookClick
                                        )
                                    }
                                }
                            }
                        }
                        is LibraryState.Loaded -> {
                            val visible = s.books.ofType(filter)
                            when {
                                s.books.isEmpty() -> CenteredHint(
                                    "Keine Titel ohne Serie. Titel einer Serie findest du unter " +
                                        "\u201eSerien\u201c oder über die Suche. Neue Titel erscheinen " +
                                        "nach einem Scan auf dem Server."
                                )
                                visible.isEmpty() -> Text(
                                    "In dieser Kategorie ist noch nichts vorhanden.",
                                    color = MaterialTheme.colorScheme.outline
                                )
                                else -> BookGrid(
                                    books = visible,
                                    repository = repository,
                                    badgeFor = badgeFor,
                                    onClick = onBookClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the library shows while a search is running. */
@Composable
private fun SearchResults(
    state: SearchState,
    query: String,
    filter: LibraryType?,
    offlineBooks: List<BookSummaryDto>,
    repository: FabulaRepository,
    badgeFor: (Int) -> DownloadBadgeState?,
    onBookClick: (Int) -> Unit
) {
    when (state) {
        SearchState.Idle, SearchState.Loading -> CircularProgressIndicator()
        is SearchState.Results -> {
            val visible = state.books.ofType(filter)
            if (visible.isEmpty()) {
                CenteredHint("Nichts gefunden für \u201e$query\u201c.")
            } else {
                BookGrid(
                    books = visible,
                    repository = repository,
                    badgeFor = badgeFor,
                    onClick = onBookClick,
                    showSeries = true
                )
            }
        }
        is SearchState.Error -> {
            // No server: search what is on the device, so the field still does
            // something useful in a tunnel.
            val local = offlineBooks.filter { matchesSearch(it, query) }.ofType(filter)
            Column(modifier = Modifier.fillMaxSize()) {
                OfflineBanner(state.message)
                if (local.isEmpty()) {
                    CenteredHint("Offline nichts gefunden für \u201e$query\u201c.")
                } else {
                    BookGrid(
                        books = local,
                        repository = repository,
                        badgeFor = badgeFor,
                        onClick = onBookClick,
                        showSeries = true
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Titel, Autor, Sprecher, Serie") },
        singleLine = true,
        // Sits in the app bar, so it takes the bar's background and drops the
        // underline a form field would draw.
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
}

@Composable
private fun FilterChipsRow(
    filter: LibraryType?,
    onFilterChange: (LibraryType?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipPill(label = "Alle", active = filter == null) { onFilterChange(null) }
        FilterChipPill(label = "Hörbücher", active = filter == LibraryType.Audiobook) {
            onFilterChange(LibraryType.Audiobook)
        }
        FilterChipPill(label = "Hörspiele", active = filter == LibraryType.RadioPlay) {
            onFilterChange(LibraryType.RadioPlay)
        }
    }
}

@Composable
private fun FilterChipPill(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun BookGrid(
    books: List<BookSummaryDto>,
    repository: FabulaRepository,
    badgeFor: (Int) -> DownloadBadgeState?,
    onClick: (Int) -> Unit,
    showSeries: Boolean = false
) {
    val bottomInset = LocalContentBottomInset.current.calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = books, key = { it.id }) { book ->
            BookCard(
                book = book,
                repository = repository,
                badge = badgeFor(book.id),
                showSeries = showSeries,
                onClick = { onClick(book.id) }
            )
        }
    }
}

@Composable
private fun BookCard(
    book: BookSummaryDto,
    repository: FabulaRepository,
    badge: DownloadBadgeState?,
    showSeries: Boolean,
    onClick: () -> Unit
) {
    val durationSec = parseTimeSpan(book.duration)
    val positionSec = book.progress?.let { parseTimeSpan(it.position) } ?: 0.0
    val finished = book.progress?.finished == true
    val started = positionSec > 1.0
    val pct = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val coverUrl = repository.coverUrl(book)
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp)
                )
            }

            // Top-start so it never collides with the "FERTIG" chip.
            if (badge != null) {
                DownloadBadge(
                    state = badge,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
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

            if (started && !finished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct.toFloat())
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )
            // In search results only: a book that the library itself hides is
            // otherwise a result with no hint of where it belongs.
            if (showSeries) {
                seriesLabel(book)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            Text(
                buildString {
                    append(formatDurationHuman(durationSec))
                    if (started && !finished) append(" · ${(pct * 100).toInt()} %")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
