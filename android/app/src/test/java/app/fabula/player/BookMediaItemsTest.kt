package app.fabula.player

import app.fabula.data.AudioFileDto
import app.fabula.data.BookDetailDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping a position in a book onto the file that holds it.
 *
 * Two callers depend on this agreeing: opening a book, and answering the system
 * when it resumes playback into a fresh process after a headphone button press.
 * A disagreement there would restart a listener somewhere they never were, and
 * only on the path nobody watches.
 */
class BookMediaItemsTest {

    private fun file(id: Int, duration: String, offset: String) =
        AudioFileDto(id = id, trackIndex = id, duration = duration, offsetInBook = offset)

    private fun book(vararg files: AudioFileDto) =
        BookDetailDto(id = 1, title = "Buch", duration = "00:00:00", files = files.toList())

    @Test
    fun `accumulates file durations into start offsets`() {
        val b = book(
            file(1, duration = "00:01:40", offset = "00:00:00"),  // 100 s
            file(2, duration = "00:02:30", offset = "00:01:40"),  // 150 s
            file(3, duration = "00:00:30", offset = "00:04:10")
        )

        assertArrayEquals(doubleArrayOf(0.0, 100.0, 250.0), fileStartsOf(b), 0.001)
    }

    @Test
    fun `a book with no files has no starts`() {
        assertArrayEquals(doubleArrayOf(), fileStartsOf(book()), 0.001)
    }

    private val starts = doubleArrayOf(0.0, 100.0, 250.0)

    @Test
    fun `maps a position inside a file to that file and its offset`() {
        assertEquals(0 to 0L, mapBookPositionToMedia(starts, 0.0))
        assertEquals(0 to 50_000L, mapBookPositionToMedia(starts, 50.0))
        assertEquals(1 to 149_000L, mapBookPositionToMedia(starts, 249.0))
    }

    @Test
    fun `a position exactly on a boundary belongs to the file starting there`() {
        assertEquals(1 to 0L, mapBookPositionToMedia(starts, 100.0))
        assertEquals(2 to 0L, mapBookPositionToMedia(starts, 250.0))
    }

    /**
     * The tolerance earning its keep. The stored position comes from summing
     * file durations client-side, which can land a few microseconds short of a
     * boundary. Without the slack this would resume at the last millisecond of
     * the previous file -- audible as a stutter of the wrong words.
     */
    @Test
    fun `a hair before a boundary still belongs to the next file`() {
        assertEquals(1 to 0L, mapBookPositionToMedia(starts, 99.995))
        assertEquals(2 to 0L, mapBookPositionToMedia(starts, 249.999))
    }

    /** A few milliseconds earlier is genuinely still the previous file. */
    @Test
    fun `well before a boundary stays in the previous file`() {
        assertEquals(0 to 99_950L, mapBookPositionToMedia(starts, 99.95))
    }

    @Test
    fun `a negative position starts at the beginning`() {
        assertEquals(0 to 0L, mapBookPositionToMedia(starts, -30.0))
    }

    @Test
    fun `a position past the end lands in the last file`() {
        assertEquals(2, mapBookPositionToMedia(starts, 10_000.0).first)
    }

    @Test
    fun `no files means nothing to seek into`() {
        assertEquals(0 to 0L, mapBookPositionToMedia(doubleArrayOf(), 500.0))
    }
}
