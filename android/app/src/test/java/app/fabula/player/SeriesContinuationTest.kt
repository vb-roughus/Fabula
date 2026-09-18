package app.fabula.player

import app.fabula.data.BookDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which book "Serie hören" continues with, and where it starts.
 *
 * The continuation follows the series volume by volume whatever each book's
 * state, so it regularly reopens books that were heard before. The dangerous
 * branch is therefore where such a book starts: resuming it at its stored
 * position would end it the moment it began and hand on to the next -- tearing
 * through the rest of the series in seconds. Erring the other way discards a
 * bookmark the listener meant to return to.
 */
class SeriesContinuationTest {

    private fun book(
        id: Int,
        seriesId: Int? = 1,
        position: Double? = null,
        title: String = "Band $id"
    ) = BookDetailDto(
        id = id,
        title = title,
        duration = "10:00:00",
        seriesId = seriesId,
        seriesPosition = position
    )

    // --- ordering -----------------------------------------------------------

    @Test
    fun `orders a series by position, not alphabetically`() {
        val books = listOf(
            book(id = 3, position = 3.0, title = "Aaa"),
            book(id = 1, position = 1.0, title = "Zzz"),
            book(id = 2, position = 2.0, title = "Mmm")
        )

        assertEquals(listOf(1, 2, 3), seriesOrderFromLibrary(books, seriesId = 1))
    }

    /** 10 must follow 9, which is exactly what a text sort gets wrong. */
    @Test
    fun `orders positions numerically`() {
        val books = listOf(book(id = 10, position = 10.0), book(id = 9, position = 9.0))

        assertEquals(listOf(9, 10), seriesOrderFromLibrary(books, seriesId = 1))
    }

    @Test
    fun `ignores books of other series`() {
        val books = listOf(
            book(id = 1, seriesId = 1, position = 1.0),
            book(id = 2, seriesId = 2, position = 1.0),
            book(id = 3, seriesId = null)
        )

        assertEquals(listOf(1), seriesOrderFromLibrary(books, seriesId = 1))
    }

    /**
     * An unnumbered extra is a worse guess for "next" than any numbered volume,
     * so it sorts last rather than first.
     */
    @Test
    fun `books without a position sort last, by title`() {
        val books = listOf(
            book(id = 3, position = null, title = "Bonus B"),
            book(id = 2, position = null, title = "Bonus A"),
            book(id = 1, position = 1.0)
        )

        assertEquals(listOf(1, 2, 3), seriesOrderFromLibrary(books, seriesId = 1))
    }

    // --- position in the series ---------------------------------------------

    @Test
    fun `continues after the current book`() {
        assertEquals(listOf(3, 4), idsAfter(listOf(1, 2, 3, 4), currentId = 2))
    }

    @Test
    fun `nothing follows the last book`() {
        assertEquals(emptyList<Int>(), idsAfter(listOf(1, 2, 3), currentId = 3))
    }

    /**
     * A book that isn't in the list it was supposed to be part of gives no
     * answer, rather than starting the series over from the beginning.
     */
    @Test
    fun `a book missing from the order yields nothing`() {
        assertEquals(emptyList<Int>(), idsAfter(listOf(1, 2, 3), currentId = 99))
    }

    // --- where a continued book starts --------------------------------------

    @Test
    fun `an untouched book starts where it is`() {
        assertFalse(startsFromBeginning(startSec = 0.0, finished = false, durationSec = 10 * 3600.0))
    }

    @Test
    fun `a book stopped halfway keeps its bookmark`() {
        assertFalse(startsFromBeginning(startSec = 5 * 3600.0, finished = false, durationSec = 10 * 3600.0))
    }

    @Test
    fun `a book flagged finished starts over`() {
        assertTrue(startsFromBeginning(startSec = 0.0, finished = true, durationSec = 10 * 3600.0))
    }

    /**
     * The cascade guard: a book resting on its final seconds without ever having
     * been flagged starts over too. Otherwise it would be started, end
     * immediately, and hand on -- and so would the next, and the next.
     */
    @Test
    fun `a book resting on its last seconds starts over`() {
        assertTrue(startsFromBeginning(
            startSec = 10 * 3600.0 - 1.0, finished = false, durationSec = 10 * 3600.0))
    }

    @Test
    fun `a book a minute from the end does not start over`() {
        assertFalse(startsFromBeginning(
            startSec = 10 * 3600.0 - 60.0, finished = false, durationSec = 10 * 3600.0))
    }

    /** Zero-length metadata must not make every book start from the beginning. */
    @Test
    fun `a book with no duration keeps its position`() {
        assertFalse(startsFromBeginning(startSec = 120.0, finished = false, durationSec = 0.0))
    }

    // --- when the next volume is fetched -------------------------------------

    @Test
    fun `preparation waits until the end is in sight`() {
        val tenHours = 10 * 3600.0
        assertFalse(preparationDue(positionSec = 0.0, durationSec = tenHours))
        assertFalse(preparationDue(positionSec = tenHours - 301.0, durationSec = tenHours))
        assertTrue(preparationDue(positionSec = tenHours - 300.0, durationSec = tenHours))
        assertTrue(preparationDue(positionSec = tenHours, durationSec = tenHours))
    }

    /**
     * Without a duration there is no "near the end" -- and treating it as due
     * would mean fetching the next volume on every tick for the whole book.
     */
    @Test
    fun `a book of unknown length never becomes due`() {
        assertFalse(preparationDue(positionSec = 500.0, durationSec = 0.0))
    }

    // --- when the skip card is offered ---------------------------------------

    private fun cardAt(
        positionSec: Double,
        durationSec: Double = 10 * 3600.0,
        seriesMode: Boolean = true,
        hasPreparedNext: Boolean = true
    ) = skipCardDue(positionSec, durationSec, seriesMode, hasPreparedNext)

    @Test
    fun `the card appears in the last thirty seconds`() {
        val tenHours = 10 * 3600.0
        assertFalse(cardAt(tenHours - 31.0))
        assertTrue(cardAt(tenHours - 30.0))
        assertTrue(cardAt(tenHours))
    }

    /**
     * The card is the only sign the listener gets that the handover is ready,
     * so offering it without a prepared volume would be a lie -- and tapping it
     * would do nothing.
     */
    @Test
    fun `no card while nothing is prepared`() {
        assertFalse(cardAt(10 * 3600.0 - 10.0, hasPreparedNext = false))
    }

    @Test
    fun `no card when series mode is off`() {
        assertFalse(cardAt(10 * 3600.0 - 10.0, seriesMode = false))
    }

    @Test
    fun `no card for a book of unknown length`() {
        assertFalse(cardAt(10.0, durationSec = 0.0))
    }
}
