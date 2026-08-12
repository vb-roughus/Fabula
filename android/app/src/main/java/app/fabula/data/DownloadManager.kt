package app.fabula.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException

/**
 * [Paused] means "in the queue, waiting for an allowed network"; [Partial]
 * means "interrupted, nothing running, tap to resume". Keeping them apart is
 * what lets the download button pick the right action.
 */
enum class DownloadStatus { Queued, Running, Paused, Partial, Complete, Failed }

/**
 * Progress of one book's offline sync. [percent] prefers real byte sizes and
 * falls back to duration weighting when the server didn't report sizes.
 */
data class BookDownloadState(
    val bookId: Int,
    val title: String = "",
    val status: DownloadStatus,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneTracks: Int = 0,
    val totalTracks: Int = 0,
    val doneFileIds: Set<Int> = emptySet(),
    /** The track being fetched right now, so the UI can mark that row busy. */
    val currentFileId: Int? = null,
    /** How far that one track has come, 0..1. Null while unknown -- the row
     *  then spins indeterminately instead of showing a ring stuck at zero. */
    val currentFileProgress: Float? = null,
    val error: String? = null
) {
    val percent: Int
        get() = when {
            totalBytes > 0 -> ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            totalTracks > 0 -> ((doneTracks * 100) / totalTracks).coerceIn(0, 100)
            else -> 0
        }

    val active: Boolean
        get() = status == DownloadStatus.Queued || status == DownloadStatus.Running ||
            status == DownloadStatus.Paused
}

/**
 * Downloads whole audiobooks for offline playback, one track at a time.
 *
 * A single worker coroutine drains [queue] so only one file is ever in flight
 * -- that matches the "track by track" requirement and keeps memory and the
 * connection calm. Partial files are resumed with an HTTP Range request; the
 * streaming endpoint enables range processing.
 */
