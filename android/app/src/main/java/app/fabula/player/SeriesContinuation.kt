package app.fabula.player

import app.fabula.data.BookDetailDto
import app.fabula.data.LocalProgress
import app.fabula.data.parseTimeSpan

/**
 * Seconds of slack at the end of a book that still count as heard.
 *
 * A book that ran to its end but whose `finished` flag never reached the server
 * looks exactly like one resting on its last second. Without this, continuing a
 * series would restart such a book, end it again at once, and move on -- racing
 * through the remainder in seconds.
 */
internal const val SERIES_END_SLACK_SEC = 5.0

/**
 * The books of a series in reading order, derived from whatever is on the
 * device. Used when the server can't be reached: the downloaded manifests carry
 * `seriesId` and `seriesPosition` themselves, so an offline device can still
 * continue through the part of the series it holds.
 *
 * Books without a position sort last rather than first -- an unnumbered extra is
 * a worse guess for "next" than any numbered volume.
 */
internal fun seriesOrderFromLibrary(books: List<BookDetailDto>, seriesId: Int): List<Int> =
    books
        .filter { it.seriesId == seriesId }
        .sortedWith(compareBy({ it.seriesPosition ?: Double.MAX_VALUE }, { it.title }))
        .map { it.id }

/** The ids following [currentId], or nothing when it isn't in the list at all. */
internal fun idsAfter(order: List<Int>, currentId: Int): List<Int> {
    val at = order.indexOf(currentId)
    return if (at < 0) emptyList() else order.drop(at + 1)
}

/**
 * Whether a book has been heard already, and should therefore be skipped when
 * continuing a series.
 *
 * Prefers an unsynced local record over the server's -- the same rule
 * `loadBook` follows, so the decision matches the position playback would
 * actually resume at.
 */
internal fun alreadyHeard(book: BookDetailDto, local: LocalProgress?): Boolean {
    if (local != null && !local.synced) {
        return local.finished || restsAtEnd(local.positionSec, book)
    }
    if (book.progress?.finished == true || local?.finished == true) return true
    val stored = book.progress?.let { parseTimeSpan(it.position) } ?: local?.positionSec ?: 0.0
    return restsAtEnd(stored, book)
}

private fun restsAtEnd(positionSec: Double, book: BookDetailDto): Boolean {
    val duration = parseTimeSpan(book.duration)
    return duration > 0.0 && positionSec >= duration - SERIES_END_SLACK_SEC
}
