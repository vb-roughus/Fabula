package app.fabula.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which step of shower mode a stored boost is ticked as. */
class ShowerLevelTest {

    @Test
    fun `each step is found by its own value`() {
        SHOWER_LEVELS.forEach { level -> assertEquals(level, showerLevelFor(level.db)) }
    }

    @Test
    fun `off is no step`() {
        assertNull(showerLevelFor(0f))
    }

    /** A value from the old slider between two steps ticks neither, rather than a wrong one. */
    @Test
    fun `a value between steps ticks none`() {
        assertNull(showerLevelFor(7f))
    }

    @Test
    fun `the steps go up to the most the boost allows`() {
        assertEquals(15f, SHOWER_LEVELS.last().db)
        assertEquals(SHOWER_LEVELS.sortedBy { it.db }, SHOWER_LEVELS)
    }
}
