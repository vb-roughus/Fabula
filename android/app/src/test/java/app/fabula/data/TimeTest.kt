package app.fabula.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeTest {

    private lateinit var original: Locale

    @Before fun saveLocale() { original = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(original) }

    @Test
    fun `parses plain hours minutes seconds`() {
        assertEquals(3661.0, parseTimeSpan("01:01:01"), 0.001)
    }

    @Test
    fun `parses fractional seconds`() {
        assertEquals(90.5, parseTimeSpan("00:01:30.5000000"), 0.001)
    }

    @Test
    fun `parses a leading day component`() {
        // 1.02:00:00 = one day plus two hours
        assertEquals(93600.0, parseTimeSpan("1.02:00:00"), 0.001)
    }

    @Test
    fun `null blank and malformed input read as zero`() {
        assertEquals(0.0, parseTimeSpan(null), 0.001)
        assertEquals(0.0, parseTimeSpan(""), 0.001)
        assertEquals(0.0, parseTimeSpan("   "), 0.001)
        assertEquals(0.0, parseTimeSpan("Unsinn"), 0.001)
    }

    /**
     * The one that matters: this string is sent to the server, and the default
     * locale decides the decimal separator. Under a comma locale the position
     * would be unparsable as a TimeSpan and every save would be rejected.
     */
    @Test
    fun `timespan output uses a dot even under a comma locale`() {
        Locale.setDefault(Locale.GERMANY)
        val formatted = toTimeSpanString(90.5)
        assertEquals("00:01:30.500", formatted)
        assertTrue("Darf kein Komma enthalten: $formatted", !formatted.contains(','))
    }

    @Test
    fun `round trip keeps the position to millisecond precision`() {
        Locale.setDefault(Locale.GERMANY)
        val seconds = 3725.125
        assertEquals(seconds, parseTimeSpan(toTimeSpanString(seconds)), 0.001)
    }

    @Test
    fun `negative positions are clamped to zero`() {
        assertEquals("00:00:00.000", toTimeSpanString(-5.0))
    }

    @Test
    fun `clock drops the hour part below an hour`() {
        assertEquals("0:05", formatClock(5.0))
        assertEquals("1:05", formatClock(65.0))
        assertEquals("1:01:05", formatClock(3665.0))
    }

    @Test
    fun `clock never shows a negative time`() {
        assertEquals("0:00", formatClock(-3.0))
    }

    @Test
    fun `human duration reads naturally at the boundaries`() {
        assertEquals("5 min", formatDurationHuman(300.0))
        assertEquals("2 h", formatDurationHuman(7200.0))
        assertEquals("2 h 5 min", formatDurationHuman(7500.0))
    }
}
