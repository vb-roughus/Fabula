package app.fabula.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Progress weighting and the percentage derived from it. The byte/duration
 * fallback is easy to get subtly wrong, and the result is a bar that lies.
 */
class DownloadProgressTest {

    private fun file(id: Int, duration: String, size: Long) =
        AudioFileDto(
            id = id,
            trackIndex = id,
            duration = duration,
            offsetInBook = "00:00:00",
            sizeBytes = size
        )

    @Test
    fun `sizes are summed when every file reports one`() {
        val files = listOf(
            file(1, "00:10:00", 1_000),
            file(2, "00:10:00", 2_500)
        )
        assertEquals(3_500L, downloadWeightOf(files))
    }

    /**
     * The important case: one missing size must switch the whole set to
     * duration weighting. Summing bytes with a zero in it would understate the
     * total, and the bar would run past 100 %.
     */
    @Test
    fun `a single missing size switches the set to duration weighting`() {
        val files = listOf(
            file(1, "00:01:00", 1_000),
            file(2, "00:02:00", 0)
        )
        // 60 s + 120 s, not 1000 bytes
        assertEquals(180L, downloadWeightOf(files))
    }

    @Test
    fun `an empty set weighs nothing`() {
        assertEquals(0L, downloadWeightOf(emptyList()))
    }

    @Test
    fun `percent uses bytes when a total is known`() {
        val state = BookDownloadState(
            bookId = 1,
            status = DownloadStatus.Running,
            doneBytes = 250,
            totalBytes = 1_000
        )
        assertEquals(25, state.percent)
    }

    /** Without byte totals it falls back to counting whole tracks. */
    @Test
    fun `percent falls back to track counting`() {
        val state = BookDownloadState(
            bookId = 1,
            status = DownloadStatus.Running,
            doneBytes = 0,
            totalBytes = 0,
            doneTracks = 3,
            totalTracks = 4
        )
        assertEquals(75, state.percent)
    }

    @Test
    fun `percent is zero when nothing is known`() {
        val state = BookDownloadState(bookId = 1, status = DownloadStatus.Queued)
        assertEquals(0, state.percent)
    }

    /** Guards against a bar overshooting if the sizes turn out understated. */
    @Test
    fun `percent is clamped to a hundred`() {
        val state = BookDownloadState(
            bookId = 1,
            status = DownloadStatus.Running,
            doneBytes = 5_000,
            totalBytes = 1_000
        )
        assertEquals(100, state.percent)
    }

    @Test
    fun `active covers exactly the states that are in the queue`() {
        fun state(s: DownloadStatus) = BookDownloadState(bookId = 1, status = s).active
        assertEquals(true, state(DownloadStatus.Queued))
        assertEquals(true, state(DownloadStatus.Running))
        assertEquals(true, state(DownloadStatus.Paused))
        // Partial means "interrupted, tap to resume" -- deliberately not active.
        assertEquals(false, state(DownloadStatus.Partial))
        assertEquals(false, state(DownloadStatus.Complete))
        assertEquals(false, state(DownloadStatus.Failed))
    }
}
