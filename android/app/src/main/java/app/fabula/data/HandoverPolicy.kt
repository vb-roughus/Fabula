package app.fabula.data

/** What to do with a queued upload the server did not take. */
internal enum class HandoverOutcome {
    /** Keep it, and everything behind it, for the next attempt. */
    RetryLater,

    /** Drop this one and carry on with the rest of the queue. */
    GiveUp
}

/**
 * Whether a failed handover -- progress, a bookmark, a highlight -- is worth
 * trying again.
 *
 * The queues hand entries over oldest first and stop at the first failure, so
 * that an outage keeps everything in order for later. That is right for a
 * failure that passes. One that never passes -- the book is gone from the
 * server, the request is refused -- would otherwise stop the queue for good:
 * everything recorded after it, the book being listened to now included,
 * would never reach the server, with nothing to show for it.
 *
 * [status] is the HTTP status, or null when there was no answer at all.
 */
internal fun afterFailedHandover(status: Int?): HandoverOutcome = when (status) {
    null -> HandoverOutcome.RetryLater           // no answer: offline, timed out
    401 -> HandoverOutcome.RetryLater            // signed out; signing in again sends it
    408, 429 -> HandoverOutcome.RetryLater       // timed out, or asked to slow down
    in 400..499 -> HandoverOutcome.GiveUp        // gone or refused: it will never be taken
    else -> HandoverOutcome.RetryLater           // the server's trouble passes
}

/** The HTTP status behind a failed call, or null when it never got an answer. */
internal fun httpStatusOf(failure: Throwable): Int? = (failure as? retrofit2.HttpException)?.code()
