package app.fabula.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shower mode's steps, and the scale they are set on. */
class ShowerLevelTest {

    @Test
    fun `each named step is found by its own value`() {
        SHOWER_LEVELS.forEach { level -> assertEquals(level, showerLevelFor(level.db)) }
    }

    @Test
    fun `off is no named step`() {
        assertNull(showerLevelFor(0f))
    }

    @Test
    fun `a value between named steps has no name`() {
        assertNull(showerLevelFor(7f))
    }

    @Test
    fun `the named steps go up to the top of the scale`() {
        assertEquals(SHOWER_MAX_DB.toFloat(), SHOWER_LEVELS.last().db)
        assertEquals(SHOWER_LEVELS.sortedBy { it.db }, SHOWER_LEVELS)
    }

    // --- the scale ----------------------------------------------------------

    @Test
    fun `the scale reads the tick under the mark`() {
        assertEquals(0, scaleStepAt(0f, 40f))
        assertEquals(6, scaleStepAt(240f, 40f))
    }

    /** Between two ticks it reads the nearer, the way it settles. */
    @Test
    fun `between ticks the nearer one counts`() {
        assertEquals(6, scaleStepAt(255f, 40f))
        assertEquals(7, scaleStepAt(265f, 40f))
    }

    /** A flick past either end stops at the end. */
    @Test
    fun `the scale stops at its ends`() {
        assertEquals(0, scaleStepAt(-500f, 40f))
        assertEquals(SHOWER_MAX_DB, scaleStepAt(10_000f, 40f))
    }

    @Test
    fun `ticks fade towards the edges but never vanish`() {
        assertEquals(1f, tickAlpha(0f, 200f), 0.001f)
        assertEquals(0.5f, tickAlpha(100f, 200f), 0.001f)
        assertEquals(0.5f, tickAlpha(-100f, 200f), 0.001f)
        assertTrue(tickAlpha(400f, 200f) > 0f)
    }
}
