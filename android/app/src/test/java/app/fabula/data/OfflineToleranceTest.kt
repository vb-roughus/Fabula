package app.fabula.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * When a failed request earns a second attempt instead of putting the app
 * offline.
 *
 * Both directions cost the user something, which is why the rule is worth
 * pinning down. Too strict and a two-second Wi-Fi handover ends playback until
 * they press a button. Too lax and a genuinely unreachable server doubles every
 * timeout while pretending it might come back.
 */
class OfflineToleranceTest {

    private val hasNetwork = true
    private val noNetwork = false

    /**
     * The classic handover signature: the DNS resolver was bound to the
     * interface that just disappeared, so the lookup fails at once.
     */
    @Test
    fun `retries a fast lookup failure while a network is present`() {
        assertTrue(
            shouldRetryBeforeLatching(UnknownHostException("fabula.local"), 40L, hasNetwork)
        )
    }

    /** A socket dying with the interface underneath it, likewise. */
    @Test
    fun `retries a fast socket failure while a network is present`() {
        assertTrue(shouldRetryBeforeLatching(SocketException("Software caused abort"), 120L, hasNetwork))
    }

    /**
     * With no network there is nothing to retry over, and "offline" is simply
     * the truth. Retrying anyway would delay an honest answer.
     */
    @Test
    fun `does not retry when the device has no network`() {
        assertFalse(shouldRetryBeforeLatching(UnknownHostException("fabula.local"), 40L, noNetwork))
    }

    /**
     * A slow failure means the request had its chance: the server was given ten
     * seconds and said nothing. A second attempt only doubles the wait before
     * the user learns the truth.
     */
    @Test
    fun `does not retry a slow failure`() {
        assertFalse(shouldRetryBeforeLatching(IOException("connect timed out"), 10_000L, hasNetwork))
    }

    /** A timeout is never a handover, however quickly it is reported. */
    @Test
    fun `does not retry a socket timeout`() {
        assertFalse(shouldRetryBeforeLatching(SocketTimeoutException("read timed out"), 50L, hasNetwork))
    }

    /**
     * Only transport failures qualify. Anything else is a bug in our own code,
     * and repeating it would just hide it behind a delay.
     */
    @Test
    fun `does not retry a non-transport failure`() {
        assertFalse(shouldRetryBeforeLatching(IllegalStateException("closed"), 10L, hasNetwork))
    }

    @Test
    fun `the fast-failure window is inclusive of what came just before it`() {
        val io = IOException("reset")
        assertTrue(shouldRetryBeforeLatching(io, HANDOVER_FAST_FAILURE_MS - 1, hasNetwork))
        assertFalse(shouldRetryBeforeLatching(io, HANDOVER_FAST_FAILURE_MS, hasNetwork))
    }

    /**
     * The retry pause has to outlast Android promoting the replacement network,
     * while staying short enough not to be felt.
     */
    @Test
    fun `the retry pause is short but not instant`() {
        assertTrue(HANDOVER_RETRY_DELAY_MS in 200L..1_000L)
    }

    // --- who gets a vote ----------------------------------------------------

    /**
     * The four-second progress worker is the busiest requester in the app and
     * the least worth reporting: it saves locally and catches up later. Letting
     * it declare the whole app offline is what made a weak connection feel like
     * a broken one.
     */
    @Test
    fun `a failed progress write does not declare the app offline`() {
        assertFalse(failureCountsAsOffline("POST", "/api/progress/7"))
        assertFalse(failureCountsAsOffline("PUT", "/api/progress/7"))
    }

    /**
     * Reading it is a different matter: the home screen is waiting for that
     * list, so the user is waiting too, and they should be told.
     */
    @Test
    fun `a failed progress read still counts`() {
        assertTrue(failureCountsAsOffline("GET", "/api/progress/in-progress"))
    }

    /** Created only by the upload worker, from a durable queue. */
    @Test
    fun `failed uploads of bookmarks and highlights do not count`() {
        assertFalse(failureCountsAsOffline("POST", "/api/bookmarks"))
        assertFalse(failureCountsAsOffline("POST", "/api/highlights"))
    }

    /**
     * But listing them, or deleting one, happens with somebody looking at the
     * screen.
     */
    @Test
    fun `reading and deleting bookmarks still counts`() {
        assertTrue(failureCountsAsOffline("GET", "/api/bookmarks"))
        assertTrue(failureCountsAsOffline("DELETE", "/api/bookmarks/3"))
        assertTrue(failureCountsAsOffline("GET", "/api/highlights"))
    }

    /**
     * The startup update check runs unasked, and an older server answers 404
     * for it. Neither is a reason to tell anyone the app is offline.
     */
    @Test
    fun `a failed update check does not count`() {
        assertFalse(failureCountsAsOffline("GET", "/api/app/version"))
    }

    /** Offline downloads report their own state per book already. */
    @Test
    fun `failed download traffic does not count`() {
        assertFalse(failureCountsAsOffline("GET", "/api/stream/42"))
        assertFalse(failureCountsAsOffline("GET", "/api/books/42/cover"))
    }

    /**
     * Everything a screen actually waits for keeps its say -- the point was to
     * stop background noise voting, not to stop reporting outages.
     */
    @Test
    fun `everything a screen waits for still counts`() {
        assertTrue(failureCountsAsOffline("GET", "/api/books"))
        assertTrue(failureCountsAsOffline("GET", "/api/books/42"))
        assertTrue(failureCountsAsOffline("GET", "/api/series"))
        assertTrue(failureCountsAsOffline("POST", "/api/auth/login"))
        assertTrue(failureCountsAsOffline("GET", "/api/auth/me"))
    }

    /** Methods arrive in whatever case the client used. */
    @Test
    fun `the method is compared without regard to case`() {
        assertFalse(failureCountsAsOffline("post", "/api/bookmarks"))
        assertTrue(failureCountsAsOffline("get", "/api/books"))
    }

    // --- how long before trying again ---------------------------------------

    /**
     * Short first, because most outages are a lift or a tunnel; long later,
     * because a server that has been gone for minutes is not worth asking every
     * five seconds.
     */
    @Test
    fun `the waiting time grows and then levels off`() {
        val delays = (0..5).map { offlineRetryDelayMs(it) }

        assertEquals(5_000L, delays.first())
        assertEquals(300_000L, delays.last())
        // Never shrinks along the way.
        assertEquals(delays.sorted(), delays)
    }

    /** A step past the end keeps the longest wait rather than crashing. */
    @Test
    fun `an out of range step is clamped`() {
        assertEquals(300_000L, offlineRetryDelayMs(99))
        assertEquals(5_000L, offlineRetryDelayMs(-1))
    }

    /**
     * The colour has to outlast the first retry, or an outage that heals itself
     * still repaints the app -- which is the whole complaint.
     */
    @Test
    fun `the display waits longer than the first retry`() {
        assertTrue(OFFLINE_DISPLAY_DELAY_MS > offlineRetryDelayMs(0))
    }
}
