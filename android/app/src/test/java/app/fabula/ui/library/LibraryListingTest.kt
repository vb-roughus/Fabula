package app.fabula.ui.library

import app.fabula.data.BookSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library hides books that belong to a series, and a search finds them
 * anyway. Both rules live in plain functions so they can be pinned down here.
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

    // --- hiding series books ------------------------------------------------

    @Test
    fun `books in a series are left out of the library`() {
        val books = listOf(
            book(1),
            book(2, seriesId = 7, series = "Harry Potter"),
            book(3)
        )

        assertEquals(listOf(1, 3), standaloneBooks(books).map { it.id })
    }

    @Test
    fun `a library without any series stays whole`() {
        val books = listOf(book(1), book(2), book(3))

        assertEquals(listOf(1, 2, 3), standaloneBooks(books).map { it.id })
    }

    @Test
    fun `a library made only of series books shows nothing`() {
        val books = listOf(book(1, seriesId = 7), book(2, seriesId = 7))

        assertTrue(standaloneBooks(books).isEmpty())
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
}
