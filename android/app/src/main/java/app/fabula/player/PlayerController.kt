package app.fabula.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import app.fabula.data.FabulaRepository
import app.fabula.data.UpdateProgressRequest
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PlayerUiState(
    val book: BookDetailDto? = null,
    val isPlaying: Boolean = false,
    val positionInBook: Double = 0.0,
    val durationInBook: Double = 0.0,
    val currentChapter: ChapterDto? = null
)

/**
 * Thin wrapper around a Media3 MediaController. Maps book-wide position to
 * the ExoPlayer's per-MediaItem position, pushes progress to the server on a
 * timer, and exposes a simple StateFlow for the UI.
 */
class PlayerController(
    private val context: Context,
    private val repository: FabulaRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Cumulative start second of each MediaItem, matching BookDetail.files order. */
    private var fileStarts: DoubleArray = DoubleArray(0)

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            startPolling()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        pollJob?.cancel()
        progressJob?.cancel()
        controller?.release()
        controller = null
        scope.cancel()
    }

    suspend fun loadBook(book: BookDetailDto) {
        val c = controller ?: return
        val api = repository.apiOrNull()

        val starts = DoubleArray(book.files.size)
        var acc = 0.0
        book.files.forEachIndexed { i, f ->
            starts[i] = acc
            acc += parseTimeSpan(f.duration)
        }
        fileStarts = starts

        val items = book.files.map { f ->
            val url = repository.streamUrl(f.id) ?: return@map null
            MediaItem.Builder()
                .setMediaId("book-${book.id}-file-${f.id}")
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(book.authors.joinToString(", "))
                        .setAlbumTitle(book.series ?: book.title)
                        .setArtworkUri(repository.coverUrl(book)?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
        }.filterNotNull()

        if (items.isEmpty()) return

        val savedPosition = runCatching { api?.getProgress(book.id)?.position }.getOrNull()
        val startSec = parseTimeSpan(savedPosition)
        val (startIndex, startOffsetMs) = mapBookToMedia(startSec)

        c.setMediaItems(items, startIndex, startOffsetMs)
        c.prepare()

        _state.value = PlayerUiState(
            book = book,
            isPlaying = false,
            positionInBook = startSec,
            durationInBook = parseTimeSpan(book.duration),
            currentChapter = chapterAt(book, startSec)
        )
    }

    fun play() { controller?.play() }
    fun pause() { controller?.pause() }
    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekInBook(seconds: Double) {
        val c = controller ?: return
        val (index, offsetMs) = mapBookToMedia(seconds)
        c.seekTo(index, offsetMs)
        updateStateFromController()
    }

    fun skip(seconds: Double) {
        seekInBook(state.value.positionInBook + seconds)
    }

    fun setSpeed(rate: Float) {
        controller?.setPlaybackSpeed(rate)
    }

    fun jumpToChapter(chapter: ChapterDto) {
        seekInBook(parseTimeSpan(chapter.start))
    }

    private fun mapBookToMedia(seconds: Double): Pair<Int, Long> {
        if (fileStarts.isEmpty()) return 0 to 0L
        val clamped = max(0.0, seconds)
        var index = fileStarts.size - 1
        for (i in fileStarts.indices) {
            val nextStart = if (i + 1 < fileStarts.size) fileStarts[i + 1] else Double.MAX_VALUE
            if (clamped < nextStart) { index = i; break }
        }
        val localMs = ((clamped - fileStarts[index]) * 1000.0).toLong().coerceAtLeast(0L)
        return index to localMs
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateStateFromController() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateStateFromController() }
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (true) {
                updateStateFromController()
                delay(500)
            }
        }
        progressJob = scope.launch {
            var lastSaved = -10.0
            var lastBookId = -1
            while (true) {
                delay(4000)
                val s = _state.value
                val book = s.book ?: continue
                if (book.id == lastBookId && abs(lastSaved - s.positionInBook) < 2.0) continue
                val api = repository.apiOrNull() ?: continue
                val finished = s.positionInBook >= s.durationInBook - 1
                runCatching {
                    api.saveProgress(
                        book.id,
                        UpdateProgressRequest(
                            position = toTimeSpanString(s.positionInBook),
                            finished = finished,
                            device = repository.deviceId()
                        )
                    )
                }
                lastSaved = s.positionInBook
                lastBookId = book.id
            }
        }
    }

    private fun updateStateFromController() {
        val c = controller ?: return
        val book = _state.value.book ?: return
        val index = c.currentMediaItemIndex.coerceIn(0, max(0, fileStarts.size - 1))
        val fileOffset = if (fileStarts.isNotEmpty()) fileStarts[index] else 0.0
        val position = fileOffset + c.currentPosition / 1000.0
        _state.value = _state.value.copy(
            isPlaying = c.isPlaying,
            positionInBook = min(position, _state.value.durationInBook),
            currentChapter = chapterAt(book, position)
        )
    }

    private fun chapterAt(book: BookDetailDto, seconds: Double): ChapterDto? =
        book.chapters.firstOrNull {
            seconds >= parseTimeSpan(it.start) && seconds < parseTimeSpan(it.end)
        }
}
