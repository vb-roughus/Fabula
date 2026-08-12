package app.fabula.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A bookmark the user created that the server hasn't accepted yet.
 *
 * [localId] is always negative and doubles as the id the UI displays, which is
 * how a pending entry is told apart from a server one without changing the type
 * the lists are built from: `id < 0` means "still ours".
 */
@Serializable
data class PendingBookmark(
    val localId: Int,
    val bookId: Int,
    val position: String,
    val note: String?,
    val createdAt: String
) {
    fun toDto(): BookmarkDto = BookmarkDto(
        id = localId,
        bookId = bookId,
        position = position,
        note = note,
        createdAt = createdAt
    )
}

@Serializable
data class PendingHighlight(
    val localId: Int,
    val bookId: Int,
    val start: String,
    val end: String,
    val title: String?,
    val note: String?,
    val createdAt: String
) {
    fun toDto(): HighlightDto = HighlightDto(
        id = localId,
        bookId = bookId,
        start = start,
        end = end,
        title = title,
        note = note,
        createdAt = createdAt
    )
}

@Serializable
private data class PendingFile(
    /** Next id to hand out; counts downwards so ids stay negative and unique. */
    val nextLocalId: Int = -1,
    val bookmarks: List<PendingBookmark> = emptyList(),
    val highlights: List<PendingHighlight> = emptyList()
)

/**
 * Bookmarks and highlights created while the server was out of reach.
 *
 * They used to be posted straight to the server with the failure swallowed, so
 * anything created offline was simply gone -- including the bookmark the sleep
 * timer sets on its own, which is exactly the one nobody is awake to notice.
 * They are now written here first and handed over later, and the lists show
 * them in the meantime so setting one offline isn't a silent no-op.
 */
class PendingUploadStore(context: Context, private val logStore: LogStore) {

    private val dir: File = File(context.filesDir, "pending").apply { mkdirs() }
    private val file: File = File(dir, "uploads.json")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val writeLock = Any()

    @Volatile
    private var data: PendingFile = load()

    /** Bumped on every change so the lists re-read. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun load(): PendingFile {
        if (!file.isFile) return PendingFile()
        return runCatching { json.decodeFromString<PendingFile>(file.readText()) }
            .onFailure { logStore.w("Pending", "uploads.json unlesbar: ${it.message}") }
            .getOrDefault(PendingFile())
    }

    private fun persist(next: PendingFile) {
        synchronized(writeLock) {
            data = next
            runCatching {
                val tmp = File(dir, "uploads.json.tmp")
                tmp.writeText(json.encodeToString(next))
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            }.onFailure { logStore.w("Pending", "uploads.json schreiben fehlgeschlagen", it) }
        }
        _revision.value = _revision.value + 1
    }

    /** ISO-8601 UTC, the shape the lists' date grouping parses. */
    private fun nowIso(): String =
        java.time.Instant.now().toString()

    fun addBookmark(bookId: Int, position: String, note: String?): PendingBookmark {
        val entry = PendingBookmark(
            localId = data.nextLocalId,
            bookId = bookId,
            position = position,
            note = note,
            createdAt = nowIso()
        )
        persist(
            data.copy(
                nextLocalId = data.nextLocalId - 1,
                bookmarks = data.bookmarks + entry
            )
        )
        return entry
    }

    fun addHighlight(
        bookId: Int,
        start: String,
        end: String,
        title: String?,
        note: String?
    ): PendingHighlight {
        val entry = PendingHighlight(
            localId = data.nextLocalId,
            bookId = bookId,
            start = start,
            end = end,
            title = title,
            note = note,
            createdAt = nowIso()
        )
        persist(
            data.copy(
                nextLocalId = data.nextLocalId - 1,
                highlights = data.highlights + entry
            )
        )
        return entry
    }

    fun bookmarksFor(bookId: Int): List<PendingBookmark> =
        data.bookmarks.filter { it.bookId == bookId }

    fun highlightsFor(bookId: Int): List<PendingHighlight> =
        data.highlights.filter { it.bookId == bookId }

    /** Oldest first, so entries reach the server in the order they were made. */
    fun allBookmarks(): List<PendingBookmark> = data.bookmarks.sortedBy { -it.localId }

    fun allHighlights(): List<PendingHighlight> = data.highlights.sortedBy { -it.localId }

    fun hasPending(): Boolean = data.bookmarks.isNotEmpty() || data.highlights.isNotEmpty()

    fun removeBookmark(localId: Int) {
        if (data.bookmarks.none { it.localId == localId }) return
        persist(data.copy(bookmarks = data.bookmarks.filterNot { it.localId == localId }))
    }

    fun removeHighlight(localId: Int) {
        if (data.highlights.none { it.localId == localId }) return
        persist(data.copy(highlights = data.highlights.filterNot { it.localId == localId }))
    }

    /** Edits a pending entry in place -- it has no server row to PATCH yet. */
    fun updateBookmarkNote(localId: Int, note: String?) {
        val existing = data.bookmarks.firstOrNull { it.localId == localId } ?: return
        persist(
            data.copy(
                bookmarks = data.bookmarks.map {
                    if (it.localId == localId) existing.copy(note = note) else it
                }
            )
        )
    }

    fun updateHighlight(localId: Int, title: String?, note: String?) {
        val existing = data.highlights.firstOrNull { it.localId == localId } ?: return
        persist(
            data.copy(
                highlights = data.highlights.map {
                    if (it.localId == localId) existing.copy(title = title, note = note) else it
                }
            )
        )
    }
}
