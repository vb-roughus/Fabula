package app.fabula.ui.library

import app.fabula.data.BookSummaryDto
import app.fabula.data.LibraryType

/**
 * What the library lists, and what a search finds.
 *
 * Kept out of the screen so it can be tested: the rules here are the part that
 * would quietly go wrong, and a Composable is a poor place to notice.
 */

/**
 * The tabs along the top of the library. One is active at a time.
 *
 * "Alle" lists everything, series volumes included; "Ohne Serie" is the list of
 * books that belong to none, for finding the loose ones among a library that is
 * mostly series.
 */
internal enum class LibraryFilter(val label: String) {
    All("Alle"),
    Audiobooks("Hörbücher"),
    RadioPlays("Hörspiele"),
    Standalone("Ohne Serie")
}

/**
 * What to ask the server for under this tab, optionally narrowed by a search.
 * A search stays inside the tab it was typed in: under "Ohne Serie" it looks
 * through the books without a series.
 */
internal fun LibraryFilter.toQuery(search: String? = null) = PageQuery(
    search = search,
    standalone = this == LibraryFilter.Standalone,
    type = when (this) {
        LibraryFilter.Audiobooks -> LibraryType.Audiobook
        LibraryFilter.RadioPlays -> LibraryType.RadioPlay
        LibraryFilter.All, LibraryFilter.Standalone -> null
    }
)

/**
 * The books that belong to no series.
 */
internal fun standaloneBooks(books: List<BookSummaryDto>): List<BookSummaryDto> =
    books.filter { it.seriesId == null }

/**
 * The books a query asks for, whatever was sent.
 *
 * The server filters, which is what keeps a page from being thinned out after
 * the fact. This is the second pass: for a server that predates the filters and
 * sends everything, and for the downloaded books shown offline, which no server
 * has filtered at all.
 */
internal fun List<BookSummaryDto>.matching(query: PageQuery): List<BookSummaryDto> =
    (if (query.standalone) standaloneBooks(this) else this).ofType(query.type)

/** The words of a search: split on whitespace, blanks dropped. */
internal fun searchWords(query: String): List<String> =
    query.split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * Whether a book answers a search. Every word has to be found somewhere --
 * title, subtitle, author, narrator or series -- so "rowling stein" finds a
 * title by that author without the words sitting next to each other.
 *
 * The server does the same for the full library. This copy is for the books on
 * the device, which is all there is to search without a connection. Case is
 * folded properly, umlauts included.
 */
internal fun matchesSearch(book: BookSummaryDto, query: String): Boolean {
    val words = searchWords(query)
    if (words.isEmpty()) return true
    val fields = buildList {
        add(book.title)
        book.subtitle?.let { add(it) }
        book.series?.let { add(it) }
        addAll(book.authors)
        addAll(book.narrators)
    }
    return words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }
}

/**
 * "Band 3" / "Band 2,5" for a book in a series, or null when its position is
 * unknown. Whole numbers lose the decimal -- "Band 3", not "Band 3.0".
 */
internal fun seriesLabel(book: BookSummaryDto): String? {
    val name = book.series ?: return null
    val position = book.seriesPosition ?: return name
    val shown = if (position % 1.0 == 0.0) position.toLong().toString()
        else position.toString().replace('.', ',')
    return "$name · Band $shown"
}

// --- paging ---------------------------------------------------------------

/** Books per request. The server caps a page at 200; this is what a screenful or two needs. */
internal const val LIBRARY_PAGE_SIZE = 60

/**
 * How many books before the end of what is loaded the next page is requested.
 * A few rows' worth, so it is usually in by the time the scrolling gets there.
 */
internal const val LOAD_MORE_PREFETCH = 12

/**
 * Whether the server has more pages than have been fetched. Worked out from the
 * server's own total -- not from how many books survived the client-side
 * filters, which can be fewer than a page and would make the end look near.
 */
internal fun hasMorePages(loadedPages: Int, pageSize: Int, total: Int): Boolean =
    loadedPages * pageSize < total

/**
 * Adds a page to what is already listed, skipping books that are there.
 *
 * Pages are separate requests. A book added or renamed in between shifts the
 * boundary, so the same book can turn up at the end of one page and the start
 * of the next -- and a grid keyed by id throws on a duplicate key.
 */
internal fun appendPage(
    existing: List<BookSummaryDto>,
    incoming: List<BookSummaryDto>
): List<BookSummaryDto> {
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + incoming.filter { seen.add(it.id) }
}

/**
 * Whether scrolling has got close enough to the end to ask for the next page.
 *
 * [busy] covers both a request in flight and one that just failed: without the
 * second, a failing request would be repeated as fast as it can fail.
 */
internal fun shouldLoadMore(
    lastVisibleIndex: Int,
    itemCount: Int,
    hasMore: Boolean,
    busy: Boolean
): Boolean =
    hasMore && !busy && itemCount > 0 && lastVisibleIndex >= itemCount - 1 - LOAD_MORE_PREFETCH

/** The books of one kind of library, or all of them for null. */
internal fun List<BookSummaryDto>.ofType(type: LibraryType?): List<BookSummaryDto> =
    if (type == null) this else this.filter { it.type == type }
