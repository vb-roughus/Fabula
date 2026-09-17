package app.fabula.player

import app.fabula.data.BookDetailDto

/**
 * Seconds of slack at the end of a book that still count as "played to the end".
 *
 * A book that ran to its end but whose `finished` flag never reached the server
 * looks exactly like one resting on its last second, so both are judged the
 * same way.
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
 * Whether a book the series continuation is about to open should start at its
 * beginning instead of at the position it was left at.
 *
 * Series playback takes the next volume whatever its state, so it regularly
 * opens books that were heard before. Resuming those at their stored position
 * would land on the final seconds, end the book at once and hand on to the next
 * -- tearing through the rest of the series in seconds. Anything already at the
 * end therefore starts over; a book stopped in the middle keeps its bookmark.
 *
 * [startSec] and [finished] are the values playback would otherwise use, after
 * the local record and the server's have been reconciled.
 */
internal fun startsFromBeginning(startSec: Double, finished: Boolean, durationSec: Double): Boolean =
    finished || (durationSec > 0.0 && startSec >= durationSec - SERIES_END_SLACK_SEC)
