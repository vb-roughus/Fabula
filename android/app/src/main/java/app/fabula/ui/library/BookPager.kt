package app.fabula.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fabula.data.BookSummaryDto
import app.fabula.data.LibraryType
import app.fabula.data.PagedResultDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a listing asks the server for. Two listings with the same query are the same listing. */
internal data class PageQuery(
    val search: String?,
    val standalone: Boolean,
    val type: LibraryType?
)

/** The books fetched so far for one query, and how far through it the fetching has got. */
internal data class Listing(
    val query: PageQuery,
    val books: List<BookSummaryDto>,
    val loadedPages: Int,
    val total: Int,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false
) {
    val hasMore: Boolean get() = hasMorePages(loadedPages, LIBRARY_PAGE_SIZE, total)
}

/**
 * A list that arrives a page at a time.
 *
 * Holds the books fetched so far and fetches the next page on request. The
 * library keeps two of these -- one for browsing and one for the search -- so
 * the rules for appending, failing and being replaced exist once.
 *
 * The part that needs care is being replaced while a page is on its way. A new
 * query, a pull-to-refresh or a reconnect all start over, and a page that was
 * requested for the old list must not be appended to the new one. Every
 * restart therefore moves [generation] on, and a page that arrives for an
 * older one is dropped.
 */
internal class BookPager(
    private val fetch: suspend (query: PageQuery, page: Int) -> PagedResultDto<BookSummaryDto>
) {
    /** Null until a first page has landed, and again after a failed reload. */
    var listing by mutableStateOf<Listing?>(null)
        private set

    /** Why the first page could not be fetched; null while there is a listing. */
    var error by mutableStateOf<String?>(null)
        private set

    private var generation = 0

    /**
     * Fetches the first page of [query] and replaces whatever is listed.
     *
     * The old listing stays up while this runs, so a refresh or the next
     * keystroke of a search doesn't blank the screen. A failure drops it,
     * though: a list that can no longer be refreshed is the cue for the offline
     * view, not something to keep showing as if it were current.
     */
    suspend fun reload(query: PageQuery) {
        val started = ++generation
        try {
            val first = fetch(query, 1)
            if (started != generation) return
            // Moved on a second time as the page lands, so that a next page
            // requested while this one was on its way -- for the list being
            // replaced -- finds itself out of date.
            generation++
            listing = Listing(
                query = query,
                books = filterPage(first.items, query),
                loadedPages = 1,
                total = first.total
            )
            error = null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (started != generation) return
            listing = null
            error = t.message ?: "Unbekannter Fehler"
        }
    }

    /** Forgets everything, and any page still on its way. */
    fun clear() {
        generation++
        listing = null
        error = null
    }

    /** Fetches the next page, unless one is on its way already or there is none. */
    fun loadMore(scope: CoroutineScope) {
        val current = listing ?: return
        if (current.loadingMore || !current.hasMore) return
        val started = generation
        listing = current.copy(loadingMore = true, moreFailed = false)
        scope.launch {
            try {
                val next = fetch(current.query, current.loadedPages + 1)
                if (started != generation) return@launch
                val now = listing ?: return@launch
                listing = now.copy(
                    books = appendPage(now.books, filterPage(next.items, now.query)),
                    loadedPages = now.loadedPages + 1,
                    total = next.total,
                    loadingMore = false
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (started != generation) return@launch
                listing = listing?.copy(loadingMore = false, moreFailed = true)
            }
        }
    }

    private fun filterPage(items: List<BookSummaryDto>, query: PageQuery): List<BookSummaryDto> =
        items.matching(query)
}
