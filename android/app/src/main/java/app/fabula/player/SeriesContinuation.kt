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

/**
 * How long before the end of a book the next volume is fetched and turned into
 * a playable queue.
 *
 * Everything the handover needs -- the book detail, the stream URLs, the cover
 * bytes for the car display -- is network work, and the end of a book is the
 * worst possible moment to do it: the listener may be in a tunnel, on a dying
 * mobile connection, or halfway through a Wi-Fi handover. Preparing minutes
 * ahead turns the handover itself into a local operation.
 */
internal const val SERIES_PREPARE_LEAD_SEC = 300.0

/** How long a failed preparation waits before it is tried again. */
internal const val SERIES_PREPARE_RETRY_MS = 20_000L

/** How often the preparation watchdog looks at the remaining time. */
internal const val SERIES_PREPARE_POLL_MS = 5_000L

/**
 * The closing stretch of a book during which the "next volume" card offers to
 * skip ahead. It appears only once the next volume is actually prepared, so it
 * doubles as the answer to "did it load?".
 */
const val SERIES_SKIP_WINDOW_SEC = 30.0

/**
 * Whether the next volume should be fetched now. A book of unknown length never
 * qualifies -- without a duration there is no "near the end" to speak of, and
 * preparing on every tick would hammer the server for the whole book.
 */
internal fun preparationDue(positionSec: Double, durationSec: Double): Boolean =
    durationSec > 0.0 && durationSec - positionSec <= SERIES_PREPARE_LEAD_SEC

/**
 * Whether the skip card belongs on screen.
 *
 * [hasPreparedNext] is the load-bearing part: the card is also the listener's
 * only indication that the handover is ready, so it must never appear for a
 * volume that has not been fetched.
 */
fun skipCardDue(
    positionSec: Double,
    durationSec: Double,
    seriesMode: Boolean,
    hasPreparedNext: Boolean
): Boolean {
    if (!seriesMode || !hasPreparedNext || durationSec <= 0.0) return false
    val remaining = durationSec - positionSec
    return remaining >= 0.0 && remaining <= SERIES_SKIP_WINDOW_SEC
}
