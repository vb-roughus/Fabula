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

// --- how long the app stays offline before trying again ---------------------

/**
 * Waiting times before the app quietly tries the server again, in order.
 *
 * The offline latch used to be left for the user to clear. Its purpose was
 * never to make anyone press a button, though -- it was to stop hammering a
 * dead server and to stop the display flickering between states. A backoff
 * does both and still heals on its own.
 *
 * Starts short, because most outages are a lift or a tunnel, and ends long,
 * because a server that has been down for five minutes is not worth asking
 * every five seconds.
 */
internal val OFFLINE_RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 60_000, 300_000)

internal fun offlineRetryDelayMs(step: Int): Long =
    OFFLINE_RETRY_DELAYS_MS[step.coerceIn(0, OFFLINE_RETRY_DELAYS_MS.lastIndex)]

/**
 * Counted failures in a row before the app calls itself offline.
 *
 * One timeout on a patchy connection is noise, not a verdict. The single
 * retry above only covers failures that come back fast; a connect timeout
 * takes its full ten seconds and used to be enough on its own.
 */
internal const val OFFLINE_FAILURE_THRESHOLD = 2

/**
 * How long "offline" has to hold before the interface says so.
 *
 * Longer than the first retry above on purpose: a blip that heals itself
 * never gets to repaint the app orange.
 */
internal const val OFFLINE_DISPLAY_DELAY_MS = 6_000L

/**
 * Whether a failed request gets a say in declaring the app offline.
 *
 * Some traffic runs with nobody waiting for it: the progress sync fires every
 * four seconds while listening, uploads retry from a durable queue, downloads
 * grind away in the background. All of it already survives failure by design --
 * and all of it used to be able to flip the whole app to offline, orange theme
 * and all, for a hiccup nobody would otherwise have noticed. On a patchy
 * connection that is most of what happens.
 *
 * Reads keep their say: when a screen is waiting for a list, the user is
 * waiting too, and they should be told.
 *
 * Failure only. A *successful* background request still clears the offline
 * state, because that is proof, and proof should count wherever it comes from.
 */
internal fun failureCountsAsOffline(method: String, path: String): Boolean {
    val write = !method.equals("GET", ignoreCase = true)
    return when {
        // The four-second progress worker, and the reset dialog that goes
        // through the same call and already ignores its own failure.
        write && path.startsWith("/api/progress") -> false
        // Created only by the upload worker; the screens hand it a pending
        // entry and never call the server themselves.
        method.equals("POST", ignoreCase = true) &&
            (path == "/api/bookmarks" || path == "/api/highlights") -> false
        // Offline downloads: the download manager's own business, and it
        // reports its state per book already.
        path.startsWith("/api/stream/") -> false
        path.startsWith("/api/books/") && path.endsWith("/cover") -> false
        else -> true
    }
}
