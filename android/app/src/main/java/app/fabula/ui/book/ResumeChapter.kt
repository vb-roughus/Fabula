package app.fabula.ui.book

import app.fabula.data.BookDetailDto
import app.fabula.data.parseTimeSpan

/**
 * The chapter playback would continue at, or null when the book hasn't been
 * started.
 *
 * Three places need this answer and used to work it out separately: the
 * auto-scroll, the page-flip intro's target, and the highlight on the chapter
 * row. They must agree -- scrolling to one chapter while marking another looks
 * like a bug -- so the rule lives here once.
 *
 * When the book is the one playing, the live chapter wins. Otherwise it is
 * derived from the stored position, with the same 10 ms tolerance the rest of
 * the codebase uses around chapter boundaries.
 */
internal fun resumeChapterIndex(
    book: BookDetailDto,
    isActiveBook: Boolean,
    activeChapterIndex: Int?,
    storedPositionSec: Double
): Int? {
    if (book.chapters.isEmpty()) return null
    if (isActiveBook && activeChapterIndex != null) return activeChapterIndex

    // Below a second counts as "not started": a fresh book sits at 0 and should
    // not mark its first chapter as though it were resumed.
    if (storedPositionSec <= 1.0) return null

    val probe = storedPositionSec + 0.010
    return book.chapters.indexOfFirst { c ->
        probe >= parseTimeSpan(c.start) && probe < parseTimeSpan(c.end)
    }.takeIf { it >= 0 }
}
