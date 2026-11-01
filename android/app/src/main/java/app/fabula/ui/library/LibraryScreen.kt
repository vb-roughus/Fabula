package app.fabula.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
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
import app.fabula.data.PagedResultDto
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

/** How long typing has to pause before the server is asked. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** One request for a page of the listing. */
private suspend fun FabulaRepository.fetchBooksPage(
    query: PageQuery,
    page: Int
): PagedResultDto<BookSummaryDto> {
    val api = apiOrNull() ?: throw IllegalStateException("Kein Server konfiguriert.")
    return api.listBooks(
        search = query.search,
        standalone = if (query.standalone) true else null,
        type = query.type,
        page = page,
        pageSize = LIBRARY_PAGE_SIZE
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: FabulaRepository,
    onMenuClick: () -> Unit,
    onBookClick: (Int) -> Unit
) {
    var filter by remember { mutableStateOf(LibraryFilter.All) }

    // Saveable, so turning the phone does not throw away what was being typed.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val searchActive = searching && trimmedQuery.isNotEmpty()

    val scope = rememberCoroutineScope()
    // Two listings that arrive a page at a time: the plain library, and the
    // search. Both are asked for the next page as the scrolling nears the end.
    val browse = remember(repository) { BookPager { q, page -> repository.fetchBooksPage(q, page) } }
    val results = remember(repository) { BookPager { q, page -> repository.fetchBooksPage(q, page) } }

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

    // The tabs ask the server rather than filter what is loaded: with a list
    // that arrives page by page, a client can only filter the pages it has.
    LaunchedEffect(filter, refreshTrigger, reconnects) {
        // The list on screen stays up while this runs, so a pull-to-refresh
        // doesn't fall back to the spinner.
        try {
            browse.reload(filter.toQuery())
        } finally {
            isRefreshing = false
        }
    }

    // The search asks the server rather than filtering what is loaded: the list
    // above holds a few pages at most, and a search that can only find what
    // happens to be on screen is not much of a search. Keyed on the text, so
    // typing on cancels the request still waiting out its pause.
    LaunchedEffect(trimmedQuery, filter, refreshTrigger, reconnects) {
        if (trimmedQuery.isEmpty()) {
            results.clear()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        // Stays inside the tab it was typed in.
        results.reload(filter.toQuery(search = trimmedQuery))
    }

    KeepLoadingWhileEmpty(browse)
    KeepLoadingWhileEmpty(results)

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
                            pager = results,
                            query = trimmedQuery,
                            filter = filter,
                            offlineBooks = offlineBooks,
                            repository = repository,
                            badgeFor = badgeFor,
                            onBookClick = onBookClick,
                            onLoadMore = { results.loadMore(scope) }
                        )
                    } else {
                        BrowseContent(
                            pager = browse,
                            filter = filter,
                            offlineBooks = offlineBooks,
                            repository = repository,
                            badgeFor = badgeFor,
                            onBookClick = onBookClick,
                            onLoadMore = { browse.loadMore(scope) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps fetching while a listing shows nothing yet has pages left.
 *
 * Only happens against a server that predates the filters: a whole page can be
 * made of books this client then hides, and an empty grid has nothing to scroll
 * and so nothing to ask for the next page.
 */
@Composable
private fun KeepLoadingWhileEmpty(pager: BookPager) {
    val scope = rememberCoroutineScope()
    val listing = pager.listing
    LaunchedEffect(listing?.query, listing?.loadedPages, listing?.moreFailed) {
        if (listing != null && listing.books.isEmpty() && listing.hasMore && !listing.moreFailed) {
            pager.loadMore(scope)
        }
    }
}

/** The plain library: books without a series, or the downloaded books when offline. */
@Composable
private fun BrowseContent(
    pager: BookPager,
    filter: LibraryFilter,
    offlineBooks: List<BookSummaryDto>,
    repository: FabulaRepository,
    badgeFor: (Int) -> DownloadBadgeState?,
    onBookClick: (Int) -> Unit,
    onLoadMore: () -> Unit
) {
    val listing = pager.listing
    val error = pager.error
    when {
        listing != null -> when {
            listing.books.isEmpty() && listing.hasMore && !listing.moreFailed ->
                CircularProgressIndicator()
            listing.books.isEmpty() && listing.moreFailed -> CenteredHint(
                "Laden fehlgeschlagen. Zum Wiederholen nach unten ziehen."
            )
            listing.books.isEmpty() && filter == LibraryFilter.All -> CenteredHint(
                "Noch nichts in der Bibliothek. Lege eine Bibliothek auf dem Server an " +
                    "und starte einen Scan."
            )
            listing.books.isEmpty() && filter == LibraryFilter.Standalone -> CenteredHint(
                "Alle Titel gehören zu einer Serie."
            )
            listing.books.isEmpty() -> CenteredHint(
                "In dieser Kategorie ist noch nichts vorhanden."
            )
            // Keyed on the query, so a new tab starts at the top while a
            // refresh keeps its place.
            else -> key(listing.query) {
                BookGrid(
                    books = listing.books,
                    repository = repository,
                    badgeFor = badgeFor,
                    onClick = onBookClick,
                    more = listing.moreInfo(onLoadMore)
                )
            }
        }
        error != null -> {
            // The downloaded books, narrowed by the tab like the server would.
            val offlineVisible = offlineBooks.matching(filter.toQuery())
            if (offlineBooks.isEmpty()) {
                OfflineEmptyMessage(error)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    OfflineBanner(error)
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
        else -> CircularProgressIndicator()
    }
}

/** What the library shows while a search is running. */
@Composable
private fun SearchResults(
    pager: BookPager,
    query: String,
    filter: LibraryFilter,
    offlineBooks: List<BookSummaryDto>,
    repository: FabulaRepository,
    badgeFor: (Int) -> DownloadBadgeState?,
    onBookClick: (Int) -> Unit,
    onLoadMore: () -> Unit
) {
    val listing = pager.listing
    val error = pager.error
    when {
        listing != null -> when {
            listing.books.isEmpty() && listing.hasMore && !listing.moreFailed ->
                CircularProgressIndicator()
            listing.books.isEmpty() -> CenteredHint("Nichts gefunden für \u201e$query\u201c.")
            else -> key(listing.query) {
                BookGrid(
                    books = listing.books,
                    repository = repository,
                    badgeFor = badgeFor,
                    onClick = onBookClick,
                    showSeries = true,
                    more = listing.moreInfo(onLoadMore)
                )
            }
        }
        error != null -> {
            // No server: search what is on the device, so the field still does
            // something useful in a tunnel.
            val local = offlineBooks.filter { matchesSearch(it, query) }.matching(filter.toQuery())
            Column(modifier = Modifier.fillMaxSize()) {
                OfflineBanner(error)
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
        else -> CircularProgressIndicator()
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
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit
) {
    Row(
        // Scrolls sideways where the four tabs do not fit a narrow screen.
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryFilter.entries.forEach { tab ->
            FilterChipPill(label = tab.label, active = filter == tab) { onFilterChange(tab) }
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

/** How far through a paged listing the grid is, and how to ask for more. */
private class MoreInfo(
    val hasMore: Boolean,
    val loading: Boolean,
    val failed: Boolean,
    val onLoadMore: () -> Unit
)

private fun Listing.moreInfo(onLoadMore: () -> Unit) =
    MoreInfo(hasMore = hasMore, loading = loadingMore, failed = moreFailed, onLoadMore = onLoadMore)

@Composable
private fun BookGrid(
    books: List<BookSummaryDto>,
    repository: FabulaRepository,
    badgeFor: (Int) -> DownloadBadgeState?,
    onClick: (Int) -> Unit,
    showSeries: Boolean = false,
    more: MoreInfo? = null
) {
    val bottomInset = LocalContentBottomInset.current.calculateBottomPadding()
    val gridState = rememberLazyGridState()

    if (more != null) {
        // Asks for the next page once the last visible book is within reach of
        // the end. Re-evaluated as the page lands, so a page too short to push
        // the end out of reach is followed by the next straight away.
        val nearEnd by remember(gridState, books.size, more.hasMore, more.loading, more.failed) {
            derivedStateOf {
                val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                shouldLoadMore(last, books.size, more.hasMore, more.loading || more.failed)
            }
        }
        LaunchedEffect(nearEnd) {
            if (nearEnd) more.onLoadMore()
        }
    }

    LazyVerticalGrid(
        state = gridState,
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
        if (more != null && (more.loading || more.failed)) {
            item(key = "load-more-footer", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(more)
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(more: MoreInfo) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (more.failed) {
            // A failed page is not retried on its own -- that would repeat as
            // fast as it can fail -- so the retry is the listener's to ask for.
            TextButton(onClick = more.onLoadMore) {
                Text("Laden fehlgeschlagen \u2013 erneut versuchen")
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
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
