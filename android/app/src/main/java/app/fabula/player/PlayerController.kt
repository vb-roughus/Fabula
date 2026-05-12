package app.fabula.player

import android.content.ComponentName
import android.content.Context
import android.media.RingtoneManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.UpdateProgressRequest
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import com.google.common.util.concurrent.MoreExecutors
import java.util.Calendar
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
    val currentChapter: ChapterDto? = null,
    /** Source of truth for the `finished` flag we send to the server.
     *  Initialised from the saved server progress on loadBook, flipped to
     *  true by Player.STATE_ENDED, flipped back to false when the user seeks
     *  more than a minute back from the end. */
    val finished: Boolean = false,
    /** Remaining sleep timer in milliseconds. Null when the timer is off. */
    val sleepTimerRemainingMs: Long? = null
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
    private var sleepJob: Job? = null

    // Most recent sleep timer duration (defaults to 30 min). Used when the
    // timer auto-restarts after the user resumes playback.
    private var lastSleepDurationMs: Long = 30L * 60 * 1000

    // Set when the sleep timer fires and pauses playback. Cleared when the
    // user starts/cancels the timer manually or when we auto-restart it.
    private var stoppedBySleep: Boolean = false

    // Cached preferences -- collected from the repository when the
    // controller is created, kept in sync via the scope.
    private var sleepRepeatEnabled: Boolean = true
    private var sleepRepeatUntilMinutes: Int = 7 * 60

    init {
        scope.launch {
            repository.sleepRepeatEnabled.collect { sleepRepeatEnabled = it }
        }
        scope.launch {
            repository.sleepRepeatUntilMinutes.collect { sleepRepeatUntilMinutes = it }
        }
    }

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
        sleepJob?.cancel()
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

        val savedProgress = runCatching { api?.getProgress(book.id) }.getOrNull()
        val savedPosition = savedProgress?.position
        val savedFinished = savedProgress?.finished == true
        val startSec = parseTimeSpan(savedPosition)
        val (startIndex, startOffsetMs) = mapBookToMedia(startSec)

        c.setMediaItems(items, startIndex, startOffsetMs)
        c.prepare()

        _state.value = PlayerUiState(
            book = book,
            isPlaying = false,
            positionInBook = startSec,
            durationInBook = parseTimeSpan(book.duration),
            currentChapter = chapterAt(book, startSec),
            finished = savedFinished
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
        // If the user just seeked well back from the end, clear the
        // sticky "finished" flag so the book reappears in "Weiter hören".
        val s = _state.value
        if (s.finished && s.durationInBook > 0 && seconds + 60.0 < s.durationInBook) {
            _state.value = s.copy(finished = false)
        }
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

    /**
     * Start (or reset) a sleep timer that, when it elapses, plays the system
     * notification sound, pauses playback, and stores a "Gute Nacht!"
     * bookmark at the position where playback stopped.
     */
    fun startSleepTimer(durationMs: Long) {
        sleepJob?.cancel()
        lastSleepDurationMs = durationMs
        stoppedBySleep = false  // a fresh manual start cancels the auto-resume flag
        val endAt = System.currentTimeMillis() + durationMs
        sleepJob = scope.launch {
            while (true) {
                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) break
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
                delay(1000)
            }
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
            fireSleepEnd()
            _state.value = _state.value.copy(sleepTimerRemainingMs = null)
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        stoppedBySleep = false
        _state.value = _state.value.copy(sleepTimerRemainingMs = null)
    }

    private suspend fun fireSleepEnd() {
        val current = _state.value
        val book = current.book
        val pos = current.positionInBook

        // Mark before pausing so the player listener doesn't try to
        // auto-restart on its own sleep-induced pause callback.
        stoppedBySleep = true

        // Pause first, then play the notification on the alarm/notification
        // stream so it doesn't get muffled by the audio book stream.
        controller?.pause()
        playNotificationSound()

        if (book != null) {
            val api = repository.apiOrNull() ?: return
            runCatching {
                api.createBookmark(
                    book.id,
                    CreateBookmarkRequest(
                        position = toTimeSpanString(pos),
                        note = "Gute Nacht!"
                    )
                )
                repository.bumpBookmarksRevision()
            }
        }
    }

    /** Wall-clock millis of the next occurrence of [sleepRepeatUntilMinutes]
     *  (e.g. tomorrow 07:00 if it's already past today's 07:00). */
    private fun nextWakeUpMillis(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, sleepRepeatUntilMinutes / 60)
            set(Calendar.MINUTE, sleepRepeatUntilMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    private fun playNotificationSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }
    }

    private fun mapBookToMedia(seconds: Double): Pair<Int, Long> {
        if (fileStarts.isEmpty()) return 0 to 0L
        val clamped = max(0.0, seconds)
        var index = fileStarts.size - 1
        // Tolerance for FP drift between the server's exact OffsetInBook and
        // the client's cumulative sum of parseTimeSpan(file.duration). Without
        // it, seeking to the start of file N can land on the last millisecond
        // of file N-1 if the cumulative sum overshoots the chapter start by a
        // few microseconds.
        val epsilon = 0.010
        for (i in fileStarts.indices) {
            val nextStart = if (i + 1 < fileStarts.size) fileStarts[i + 1] else Double.MAX_VALUE
            if (clamped + epsilon < nextStart) { index = i; break }
        }
        val localMs = ((clamped - fileStarts[index]) * 1000.0).toLong().coerceAtLeast(0L)
        return index to localMs
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // If playback resumes after the sleep timer paused us, and the
            // user has the auto-repeat enabled, start a fresh timer with the
            // same duration -- but only while we're still before the next
            // configured wake-up time.
            if (isPlaying && stoppedBySleep) {
                stoppedBySleep = false
                if (sleepRepeatEnabled && System.currentTimeMillis() < nextWakeUpMillis()) {
                    startSleepTimer(lastSleepDurationMs)
                }
            }
            updateStateFromController()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateStateFromController() }
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Natural end of the last MediaItem: mark the book as finished
            // exactly once. The auto-save below picks this up on the next
            // tick and persists it.
            if (playbackState == Player.STATE_ENDED) {
                _state.value = _state.value.copy(finished = true)
            }
        }
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
                // `finished` is no longer derived from the position -- it
                // would be sticky once true, because every subsequent
                // auto-save near the end re-sent finished=true. Instead the
                // flag lives on PlayerUiState and is flipped by either
                // STATE_ENDED, the user's "Als gehört markieren" menu, or
                // (back to false) a seek that goes well past the end zone.
                runCatching {
                    api.saveProgress(
                        book.id,
                        UpdateProgressRequest(
                            position = toTimeSpanString(s.positionInBook),
                            finished = s.finished,
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

    private fun chapterAt(book: BookDetailDto, seconds: Double): ChapterDto? {
        // Tolerance for the same FP drift that mapBookToMedia compensates
        // for: a seek to chapter N's start can read back as N's start minus
        // a few microseconds, which would otherwise pin the highlight on
        // chapter N-1.
        val probe = seconds + 0.010
        return book.chapters.firstOrNull {
            probe >= parseTimeSpan(it.start) && probe < parseTimeSpan(it.end)
        }
    }
}