class DownloadManager(
    private val context: Context,
    private val repository: FabulaRepository,
    private val offlineStore: OfflineStore,
    private val logStore: LogStore,
    private val scope: CoroutineScope,
    /** Brings the foreground service up. Injected so this stays in the data
     *  layer instead of reaching into `player`. */
    private val startForegroundService: () -> Unit
) {

    private val _states = MutableStateFlow<Map<Int, BookDownloadState>>(emptyMap())
    val states: StateFlow<Map<Int, BookDownloadState>> = _states.asStateFlow()

    /** Set when at least one book is queued or downloading -- the foreground
     *  service watches this to know when to start and stop itself. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val queue = ArrayDeque<BookDetailDto>()
    private val lock = Any()
    private var worker: Job? = null

    fun stateFor(bookId: Int): BookDownloadState? = _states.value[bookId]

    /** Restores "already complete" state for a book the user opens. */
    fun refreshFromDisk(book: BookDetailDto) {
        val existing = _states.value[book.id]
        if (existing != null && existing.active) return

        // A rescan hands out new file ids; the old files are unusable.
        if (offlineStore.isStale(book)) {
            offlineStore.deleteBook(book.id)
            update(book.id) { null }
            return
        }

        val done = offlineStore.downloadedFileIds(book.id)
        if (done.isEmpty()) {
            update(book.id) { null }
            return
        }
        val complete = book.files.isNotEmpty() && book.files.all { it.id in done }
        update(book.id) {
            BookDownloadState(
                bookId = book.id,
                title = book.title,
                status = if (complete) DownloadStatus.Complete else DownloadStatus.Partial,
                doneBytes = weightOf(book.files.filter { it.id in done }),
                totalBytes = weightOf(book.files),
                doneTracks = done.size,
                totalTracks = book.files.size,
                doneFileIds = done
            )
        }
    }

    fun enqueue(book: BookDetailDto) {
        if (book.files.isEmpty()) return
        synchronized(lock) {
            if (queue.any { it.id == book.id }) return
            queue.addLast(book)
        }
        val done = offlineStore.downloadedFileIds(book.id)
        update(book.id) {
            BookDownloadState(
                bookId = book.id,
                title = book.title,
                status = DownloadStatus.Queued,
                doneBytes = weightOf(book.files.filter { it.id in done }),
                totalBytes = weightOf(book.files),
                doneTracks = done.size,
                totalTracks = book.files.size,
                doneFileIds = done
            )
        }
        offlineStore.writeManifest(book)
        startForegroundService()
        ensureWorker()
    }

    fun cancel(bookId: Int) {
        synchronized(lock) { queue.removeAll { it.id == bookId } }
        val wasActive = _states.value[bookId]?.active == true
        if (wasActive && currentBookId == bookId) {
            worker?.cancel()
            worker = null
        }
        offlineStore.deleteParts(bookId)
        refreshBusy()
        // Keep whatever finished tracks exist; drop the running state.
        val done = offlineStore.downloadedFileIds(bookId)
        if (done.isEmpty()) update(bookId) { null }
        else update(bookId) { it?.copy(status = DownloadStatus.Partial) }
        if (wasActive) ensureWorker()
    }

    /** Stops everything in flight but keeps whatever finished tracks exist. */
    fun cancelAll() {
        val ids = _states.value.values.filter { it.active }.map { it.bookId }
        synchronized(lock) { queue.clear() }
        worker?.cancel()
        worker = null
        currentBookId = null
        ids.forEach { id ->
            offlineStore.deleteParts(id)
            val done = offlineStore.downloadedFileIds(id)
            if (done.isEmpty()) update(id) { null }
            else update(id) { it?.copy(status = DownloadStatus.Partial, error = null) }
        }
        refreshBusy()
    }

    fun remove(bookId: Int) {
        cancel(bookId)
        offlineStore.deleteBook(bookId)
        update(bookId) { null }
    }

    fun removeAll() {
        synchronized(lock) { queue.clear() }
        worker?.cancel()
        worker = null
        offlineStore.deleteAll()
        _states.value = emptyMap()
        refreshBusy()
    }

    @Volatile
    private var currentBookId: Int? = null

    private fun ensureWorker() {
        refreshBusy()
        if (worker?.isActive == true) return
        worker = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val book = synchronized(lock) { queue.firstOrNull() } ?: break
            currentBookId = book.id
            try {
                downloadBook(book)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logStore.e("Download", "book=${book.id} failed", t)
                update(book.id) { it?.copy(status = DownloadStatus.Failed, error = t.message) }
            }
            currentBookId = null
            synchronized(lock) { queue.removeAll { it.id == book.id } }
        }
        currentBookId = null
        worker = null
        refreshBusy()
    }

    private suspend fun downloadBook(book: BookDetailDto) {
        val api = repository.apiOrNull() ?: run {
            update(book.id) { it?.copy(status = DownloadStatus.Failed, error = "Kein Server konfiguriert.") }
            return
        }

        val total = weightOf(book.files)
        var doneIds = offlineStore.downloadedFileIds(book.id).toMutableSet()

        // Pre-flight: refuse up front rather than dying half-way through.
        val needed = book.files.filter { it.id !in doneIds }.sumOf { it.sizeBytes }
        if (needed > 0 && offlineStore.usableSpace() < needed + SPACE_HEADROOM) {
            update(book.id) {
                it?.copy(
                    status = DownloadStatus.Failed,
                    error = "Zu wenig Speicherplatz (${formatFileSize(needed)} benötigt)"
                )
            }
            return
        }
        update(book.id) {
            (it ?: BookDownloadState(book.id, book.title, DownloadStatus.Running)).copy(
                status = DownloadStatus.Running,
                title = book.title,
                totalBytes = total,
                totalTracks = book.files.size,
                doneTracks = doneIds.size,
                doneFileIds = doneIds,
                doneBytes = weightOf(book.files.filter { f -> f.id in doneIds }),
                error = null
            )
        }

        // Best-effort and deliberately non-fatal: a missing cover must not
        // fail the book download.
        runCatching { downloadCoverIfMissing(api, book) }

        for (file in book.files.sortedBy { it.trackIndex }) {
            if (file.id in doneIds) continue

            awaitAllowedNetwork(book.id)

            update(book.id) { it?.copy(currentFileId = file.id, currentFileProgress = null) }
            val baseDone = weightOf(book.files.filter { it.id in doneIds })
            downloadTrack(api, book, file, baseDone)

            doneIds = offlineStore.downloadedFileIds(book.id).toMutableSet()
            update(book.id) {
                it?.copy(
                    doneFileIds = doneIds,
                    doneTracks = doneIds.size,
                    doneBytes = weightOf(book.files.filter { f -> f.id in doneIds }),
                    currentFileId = null,
                    currentFileProgress = null
                )
            }
        }

        val complete = book.files.all { it.id in doneIds }
        update(book.id) { current ->
            current?.copy(
                status = if (complete) DownloadStatus.Complete else DownloadStatus.Partial,
                doneBytes = if (complete) total else current.doneBytes,
                currentFileId = null,
                currentFileProgress = null
            )
        }
        offlineStore.reindex()
    }

    private suspend fun downloadCoverIfMissing(api: FabulaApi, book: BookDetailDto) {
        if (offlineStore.localCover(book.id) != null) return
        if (book.coverUrl == null) return
        withContext(Dispatchers.IO) {
            val response = api.downloadCover(book.id)
            if (!response.isSuccessful) return@withContext
            val body = response.body() ?: return@withContext
            val ext = OfflineStore.coverExtensionFor(response.headers()["Content-Type"])
            val target = offlineStore.coverFile(book.id, ext)
            body.use { rb ->
                rb.byteStream().use { input ->
                    java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            offlineStore.reindex()
        }
    }

    private suspend fun downloadTrack(
        api: FabulaApi,
        book: BookDetailDto,
        file: AudioFileDto,
        baseDone: Long
    ) = withContext(Dispatchers.IO) {
        val existing = offlineStore.existingPart(book.id, file.id)
        val resumeFrom = existing?.length() ?: 0L
        val range = if (resumeFrom > 0) "bytes=$resumeFrom-" else null

        val response = api.downloadAudioFile(file.id, range)
        if (response.code() == 416) {
            // Range beyond the file: the .part is bogus, start clean.
            existing?.delete()
            throw IOException("Teildatei ungültig für Track ${file.trackIndex + 1}")
        }
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code()} für Track ${file.trackIndex + 1}")
        }
        val body = response.body() ?: throw IOException("Leere Antwort für Track ${file.trackIndex + 1}")

        // 206 means the server honoured the range; anything else restarts.
        val resumed = response.code() == 206 && resumeFrom > 0
        val ext = OfflineStore.extensionFor(response.headers()["Content-Type"])
        val part = if (resumed && existing != null) existing else {
            existing?.delete()
            offlineStore.partFile(book.id, file.id, ext)
        }

        val trackWeight = weightOf(listOf(file))
        var written = if (resumed) resumeFrom else 0L
        var lastEmit = 0L
        var lastBytes = written

        // Size of THIS track, for the per-track ring. The declared size is best;
        // failing that the response's own Content-Length works, remembering that
        // on a resumed 206 it only covers the remaining bytes. 0 means unknown,
        // and the UI then shows an indeterminate spinner rather than a ring
        // frozen at zero.
        val bodyLength = body.contentLength()
        val trackTotal = when {
            file.sizeBytes > 0 -> file.sizeBytes
            bodyLength >= 0 -> (if (resumed) resumeFrom else 0L) + bodyLength
            else -> 0L
        }

        body.use { rb ->
            rb.byteStream().use { input ->
                java.io.FileOutputStream(part, /* append = */ resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // The read below is blocking and not itself a
                        // cancellation point, so check explicitly -- otherwise
                        // "Abbrechen" wouldn't take effect until the transfer
                        // finished.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read

                        // Throttle: emitting per 8 KB chunk would flood recomposition.
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= EMIT_INTERVAL_MS || written - lastBytes >= EMIT_BYTES) {
                            lastEmit = now
                            lastBytes = written
                            val fraction = if (trackTotal > 0) {
                                (written.toDouble() / trackTotal).coerceIn(0.0, 1.0)
                            } else null
                            val partial = ((fraction ?: 0.0) * trackWeight).toLong()
                            update(book.id) {
                                it?.copy(
                                    doneBytes = baseDone + partial,
                                    currentFileProgress = fraction?.toFloat()
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        if (offlineStore.commitPart(part) == null) {
            throw IOException("Konnte Track ${file.trackIndex + 1} nicht ablegen")
        }
    }

    /**
     * Blocks while downloads aren't allowed on the current network. With
     * "Wi-Fi only" on, a switch to mobile parks the download (the `.part` file
     * stays) and it resumes as soon as Wi-Fi is back.
     */
    private suspend fun awaitAllowedNetwork(bookId: Int) {
        var announced = false
        while (!networkAllowed()) {
            if (!announced) {
                announced = true
                update(bookId) {
                    it?.copy(status = DownloadStatus.Paused, error = "Wartet auf WLAN")
                }
            }
            delay(NETWORK_POLL_MS)
        }
        if (announced) {
            update(bookId) { it?.copy(status = DownloadStatus.Running, error = null) }
        }
    }

    private suspend fun networkAllowed(): Boolean {
        val wifiOnly = repository.downloadWifiOnly.first()
        if (!wifiOnly) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Byte size when the server reported it for *every* file, otherwise
     * seconds of audio -- both are just weights, so the percentage works out
     * either way. Requiring all sizes avoids a skewed total when only some
     * tracks carry a size (older server, partially rescanned library).
     */
    private fun weightOf(files: List<AudioFileDto>): Long =
        if (files.isNotEmpty() && files.all { it.sizeBytes > 0 }) files.sumOf { it.sizeBytes }
        else files.sumOf { parseTimeSpan(it.duration).toLong() }

    private fun update(bookId: Int, transform: (BookDownloadState?) -> BookDownloadState?) {
        val current = _states.value
        val next = transform(current[bookId])
        _states.value = if (next == null) current - bookId else current + (bookId to next)
        refreshBusy()
    }

    private fun refreshBusy() {
        _busy.value = _states.value.values.any {
            it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running
        }
    }

    private companion object {
        const val EMIT_INTERVAL_MS = 250L
        const val EMIT_BYTES = 512L * 1024L
        const val NETWORK_POLL_MS = 3_000L
        const val SPACE_HEADROOM = 50L * 1024L * 1024L
    }
}

/** Human-readable byte size, e.g. "1,2 GB". */
fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.GERMAN, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(java.util.Locale.GERMAN, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(java.util.Locale.GERMAN, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
