package app.fabula.ui.book

import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- Tapping a chapter: carry on or start over ---------------------------

    /** The marked chapter picks up where the listener left off. */
    @Test
    fun `tapping the chapter holding the position carries on`() {
        assertTrue(continuesInChapter(754.0, finished = false, chapterStartSec = 600.0, chapterEndSec = 1200.0))
    }

    /** Any other chapter is an explicit choice to hear it from its start. */
    @Test
    fun `tapping another chapter starts it from the beginning`() {
        assertFalse(continuesInChapter(754.0, finished = false, chapterStartSec = 0.0, chapterEndSec = 600.0))
        assertFalse(continuesInChapter(754.0, finished = false, chapterStartSec = 1200.0, chapterEndSec = 1800.0))
    }

    /** A position resting on a boundary belongs to the chapter that begins there. */
    @Test
    fun `a position on the boundary belongs to the following chapter`() {
        assertTrue(continuesInChapter(599.995, finished = false, chapterStartSec = 600.0, chapterEndSec = 1200.0))
        assertFalse(continuesInChapter(599.995, finished = false, chapterStartSec = 0.0, chapterEndSec = 600.0))
    }

    /** Carrying on two seconds before the end would only clip into the next chapter. */
    @Test
    fun `the last seconds of a chapter start it over`() {
        assertFalse(continuesInChapter(1199.0, finished = false, chapterStartSec = 600.0, chapterEndSec = 1200.0))
        assertTrue(continuesInChapter(1197.0, finished = false, chapterStartSec = 600.0, chapterEndSec = 1200.0))
    }

    /** A finished book has nothing to carry on from. */
    @Test
    fun `a finished book starts the chapter over`() {
        assertFalse(continuesInChapter(754.0, finished = true, chapterStartSec = 600.0, chapterEndSec = 1200.0))
    }
}
