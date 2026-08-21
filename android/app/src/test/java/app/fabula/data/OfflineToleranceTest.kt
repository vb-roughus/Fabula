package app.fabula.data

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
}
