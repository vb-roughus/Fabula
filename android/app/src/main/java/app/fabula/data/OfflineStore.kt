package app.fabula.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the downloaded audio files on disk.
 *
 * Layout: `filesDir/offline/book-<bookId>/<fileId>.<ext>`, with a `.part`
 * suffix while a download is in flight. A finished file is moved into place
 * with an atomic rename, so a file without `.part` is always complete -- the
 * disk itself is the source of truth and no database is needed.
 *
 * Each book directory also holds a `manifest.json` that embeds the **full**
 * [BookDetailDto]. That is what makes the feature actually offline: the book
 * screen and the player both need the detail payload, and without a cached
 * copy a downloaded book could not even be opened without a server.
 */
class OfflineStore(context: Context, private val logStore: LogStore) {

    private val root: File = File(context.filesDir, "offline").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * fileId -> completed file, across all books (AudioFile ids are global).
     * Read from ExoPlayer's loading thread on every `open()`, so it is a
     * plain immutable map swapped wholesale -- never a growable mutable one.
     */
    @Volatile
    private var index: Map<Int, File> = emptyMap()

    private val _downloadedFileIds = MutableStateFlow<Set<Int>>(emptySet())
    /** Drives the per-chapter offline ticks on the book screen. */
    val downloadedFileIds: StateFlow<Set<Int>> = _downloadedFileIds.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    /**
     * bookId -> true when every track of that book is on disk, false when only
     * some are. Lets the library and home tiles show a download badge without
     * each of them reading manifests from disk.
     */
    private val _downloadedBooks = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val downloadedBooks: StateFlow<Map<Int, Boolean>> = _downloadedBooks.asStateFlow()

