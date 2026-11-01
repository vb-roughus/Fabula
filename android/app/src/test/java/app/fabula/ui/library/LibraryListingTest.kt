package app.fabula.ui.library

import app.fabula.data.BookSummaryDto
import app.fabula.data.LibraryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tabs along the top of the library, and the search. The rules live in
 * plain functions so they can be pinned down here.
 */
class LibraryListingTest {

    private fun book(
        id: Int,
        title: String = "Buch $id",
        subtitle: String? = null,
        authors: List<String> = emptyList(),
        narrators: List<String> = emptyList(),
        seriesId: Int? = null,
        series: String? = null,
        position: Double? = null
    ) = BookSummaryDto(
        id = id,
        title = title,
        subtitle = subtitle,
        authors = authors,
        narrators = narrators,
        seriesId = seriesId,
        series = series,
        seriesPosition = position,
        duration = "10:00:00"
    )

    // --- the "Ohne Serie" list ----------------------------------------------

    @Test
    fun `the standalone list leaves out books in a series`() {
        val books = listOf(
            book(1),
            book(2, seriesId = 7, series = "Harry Potter"),
            book(3)
        )

        assertEquals(listOf(1, 3), standaloneBooks(books).map { it.id })
    }

    @Test
    fun `a library without any series is listed whole`() {
        val books = listOf(book(1), book(2), book(3))

        assertEquals(listOf(1, 2, 3), standaloneBooks(books).map { it.id })
    }

    @Test
    fun `a library made only of series books has no standalone list`() {
        val books = listOf(book(1, seriesId = 7), book(2, seriesId = 7))

        assertTrue(standaloneBooks(books).isEmpty())
    }

    // --- the tabs -----------------------------------------------------------

    /** The new tab goes last, after the three that were there. */
    @Test
    fun `the tabs come in order`() {
        assertEquals(
            listOf("Alle", "Hörbücher", "Hörspiele", "Ohne Serie"),
            LibraryFilter.entries.map { it.label }
        )
    }

    /** "Alle" lists everything, series volumes included. */
    @Test
    fun `the first tab asks for everything`() {
        assertEquals(
            PageQuery(search = null, standalone = false, type = null),
            LibraryFilter.All.toQuery()
        )
    }

    @Test
    fun `the series-free tab asks for books without a series`() {
        assertEquals(
            PageQuery(search = null, standalone = true, type = null),
            LibraryFilter.Standalone.toQuery()
        )
    }

    @Test
    fun `the category tabs ask for their kind of library`() {
        assertEquals(LibraryType.Audiobook, LibraryFilter.Audiobooks.toQuery().type)
        assertEquals(LibraryType.RadioPlay, LibraryFilter.RadioPlays.toQuery().type)
        assertFalse(LibraryFilter.Audiobooks.toQuery().standalone)
        assertFalse(LibraryFilter.RadioPlays.toQuery().standalone)
    }

    /** Under "Ohne Serie", a search looks through the books without a series. */
    @Test
    fun `a search stays inside its tab`() {
        val query = LibraryFilter.Standalone.toQuery(search = "hobbit")

        assertEquals("hobbit", query.search)
        assertTrue(query.standalone)
    }

    @Test
    fun `a query keeps only the books it asks for`() {
        val books = listOf(
            book(1),
            book(2, seriesId = 7),
            book(3).copy(type = LibraryType.RadioPlay),
            book(4, seriesId = 7).copy(type = LibraryType.RadioPlay)
        )

        assertEquals(listOf(1, 2, 3, 4), books.matching(LibraryFilter.All.toQuery()).map { it.id })
        assertEquals(listOf(1, 3), books.matching(LibraryFilter.Standalone.toQuery()).map { it.id })
        assertEquals(listOf(1, 2), books.matching(LibraryFilter.Audiobooks.toQuery()).map { it.id })
        assertEquals(listOf(3, 4), books.matching(LibraryFilter.RadioPlays.toQuery()).map { it.id })
    }

    // --- searching ----------------------------------------------------------

    @Test
    fun `a blank search matches everything`() {
        assertTrue(matchesSearch(book(1), ""))
        assertTrue(matchesSearch(book(1), "   "))
    }

    @Test
    fun `finds a word in the title whatever its case`() {
        val b = book(1, title = "Der Hobbit")

        assertTrue(matchesSearch(b, "hobbit"))
        assertTrue(matchesSearch(b, "HOBBIT"))
    }

    /** The case SQLite's LIKE gets wrong, and the reason matching is not left to it. */
    @Test
    fun `folds umlauts`() {
        val b = book(1, title = "Über den Wolken")

        assertTrue(matchesSearch(b, "über"))
        assertTrue(matchesSearch(b, "ÜBER"))
    }

    @Test
    fun `finds a book by author`() {
        val b = book(1, authors = listOf("J. R. R. Tolkien"))

        assertTrue(matchesSearch(b, "tolkien"))
    }

    @Test
    fun `finds a book by narrator`() {
        val b = book(1, narrators = listOf("Rufus Beck"))

        assertTrue(matchesSearch(b, "rufus"))
    }

