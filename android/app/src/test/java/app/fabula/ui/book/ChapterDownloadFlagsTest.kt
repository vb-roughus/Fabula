package app.fabula.ui.book

import app.fabula.data.AudioFileDto
import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter/track overlap maths is the piece most likely to go wrong quietly:
 * it decides which chapters show the offline tick and which show the spinner,
 * and a boundary slip would look like a rendering quirk rather than a bug.
 */
class ChapterDownloadFlagsTest {

    private fun chapter(index: Int, start: String, end: String) =
        ChapterDto(index = index, title = "Kapitel ${index + 1}", start = start, end = end)

    private fun file(id: Int, trackIndex: Int, offset: String, duration: String, size: Long = 1000) =
        AudioFileDto(
            id = id,
            trackIndex = trackIndex,
            duration = duration,
            offsetInBook = offset,
            sizeBytes = size
        )

    private fun book(chapters: List<ChapterDto>, files: List<AudioFileDto>) =
        BookDetailDto(
            id = 1,
            title = "Buch",
            duration = "01:00:00",
            chapters = chapters,
            files = files
        )

    /**
     * A single-file m4b with many chapters: one track covers all of them, so
     * they must flip together. This is the case where per-chapter marking is
     * least intuitive, which is exactly why it's pinned down here.
     */
    @Test
    fun `single file covering every chapter marks them all at once`() {
        val b = book(
            chapters = listOf(
                chapter(0, "00:00:00", "00:10:00"),
                chapter(1, "00:10:00", "00:20:00"),
                chapter(2, "00:20:00", "00:30:00")
            ),
            files = listOf(file(id = 7, trackIndex = 0, offset = "00:00:00", duration = "00:30:00"))
        )

        val none = offlineChapterFlags(b, emptySet(), null)
        assertArrayEquals(booleanArrayOf(false, false, false), none.offline)

        val all = offlineChapterFlags(b, setOf(7), null)
        assertArrayEquals(booleanArrayOf(true, true, true), all.offline)
    }

    /** A track per chapter fills in progressively as each one lands. */
    @Test
    fun `one track per chapter marks only the downloaded ones`() {
        val b = book(
            chapters = listOf(
                chapter(0, "00:00:00", "00:10:00"),
                chapter(1, "00:10:00", "00:20:00"),
                chapter(2, "00:20:00", "00:30:00")
            ),
            files = listOf(
                file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"),
                file(id = 2, trackIndex = 1, offset = "00:10:00", duration = "00:10:00"),
                file(id = 3, trackIndex = 2, offset = "00:20:00", duration = "00:10:00")
            )
        )

        val flags = offlineChapterFlags(b, setOf(1, 3), null)
        assertArrayEquals(booleanArrayOf(true, false, true), flags.offline)
    }

    /**
     * Chapter boundaries line up exactly with track boundaries, and floating
     * point comparison there is what the 10 ms tolerance exists for: without it
     * chapter 0 would also count track 2 as overlapping and stay unmarked.
     */
    @Test
    fun `exact boundary does not leak into the neighbouring track`() {
        val b = book(
            chapters = listOf(
                chapter(0, "00:00:00", "00:10:00"),
                chapter(1, "00:10:00", "00:20:00")
            ),
            files = listOf(
                file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"),
                file(id = 2, trackIndex = 1, offset = "00:10:00", duration = "00:10:00")
            )
        )

        val onlyFirst = offlineChapterFlags(b, setOf(1), null)
        assertTrue("Kapitel 1 liegt vollständig in Track 1", onlyFirst.offline[0])
        assertFalse("Kapitel 2 gehört zu Track 2", onlyFirst.offline[1])
    }

    /** A chapter spanning two tracks needs both before it counts as offline. */
    @Test
    fun `chapter spanning two tracks needs both`() {
        val b = book(
            chapters = listOf(chapter(0, "00:00:00", "00:20:00")),
            files = listOf(
                file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"),
                file(id = 2, trackIndex = 1, offset = "00:10:00", duration = "00:10:00")
            )
        )

        assertFalse(offlineChapterFlags(b, setOf(1), null).offline[0])
        assertFalse(offlineChapterFlags(b, setOf(2), null).offline[0])
        assertTrue(offlineChapterFlags(b, setOf(1, 2), null).offline[0])
    }

    @Test
    fun `the currently downloading track marks its chapters as loading`() {
        val b = book(
            chapters = listOf(
                chapter(0, "00:00:00", "00:10:00"),
                chapter(1, "00:10:00", "00:20:00")
            ),
            files = listOf(
                file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"),
                file(id = 2, trackIndex = 1, offset = "00:10:00", duration = "00:10:00")
            )
        )

        val flags = offlineChapterFlags(b, setOf(1), currentFileId = 2)
        assertArrayEquals(booleanArrayOf(true, false), flags.offline)
        assertArrayEquals(booleanArrayOf(false, true), flags.loading)
    }

    /** Once a chapter is complete the tick wins; it must not also spin. */
    @Test
    fun `a complete chapter is not reported as loading`() {
        val b = book(
            chapters = listOf(chapter(0, "00:00:00", "00:10:00")),
            files = listOf(file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"))
        )

        val flags = offlineChapterFlags(b, setOf(1), currentFileId = 1)
        assertTrue(flags.offline[0])
        assertFalse(flags.loading[0])
    }

    /**
     * Some servers report every offset as 00:00:00. The running-sum fallback is
     * what keeps such a book from marking every chapter off the first track.
     */
    @Test
    fun `missing offsets fall back to a running sum`() {
        val b = book(
            chapters = listOf(
                chapter(0, "00:00:00", "00:10:00"),
                chapter(1, "00:10:00", "00:20:00")
            ),
            files = listOf(
                file(id = 1, trackIndex = 0, offset = "00:00:00", duration = "00:10:00"),
                file(id = 2, trackIndex = 1, offset = "00:00:00", duration = "00:10:00")
            )
        )

        val flags = offlineChapterFlags(b, setOf(1), null)
        assertTrue(flags.offline[0])
        assertFalse("Ohne den Laufsummen-Fallback wäre auch Kapitel 2 markiert", flags.offline[1])
    }

    @Test
    fun `a book without files marks nothing`() {
        val b = book(
            chapters = listOf(chapter(0, "00:00:00", "00:10:00")),
            files = emptyList()
        )
        assertArrayEquals(booleanArrayOf(false), offlineChapterFlags(b, setOf(1), 1).offline)
    }

    @Test
    fun `a book without chapters yields empty arrays`() {
        val b = book(chapters = emptyList(), files = listOf(file(1, 0, "00:00:00", "00:10:00")))
        val flags = offlineChapterFlags(b, setOf(1), null)
        assertTrue(flags.offline.isEmpty())
        assertTrue(flags.loading.isEmpty())
    }
}
