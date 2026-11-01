package app.fabula.ui.library

import app.fabula.data.BookSummaryDto

/**
 * What the library lists, and what a search finds.
 *
 * Kept out of the screen so it can be tested: the rules here are the part that
 * would quietly go wrong, and a Composable is a poor place to notice.
 */

/**
 * The books that belong to no series. The library shows only these -- a series
 * is reached through the Series tab, and a book in one is found there or by
 * searching.
 *
 * The server filters too, which is what keeps a full page of results from being
 * thinned out afterwards. This second pass is for a server that predates the
 * filter and sends everything.
 */
internal fun standaloneBooks(books: List<BookSummaryDto>): List<BookSummaryDto> =
    books.filter { it.seriesId == null }

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
