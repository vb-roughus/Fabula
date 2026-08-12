package app.fabula.ui.book

import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Three places depend on this answer -- the auto-scroll, the page-flip intro's
 * target and the highlight on the chapter row -- so a slip here shows up as
 * "the app scrolled to one chapter but marked another", which reads as a
 * rendering bug rather than a maths one.
 */
class ResumeChapterTest {

    private fun chapter(index: Int, start: String, end: String) =
        ChapterDto(index = index, title = "Kapitel ${index + 1}", start = start, end = end)

    private val threeChapters = listOf(
        chapter(0, "00:00:00", "00:10:00"),
        chapter(1, "00:10:00", "00:20:00"),
        chapter(2, "00:20:00", "00:30:00")
    )

    private fun book(chapters: List<ChapterDto> = threeChapters) =
        BookDetailDto(
            id = 1,
            title = "Buch",
            duration = "00:30:00",
            chapters = chapters,
            files = emptyList()
        )

    /** The live chapter wins over the stored position for the playing book. */
    @Test
    fun `active book follows the player`() {
        assertEquals(
            2,
            resumeChapterIndex(
                book = book(),
                isActiveBook = true,
                activeChapterIndex = 2,
                storedPositionSec = 30.0  // would map to chapter 0
            )
        )
    }

    /**
     * A book that is not playing still has to answer, otherwise nothing gets
     * marked while browsing -- which is exactly the regression this guards.
     */
    @Test
    fun `idle book derives the chapter from the stored position`() {
        assertEquals(
            1,
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 15 * 60.0
            )
        )
    }

    /**
     * The player is loaded but has no chapter yet (still preparing): fall back
     * to the stored position rather than reporting "not started".
     */
    @Test
    fun `active book without a live chapter falls back to the stored position`() {
        assertEquals(
            2,
            resumeChapterIndex(
                book = book(),
                isActiveBook = true,
                activeChapterIndex = null,
                storedPositionSec = 25 * 60.0
            )
        )
    }

    /** A fresh book sits at 0 and must not mark its first chapter. */
    @Test
    fun `unstarted book resolves to nothing`() {
        assertNull(
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 0.0
            )
        )
        // Sub-second positions count as unstarted too: a stray save at 0.4s
        // shouldn't look like a resumed book.
        assertNull(
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 0.4
            )
        )
    }

    /**
     * Exactly on a boundary belongs to the chapter that starts there. The 10 ms
     * probe offset the codebase uses elsewhere is what makes this hold; without
     * it a position stored a hair under the boundary would mark the previous
     * chapter.
     */
    @Test
    fun `a position on a chapter boundary picks the starting chapter`() {
        assertEquals(
            1,
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 10 * 60.0
            )
        )
        assertEquals(
            1,
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 10 * 60.0 - 0.005
            )
        )
    }

    /** Past the last chapter's end there is nothing to mark. */
    @Test
    fun `a position beyond the last chapter resolves to nothing`() {
        assertNull(
            resumeChapterIndex(
                book = book(),
                isActiveBook = false,
                activeChapterIndex = null,
                storedPositionSec = 40 * 60.0
            )
        )
    }

    /** No chapters means no answer, even while that book is playing. */
    @Test
    fun `a book without chapters resolves to nothing`() {
        assertNull(
            resumeChapterIndex(
                book = book(chapters = emptyList()),
                isActiveBook = true,
                activeChapterIndex = 0,
                storedPositionSec = 15 * 60.0
            )
        )
    }
}