    /** What keeps a hidden book findable. */
    @Test
    fun `finds a book by the name of its series`() {
        val b = book(1, title = "Der Stein der Weisen", seriesId = 7, series = "Harry Potter")

        assertTrue(matchesSearch(b, "harry potter"))
    }

    @Test
    fun `finds a book by subtitle`() {
        val b = book(1, title = "Das Parfum", subtitle = "Die Geschichte eines Mörders")

        assertTrue(matchesSearch(b, "mörders"))
    }

    @Test
    fun `every word has to match, though not in the same field`() {
        val b = book(1, title = "Der Hobbit", authors = listOf("J. R. R. Tolkien"))

        assertTrue(matchesSearch(b, "hobbit tolkien"))
        assertFalse(matchesSearch(b, "hobbit rowling"))
    }

    @Test
    fun `a book that matches nothing is ruled out`() {
        assertFalse(matchesSearch(book(1, title = "Momo"), "zzz"))
    }

    @Test
    fun `surrounding and repeated spaces make no difference`() {
        val b = book(1, title = "Der Hobbit", authors = listOf("Tolkien"))

        assertTrue(matchesSearch(b, "  hobbit   tolkien  "))
    }

    // --- labelling ----------------------------------------------------------

    @Test
    fun `a numbered volume says which one it is`() {
        assertEquals("Harry Potter · Band 3", seriesLabel(book(1, series = "Harry Potter", position = 3.0)))
    }

    @Test
    fun `a half volume keeps its fraction`() {
        assertEquals("Reihe · Band 2,5", seriesLabel(book(1, series = "Reihe", position = 2.5)))
    }

    @Test
    fun `an unnumbered volume shows just the series`() {
        assertEquals("Reihe", seriesLabel(book(1, series = "Reihe")))
    }

    @Test
    fun `a book outside any series has no label`() {
        assertNull(seriesLabel(book(1)))
    }

    // --- paging -------------------------------------------------------------

    @Test
    fun `there are more pages while the server has more than was fetched`() {
        assertTrue(hasMorePages(loadedPages = 1, pageSize = 60, total = 61))
        assertTrue(hasMorePages(loadedPages = 2, pageSize = 60, total = 130))
    }

    @Test
    fun `there are no more pages once everything was fetched`() {
        assertFalse(hasMorePages(loadedPages = 1, pageSize = 60, total = 60))
        assertFalse(hasMorePages(loadedPages = 3, pageSize = 60, total = 130))
        assertFalse(hasMorePages(loadedPages = 1, pageSize = 60, total = 0))
    }

    @Test
    fun `an appended page follows what is there`() {
        val merged = appendPage(listOf(book(1), book(2)), listOf(book(3), book(4)))

        assertEquals(listOf(1, 2, 3, 4), merged.map { it.id })
    }

    /** A grid keyed by id throws on a duplicate. */
    @Test
    fun `a book that is there already is not appended again`() {
        val merged = appendPage(listOf(book(1), book(2)), listOf(book(2), book(3)))

        assertEquals(listOf(1, 2, 3), merged.map { it.id })
    }

    @Test
    fun `a book repeated within the new page is appended once`() {
        val merged = appendPage(listOf(book(1)), listOf(book(2), book(2)))

        assertEquals(listOf(1, 2), merged.map { it.id })
    }

    @Test
    fun `the next page is asked for when the end is near`() {
        // 60 loaded; the last visible is the 55th.
        assertTrue(shouldLoadMore(lastVisibleIndex = 54, itemCount = 60, hasMore = true, busy = false))
    }

    @Test
    fun `the next page waits while the end is far`() {
        assertFalse(shouldLoadMore(lastVisibleIndex = 10, itemCount = 60, hasMore = true, busy = false))
    }

    @Test
    fun `nothing is asked for when there is nothing more`() {
        assertFalse(shouldLoadMore(lastVisibleIndex = 59, itemCount = 60, hasMore = false, busy = false))
    }

    /** Covers a request in flight and one that just failed -- else a failing page repeats as fast as it fails. */
    @Test
    fun `nothing is asked for while busy`() {
        assertFalse(shouldLoadMore(lastVisibleIndex = 59, itemCount = 60, hasMore = true, busy = true))
    }

    @Test
    fun `an empty grid asks for nothing`() {
        assertFalse(shouldLoadMore(lastVisibleIndex = -1, itemCount = 0, hasMore = true, busy = false))
    }

    @Test
    fun `a list shorter than the lead asks at once`() {
        assertTrue(shouldLoadMore(lastVisibleIndex = 4, itemCount = 5, hasMore = true, busy = false))
    }

    // --- category -----------------------------------------------------------

    @Test
    fun `a category keeps only its own books`() {
        val books = listOf(
            book(1),
            book(2).copy(type = LibraryType.RadioPlay)
        )

        assertEquals(listOf(2), books.ofType(LibraryType.RadioPlay).map { it.id })
        assertEquals(listOf(1), books.ofType(LibraryType.Audiobook).map { it.id })
    }

    @Test
    fun `no category keeps everything`() {
        val books = listOf(book(1), book(2))

        assertEquals(listOf(1, 2), books.ofType(null).map { it.id })
    }
}
