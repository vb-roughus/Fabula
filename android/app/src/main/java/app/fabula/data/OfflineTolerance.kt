package app.fabula.data

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * How forgiving the app is before it declares itself offline.
 *
 * Switching between Wi-Fi and mobile data kills the sockets bound to the old
 * interface. For a second or two any request in flight fails, and then the new
 * network works perfectly well. Treating that first failure as "the server is
 * unreachable" cost the user their connection until they pressed a button --
 * for a hiccup that had already healed itself.
 */

/**
 * A failure this quick is an interface that went away, not a server that isn't
 * answering. Anything slower has had time to actually try.
 */
internal const val HANDOVER_FAST_FAILURE_MS = 2_000L

/** Long enough for Android to finish promoting the new default network. */
internal const val HANDOVER_RETRY_DELAY_MS = 400L

/**
 * Whether a failed request deserves one more attempt before the app latches
 * itself offline.
 *
 * Three conditions, each earning its place:
 *
 * - it has to be a transport failure, the only kind a network change produces;
 * - it has to have failed *fast*. A connect timeout means the server had ten
 *   seconds and said nothing, so a second attempt only doubles the wait --
 *   and a socket timeout mid-transfer is not a handover either;
 * - the device has to have a usable network right now. Without one there is
 *   nothing to retry over, and being offline is the honest answer.
 */
internal fun shouldRetryBeforeLatching(
    failure: Throwable,
    elapsedMs: Long,
    hasNetwork: Boolean
): Boolean {
    if (!hasNetwork) return false
    if (failure !is IOException) return false
    if (failure is SocketTimeoutException) return false
    return elapsedMs < HANDOVER_FAST_FAILURE_MS
}