    /** Bumped whenever files appear or disappear, so Compose can re-read. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reindex()
    }

    private fun bookDir(bookId: Int): File = File(root, "book-$bookId")

    private fun completedFilesIn(dir: File): List<File> =
        dir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(PART_SUFFIX) &&
                    it.name != MANIFEST && it.length() > 0
            }
            ?: emptyList()

    /** Rebuilds the in-memory index from disk. Cheap: a couple of dir listings. */
    fun reindex() {
        val map = HashMap<Int, File>()
        val books = HashMap<Int, Boolean>()
        var bytes = 0L
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val present = HashSet<Int>()
            completedFilesIn(dir).forEach { f ->
                f.name.substringBefore('.').toIntOrNull()?.let { id ->
                    map[id] = f
                    present += id
                }
                bytes += f.length()
            }
            // The manifest knows the full track list, so "complete" can be
            // decided here instead of every tile working it out again.
            val bookId = dir.name.removePrefix("book-").toIntOrNull()
            if (bookId != null && present.isNotEmpty()) {
                val expected = readManifest(bookId)?.book?.files?.map { it.id }
                books[bookId] = expected != null && expected.isNotEmpty() &&
                    expected.all { it in present }
            }
        }
        index = map
        _downloadedFileIds.value = map.keys.toSet()
        _downloadedBooks.value = books
        _totalBytes.value = bytes
        _revision.value = _revision.value + 1
    }

    // --- hot read paths (must not block) ------------------------------------

    fun localFile(fileId: Int): File? = index[fileId]?.takeIf { it.isFile }

    /**
     * Maps a `…/api/stream/<id>` URI onto a downloaded file, or null for any
     * other URI (covers, APK) so unrelated requests pass straight through.
     */
    fun localFileForStreamUri(uri: Uri): File? {
        val path = uri.path ?: return null
        if (!path.contains("/api/stream/")) return null
        val id = uri.lastPathSegment?.substringBefore('?')?.toIntOrNull() ?: return null
        return localFile(id)
    }

    fun downloadedFileIds(bookId: Int): Set<Int> =
        completedFilesIn(bookDir(bookId))
            .mapNotNull { it.name.substringBefore('.').toIntOrNull() }
            .toSet()

    // --- manifest / cached book detail --------------------------------------

    fun readManifest(bookId: Int): OfflineManifest? {
        val f = File(bookDir(bookId), MANIFEST)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<OfflineManifest>(f.readText()) }
            .onFailure { logStore.w("Offline", "manifest book=$bookId unlesbar: ${it.message}") }
            .getOrNull()
    }

    /** The cached book detail -- lets a downloaded book open with no network. */
    fun cachedBook(bookId: Int): BookDetailDto? = readManifest(bookId)?.book

    fun writeManifest(book: BookDetailDto) {
        val dir = bookDir(book.id).apply { mkdirs() }
        val manifest = OfflineManifest(bookId = book.id, book = book)
        runCatching {
            // Write to a temp file and rename, so a kill mid-write can't leave
            // a truncated manifest behind.
            val tmp = File(dir, "$MANIFEST.tmp")
            tmp.writeText(json.encodeToString(manifest))
            if (File(dir, MANIFEST).exists()) File(dir, MANIFEST).delete()
            tmp.renameTo(File(dir, MANIFEST))
        }.onFailure { logStore.w("Offline", "manifest book=${book.id} schreiben fehlgeschlagen", it) }
    }

    /** Refreshes the cached detail when the book dir already exists. */
    fun refreshCachedBook(book: BookDetailDto) {
        if (bookDir(book.id).isDirectory) writeManifest(book)
    }

    /**
     * True when the stored files no longer line up with what the server now
     * reports. A rescan re-creates AudioFile rows with fresh ids, so matching
     * on id alone would silently keep useless files around.
     */
    fun isStale(book: BookDetailDto): Boolean {
        val downloaded = downloadedFileIds(book.id)
        if (downloaded.isEmpty()) return false
        val current = book.files.map { it.id }.toSet()
        return downloaded.any { it !in current }
    }

    // --- write paths --------------------------------------------------------

    /** The in-flight file for this track. */
    fun partFile(bookId: Int, fileId: Int, ext: String): File {
        val dir = bookDir(bookId).apply { mkdirs() }
        return File(dir, "$fileId.$ext$PART_SUFFIX")
    }

    /** Any leftover `.part` for this track, whatever extension it started with. */
    fun existingPart(bookId: Int, fileId: Int): File? {
        val dir = bookDir(bookId)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(PART_SUFFIX) &&
                it.name.substringBefore('.') == fileId.toString()
        }
    }

    /** Atomically promotes a finished `.part` to its final name. */
    fun commitPart(part: File): File? {
        val target = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) return null
        reindex()
        return target
    }

    fun bookBytes(bookId: Int): Long =
        completedFilesIn(bookDir(bookId)).sumOf { it.length() }

    /** Every book that has a manifest on disk, by title. */
    fun listDownloadedBooks(): List<OfflineManifest> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> dir.name.removePrefix("book-").toIntOrNull()?.let { readManifest(it) } }
            ?.sortedBy { it.book.title.lowercase() }
            ?: emptyList()

    fun deleteBook(bookId: Int) {
        bookDir(bookId).deleteRecursively()
        reindex()
    }

    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        reindex()
    }

    /** Drops half-finished downloads for a book (used when the user cancels). */
    fun deleteParts(bookId: Int) {
        val dir = bookDir(bookId)
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    /** Free space available for downloads. */
    fun usableSpace(): Long = runCatching { root.usableSpace }.getOrDefault(Long.MAX_VALUE)

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val MANIFEST = "manifest.json"

        /** Maps a response content type onto a file extension. Cosmetic only --
         *  ExoPlayer sniffs the container -- but it keeps the folder readable. */
        fun extensionFor(contentType: String?): String {
            val t = contentType?.lowercase() ?: return "bin"
            return when {
                t.contains("mpeg") -> "mp3"
                t.contains("mp4") || t.contains("m4a") || t.contains("m4b") -> "m4a"
                t.contains("aac") -> "aac"
                t.contains("opus") -> "opus"
                t.contains("ogg") -> "ogg"
                t.contains("flac") -> "flac"
                t.contains("wav") -> "wav"
                else -> "bin"
            }
        }
    }
}

@Serializable
data class OfflineManifest(
    val version: Int = 1,
    val bookId: Int,
    /** Full detail payload, so the book opens and plays without a server. */
    val book: BookDetailDto
)
