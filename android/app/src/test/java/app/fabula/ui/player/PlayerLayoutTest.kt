package app.fabula.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full player has a single layout in every orientation: the controls take
 * the room they need, the cover what is left. These pin down what "what is
 * left" turns into.
 */
class PlayerLayoutTest {

    /** Upright phone: the width is the limit, the cover spans it. */
    @Test
    fun `the cover is the largest square that fits`() {
        assertEquals(364.dp, playerCoverSide(width = 364.dp, height = 420.dp))
        assertEquals(300.dp, playerCoverSide(width = 512.dp, height = 300.dp))
    }

    /** Phone held sideways: a thumbnail is still worth drawing. */
    @Test
    fun `a small leftover still shows a thumbnail`() {
        assertEquals(48.dp, playerCoverSide(width = 512.dp, height = 48.dp))
        assertEquals(PLAYER_MIN_COVER, playerCoverSide(width = 512.dp, height = PLAYER_MIN_COVER))
    }

    /** Less than a thumbnail is left out rather than drawn as a smudge. */
    @Test
    fun `too little room leaves the cover out`() {
        assertEquals(0.dp, playerCoverSide(width = 512.dp, height = 24.dp))
        assertEquals(0.dp, playerCoverSide(width = 512.dp, height = 0.dp))
    }

    @Test
    fun `phones held sideways count as short, tablets and upright phones don't`() {
        assertTrue(playerIsShort(360.dp))
        assertFalse(playerIsShort(740.dp))
        assertFalse(playerIsShort(800.dp))
    }
}
