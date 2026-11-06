package app.fabula.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which failed handovers wait for another attempt and which are dropped.
 * The wrong answer either way is costly: dropping too eagerly loses progress
 * to a passing outage; keeping too much blocks the whole queue for good.
 */
class HandoverPolicyTest {

    @Test
    fun `no answer at all waits for the next attempt`() {
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(null))
    }

    /** The reported case: progress for a book the server no longer has. */
    @Test
    fun `a book the server no longer has is dropped`() {
        assertEquals(HandoverOutcome.GiveUp, afterFailedHandover(404))
    }

    @Test
    fun `a refused request is dropped`() {
        assertEquals(HandoverOutcome.GiveUp, afterFailedHandover(400))
        assertEquals(HandoverOutcome.GiveUp, afterFailedHandover(403))
        assertEquals(HandoverOutcome.GiveUp, afterFailedHandover(409))
        assertEquals(HandoverOutcome.GiveUp, afterFailedHandover(422))
    }

    @Test
    fun `being signed out waits for signing in again`() {
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(401))
    }

    @Test
    fun `a timeout or a request to slow down waits`() {
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(408))
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(429))
    }

    @Test
    fun `the server's own trouble waits`() {
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(500))
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(502))
        assertEquals(HandoverOutcome.RetryLater, afterFailedHandover(503))
    }
}
