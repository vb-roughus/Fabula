package app.fabula.player

import app.fabula.data.BookDetailDto
import app.fabula.data.LocalProgress
import app.fabula.data.ProgressSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which book "Serie hören" continues with.
 *
 * The dangerous branch is the skipping rule. Playback resumes at a book's stored
 * position, so treating an already-heard book as unheard would start it, end it
 * at once, and continue from there -- tearing through the rest of the series in
 * seconds. Erring the other way silently skips a book the listener wanted.
 */
class SeriesContinuationTest {

    private fun book(
        id: Int,
        seriesId: Int? = 1,
        position: Double? = null,
        title: String = "Band $id",
        duration: String = "10:00:00",
        progressPosition: String? = null,
        finished: Boolean = false
    ) = BookDetailDto(
        id = id,
        title = title,
        duration = duration,
        seriesId = seriesId,
        seriesPosition = position,
        progress = progressPosition?.let {
            ProgressSummaryDto(position = it, finished = finished, updatedAt = "2026-01-01T00:00:00Z")
        } ?: if (finished) {
            ProgressSummaryDto(position = "00:00:00", finished = true, updatedAt = "2026-01-01T00:00:00Z")
        } else null
    )

    private fun local(
        id: Int,
        positionSec: Double,
        finished: Boolean = false,
        synced: Boolean = false
    ) = LocalProgress(
        bookId = id,
        positionSec = positionSec,
        finished = finished,
        updatedAtMs = 1_000L,
        synced = synced
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

    // --- the skipping rule --------------------------------------------------

    @Test
    fun `an untouched book counts as unheard`() {
        assertFalse(alreadyHeard(book(id = 2), local = null))
    }

    @Test
    fun `a book flagged finished on the server counts as heard`() {
        assertTrue(alreadyHeard(book(id = 2, finished = true), local = null))
    }

    @Test
    fun `a book flagged finished locally counts as heard`() {
        assertTrue(alreadyHeard(book(id = 2), local = local(2, 120.0, finished = true)))
    }

    @Test
    fun `a book stopped halfway is still to be heard`() {
        val partly = book(id = 2, duration = "10:00:00", progressPosition = "05:00:00")
        assertFalse(alreadyHeard(partly, local = null))
    }

    /**
     * The cascade guard: a book resting on its final seconds without ever having
     * been flagged is treated as heard. Otherwise it would be started, end
     * immediately, and hand on -- and so would the next, and the next.
     */
    @Test
    fun `a book resting on its last seconds counts as heard`() {
        val atEnd = book(id = 2, duration = "10:00:00", progressPosition = "09:59:58")
        assertTrue(alreadyHeard(atEnd, local = null))

        val localAtEnd = book(id = 2, duration = "10:00:00")
        assertTrue(alreadyHeard(localAtEnd, local = local(2, 10 * 3600.0 - 1.0)))
    }

    @Test
    fun `a book a minute from the end is not yet heard`() {
        val nearlyDone = book(id = 2, duration = "10:00:00", progressPosition = "09:59:00")
        assertFalse(alreadyHeard(nearlyDone, local = null))
    }

    /**
     * An unsynced local record is newer than the server's by definition -- the
     * same rule loadBook applies. Here the server still believes the book was
     * finished while this device has since restarted it, so it is to be heard.
     */
    @Test
    fun `an unsynced local record beats the server`() {
        val serverSaysDone = book(id = 2, duration = "10:00:00", finished = true)

        assertFalse(alreadyHeard(serverSaysDone, local = local(2, 60.0, finished = false)))
    }

    /**
     * Once synced, the two agree and the server value is used -- so a stale
     * local position must not resurrect a finished book.
     */
    @Test
    fun `a synced local record does not override the server`() {
        val serverSaysDone = book(id = 2, duration = "10:00:00", finished = true)

        assertTrue(alreadyHeard(serverSaysDone, local = local(2, 60.0, synced = true)))
    }

    /** Zero-length metadata must not make every book look finished. */
    @Test
    fun `a book with no duration is not treated as heard`() {
        assertFalse(alreadyHeard(book(id = 2, duration = "00:00:00"), local = null))
    }
}
