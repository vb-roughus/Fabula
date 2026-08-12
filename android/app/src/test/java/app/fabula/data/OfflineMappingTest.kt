package app.fabula.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMappingTest {

    @Test
    fun `audio content types map to sensible extensions`() {
        assertEquals("mp3", OfflineStore.extensionFor("audio/mpeg"))
        assertEquals("m4a", OfflineStore.extensionFor("audio/mp4"))
        assertEquals("m4a", OfflineStore.extensionFor("audio/x-m4a"))
        assertEquals("flac", OfflineStore.extensionFor("audio/flac"))
        assertEquals("opus", OfflineStore.extensionFor("audio/opus"))
    }

    @Test
    fun `unknown or missing audio type falls back without throwing`() {
        assertEquals("bin", OfflineStore.extensionFor(null))
        assertEquals("bin", OfflineStore.extensionFor("application/octet-stream"))
    }

    /** The server's mime lookup doesn't know .m4b, so this path is real. */
    @Test
    fun `content type matching ignores case and parameters`() {
        assertEquals("mp3", OfflineStore.extensionFor("AUDIO/MPEG"))
        assertEquals("mp3", OfflineStore.extensionFor("audio/mpeg; charset=binary"))
    }

    @Test
    fun `cover content types map to image extensions`() {
        assertEquals("jpg", OfflineStore.coverExtensionFor("image/jpeg"))
        assertEquals("png", OfflineStore.coverExtensionFor("image/png"))
        assertEquals("webp", OfflineStore.coverExtensionFor("image/webp"))
        assertEquals("img", OfflineStore.coverExtensionFor(null))
    }

    /**
     * A queued entry must keep its negative id: that sign is what the rows use
     * to route delete and edit to the local queue instead of to the server.
     */
    @Test
    fun `a pending bookmark keeps its negative id when shown`() {
        val pending = PendingBookmark(
            localId = -3,
            bookId = 7,
            position = "00:10:00",
            note = "Notiz",
            createdAt = "2026-01-01T00:00:00Z"
        )
        val dto = pending.toDto()
        assertEquals(-3, dto.id)
        assertEquals(7, dto.bookId)
        assertEquals("00:10:00", dto.position)
        assertEquals("Notiz", dto.note)
        assertTrue("Negative id markiert den Eintrag als noch nicht übertragen", dto.id < 0)
    }

    @Test
    fun `a pending highlight keeps its range and negative id`() {
        val dto = PendingHighlight(
            localId = -9,
            bookId = 4,
            start = "00:01:00",
            end = "00:02:00",
            title = "Titel",
            note = null,
            createdAt = "2026-01-01T00:00:00Z"
        ).toDto()
        assertEquals(-9, dto.id)
        assertEquals("00:01:00", dto.start)
        assertEquals("00:02:00", dto.end)
        assertEquals("Titel", dto.title)
    }

    @Test
    fun `file sizes read as human units`() {
        assertEquals("0 B", formatFileSize(0))
        assertTrue(formatFileSize(2_500).endsWith("KB"))
        assertTrue(formatFileSize(5_000_000).endsWith("MB"))
        assertTrue(formatFileSize(3_000_000_000).endsWith("GB"))
    }

    /** BookDetailDto narrowed for the tiles must keep what they render. */
    @Test
    fun `book detail narrows to a summary without losing display fields`() {
        val detail = BookDetailDto(
            id = 11,
            title = "Titel",
            subtitle = "Untertitel",
            authors = listOf("Autorin"),
            duration = "02:00:00",
            coverUrl = "/api/books/11/cover",
            libraryFolderName = "Hörbücher",
            progress = ProgressSummaryDto(position = "00:30:00", finished = false)
        )
        val summary = detail.toSummary()
        assertEquals(11, summary.id)
        assertEquals("Titel", summary.title)
        assertEquals("Untertitel", summary.subtitle)
        assertEquals(listOf("Autorin"), summary.authors)
        assertEquals("02:00:00", summary.duration)
        assertEquals("/api/books/11/cover", summary.coverUrl)
        assertEquals("Hörbücher", summary.libraryFolderName)
        assertEquals("00:30:00", summary.progress?.position)
    }
}
