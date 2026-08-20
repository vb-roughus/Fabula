package app.fabula.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Sizes shown to the reader.
 *
 * There were two of these once, disagreeing about both the units and the
 * locale, so the same download could be reported as two different sizes on the
 * same screen. These pin down the one that survived.
 */
class FormatFileSizeTest {

    private lateinit var original: Locale

    @Before fun saveLocale() { original = Locale.getDefault() }
    @After fun restoreLocale() { Locale.setDefault(original) }

    @Test
    fun `counts in decimal units, matching its own labels`() {
        assertEquals("999 B", formatFileSize(999))
        assertEquals("1 KB", formatFileSize(1_000))
        assertEquals("1 MB", formatFileSize(1_000_000))
        assertEquals("1,0 GB", formatFileSize(1_000_000_000))
    }

    @Test
    fun `zero is a size, not a special case`() {
        assertEquals("0 B", formatFileSize(0))
    }

    /**
     * The surrounding text is German whatever the phone is set to, so the
     * decimal separator has to be as well -- a device in English must not put
     * "1.4 GB" in the middle of a German sentence.
     */
    @Test
    fun `stays German on a device set to English`() {
        Locale.setDefault(Locale.US)
        assertEquals("1,4 GB", formatFileSize(1_400_000_000))
    }

    @Test
    fun `stays German on a device set to German`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1,4 GB", formatFileSize(1_400_000_000))
    }
}
