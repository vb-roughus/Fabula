package app.fabula.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.fabula.FabulaApp
import app.fabula.data.BookDetailDto
import app.fabula.data.ChapterDto
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.OfflineStore
import app.fabula.data.ProgressStore
import app.fabula.data.UploadSyncer
import app.fabula.data.UpdateProgressRequest
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import com.google.common.util.concurrent.MoreExecutors
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
    /** When on, the end of a book automatically continues with the next one in
     *  its series. Shown in the UI as a toggle, so it is never hidden state. */
    val seriesMode: Boolean = false,
    /** Remaining sleep timer in milliseconds. Null when the timer is off. */
    val sleepTimerRemainingMs: Long? = null,
    /** Configured shower boost in dB (0 = off). Persisted in DataStore. */
    val showerBoostDb: Float = 0f,
    /** True when the built-in speaker is the only active audio output. */
    val showerSpeakerOnly: Boolean = true,
    /** While the user is capturing a highlight, the book-position (seconds)
     *  where they tapped "Markierung starten". Null when no capture is in
     *  progress. The second tap reads this together with the current position
     *  to create the range. */
    val highlightStartSec: Double? = null
)

/**
 * Thin wrapper around a Media3 MediaController. Maps book-wide position to
 * the ExoPlayer's per-MediaItem position, pushes progress to the server on a
 * timer, and exposes a simple StateFlow for the UI.
 */
class PlayerController(
    private val context: Context,
    private val repository: FabulaRepository,
    private val progressStore: ProgressStore,
    private val uploadSyncer: UploadSyncer,
    private val offlineStore: OfflineStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var progressJob: Job? = null
    private var syncJob: Job? = null
    private var sleepJob: Job? = null

    /** Conflated: only the newest request matters, since the worker always
     *  reads the current pending set when it runs. */
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

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

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) = refreshSpeakerState()
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) = refreshSpeakerState()
    }

    private fun isSpeakerOnly(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.none { d ->
            d.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
    }

    private fun refreshSpeakerState() {
        _state.value = _state.value.copy(showerSpeakerOnly = isSpeakerOnly())
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.sleepRepeatEnabled.collect { sleepRepeatEnabled = it }
        }
        scope.launch {
            repository.sleepRepeatUntilMinutes.collect { sleepRepeatUntilMinutes = it }
        }
        scope.launch {
            repository.showerBoostDb.collect { db ->
                _state.value = _state.value.copy(showerBoostDb = db)
            }
        }
        scope.launch {
            repository.seriesModeEnabled.collect { on ->
                _state.value = _state.value.copy(seriesMode = on)
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        _state.value = _state.value.copy(showerSpeakerOnly = isSpeakerOnly())
    }

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
            startProgressSync()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        pollJob?.cancel()
        progressJob?.cancel()
        syncJob?.cancel()
        sleepJob?.cancel()
        controller?.release()
        controller = null
        scope.cancel()
    }

    fun setShowerBoostDb(db: Float) {
        val clamped = db.coerceIn(0f, 15f)
        (context.applicationContext as FabulaApp).setShowerBoostLive(clamped)
        scope.launch { repository.setShowerBoostDb(clamped) }
    }

    /** Mark the current playback position as the start of a new highlight.
     *  The UI shows a "capturing" state until the user taps again to set the
     *  end (which it reads from highlightStartSec + the current position). */
    fun beginHighlight() {
        _state.value = _state.value.copy(highlightStartSec = _state.value.positionInBook)
    }

    /** Abandon an in-progress highlight capture without creating anything. */
    fun cancelHighlight() {
        if (_state.value.highlightStartSec != null) {
            _state.value = _state.value.copy(highlightStartSec = null)
        }
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
        val localProgress = progressStore.local(book.id)

        // An unsynced local entry was produced here and never reached the
        // server, so it is by definition newer -- taking the server value would
        // rewind the listener by exactly the stretch that failed to save. Once
        // synced, the two agree and the server value is used as before.
        val useLocal = localProgress != null && (!localProgress.synced || savedProgress == null)
        val startSec = if (useLocal) localProgress!!.positionSec
            else parseTimeSpan(savedProgress?.position)
        val savedFinished = if (useLocal) localProgress!!.finished
            else savedProgress?.finished == true
        val (startIndex, startOffsetMs) = mapBookToMedia(startSec)

        c.setMediaItems(items, startIndex, startOffsetMs)
        c.prepare()

        // Session-scoped state has to survive swapping the book, otherwise
        // series mode would forget itself the moment it did its job -- and a
        // running sleep timer would lose its countdown while its job kept
        // ticking. Only `highlightStartSec` is deliberately dropped: a capture
        // in progress belongs to the book being left behind.
        val carried = _state.value
        _state.value = PlayerUiState(
            book = book,
            isPlaying = false,
            positionInBook = startSec,
            durationInBook = parseTimeSpan(book.duration),
            currentChapter = chapterAt(book, startSec),
            finished = savedFinished,
            seriesMode = carried.seriesMode,
            sleepTimerRemainingMs = carried.sleepTimerRemainingMs,
            showerBoostDb = carried.showerBoostDb,
            showerSpeakerOnly = carried.showerSpeakerOnly
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

    /** Sync the finished-flag with an external state change (the user
     *  toggling "Als gehört markieren" in BookScreen, or our own reset).
     *  Without this the next 4s auto-save would re-send the stale value
     *  and undo what the server already accepted. No-op if the player
     *  holds a different book or no book at all. */
    fun setFinishedFlag(bookId: Int, value: Boolean) {
        val s = _state.value
        if (s.book?.id != bookId) return
        if (s.finished == value) return
        _state.value = s.copy(finished = value)
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
            // Queued rather than posted: this fires while the user is falling
            // asleep, so a failed request is the one nobody would ever notice.
            uploadSyncer.createBookmark(
                bookId = book.id,
                position = toTimeSpanString(pos),
                note = "Gute Nacht!"
            )
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
            // Flush progress immediately on pause/stop so the home screen
            // ("Weiter hören") and library reflect the new position the
            // moment the user navigates away -- the 4s background save
            // would otherwise race the navigation.
            if (!isPlaying) recordProgress()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateStateFromController() }
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Natural end of the last MediaItem: mark the book as finished
            // exactly once. The auto-save below picks this up on the next
            // tick and persists it.
            if (playbackState == Player.STATE_ENDED) {
                _state.value = _state.value.copy(finished = true)
                if (_state.value.seriesMode) continueWithSeries()
            }
        }
    }

    // --- series playback ---------------------------------------------------

    /**
     * Turns automatic continuation within a series on or off.
     *
     * Takes effect immediately and mid-playback: it only flips a flag, so
     * nothing is interrupted and nothing has to be restarted.
     */
    fun setSeriesMode(enabled: Boolean) {
        // Set locally first so the button responds at once rather than after
        // DataStore has been round-tripped.
        _state.value = _state.value.copy(seriesMode = enabled)
        scope.launch { repository.setSeriesModeEnabled(enabled) }
    }

    /** Stops a second advance from starting while one is already under way. */
    private var advancingSeries = false

    /**
     * Continues with the next unheard book of the series the finished book
     * belongs to. Called from STATE_ENDED, so it must not block.
     */
    private fun continueWithSeries() {
        if (advancingSeries) return
        val finishedBook = _state.value.book ?: return
        if (finishedBook.seriesId == null) return
        advancingSeries = true
        scope.launch {
            try {
                // Persist the book we are leaving before its state is replaced:
                // the 4-second recorder would never see this final position.
                recordProgress()
                val next = nextUnheardInSeries(finishedBook) ?: return@launch
                loadBook(next)
                play()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                repository.logFailure("Series.continue", t)
            } finally {
                advancingSeries = false
            }
        }
    }

    /**
     * The next book of the series that hasn't been heard yet, or null at the
     * end of the series. The skipping rule itself lives in SeriesContinuation.kt
     * so it can be tested.
     */
    private suspend fun nextUnheardInSeries(current: BookDetailDto): BookDetailDto? {
        val seriesId = current.seriesId ?: return null
        for (id in idsAfter(seriesOrder(seriesId), current.id)) {
            val candidate = bookDetail(id) ?: continue
            if (!alreadyHeard(candidate, progressStore.local(id))) return candidate
        }
        return null
    }

    /**
     * Book ids of a series in reading order. The server knows the whole series,
     * including books that were never downloaded, so it is asked first; the
     * downloaded manifests are the offline fallback.
     */
    private suspend fun seriesOrder(seriesId: Int): List<Int> {
        runCatching {
            val api = repository.apiOrNull() ?: return@runCatching null
            api.getSeries(seriesId).books.map { it.id }
        }.getOrNull()?.let { if (it.isNotEmpty()) return it }

        val cached = offlineStore.downloadedBooks.value.keys.mapNotNull { offlineStore.cachedBook(it) }
        return seriesOrderFromLibrary(cached, seriesId)
    }

    private suspend fun bookDetail(id: Int): BookDetailDto? =
        runCatching { repository.apiOrNull()?.getBook(id) }.getOrNull()
            ?: offlineStore.cachedBook(id)

    /**
     * Records the current position locally and asks the sync worker to hand it
     * over. Recording never fails, so nothing is lost when the network isn't
     * there -- the handover is a separate, retryable step.
     */
    private fun recordProgress() {
        val s = _state.value
        val book = s.book ?: return
        progressStore.record(book.id, s.positionInBook, s.finished, System.currentTimeMillis())
        syncRequests.trySend(Unit)
    }

    /**
     * Single writer for progress. Requests are conflated, so however often the
     * position moves there is only ever one save in flight and it always
     * carries the newest value.
     *
     * This is what stops progress from overwriting itself: previously every
     * tick launched its own coroutine, and on a slow link an earlier one could
     * land later -- the server stamps its own arrival time, so an older
     * position would win.
     */
    private fun startProgressSync() {
        syncJob = scope.launch {
            for (unused in syncRequests) {
                val api = repository.apiOrNull() ?: continue
                // Oldest observation first, so a book listened to earlier is
                // handed over before the current one.
                for (entry in progressStore.pending()) {
                    val ok = runCatching {
                        api.saveProgress(
                            entry.bookId,
                            UpdateProgressRequest(
                                position = toTimeSpanString(entry.positionSec),
                                finished = entry.finished,
                                device = repository.deviceId()
                            )
                        )
                    }.isSuccess
                    if (!ok) break  // offline or failing; keep the rest pending
                    progressStore.markSynced(entry.bookId, entry.updatedAtMs)
                }
            }
        }
    }

    /** Hands over anything still pending. Called at app start and after the
     *  drawer's reconnect succeeds -- never on a timer of its own. */
    fun syncPendingProgress() {
        if (progressStore.hasPending()) syncRequests.trySend(Unit)
    }

    /**
     * Records a position the user set explicitly (reset to start, or marking a
     * book heard / unheard) so it survives being offline like any other
     * progress instead of relying on the one-shot request succeeding.
     */
    fun recordExplicitProgress(bookId: Int, positionSec: Double, finished: Boolean) {
        progressStore.record(bookId, positionSec, finished, System.currentTimeMillis())
        syncRequests.trySend(Unit)
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (true) {
                updateStateFromController()
                delay(500)
            }
        }
        progressJob = scope.launch {
            var lastRecorded = -10.0
            var lastBookId = -1
            while (true) {
                delay(4000)
                val s = _state.value
                val book = s.book ?: continue
                if (book.id == lastBookId && abs(lastRecorded - s.positionInBook) < 2.0) continue
                // `finished` is no longer derived from the position -- it
                // would be sticky once true, because every subsequent
                // auto-save near the end re-sent finished=true. Instead the
                // flag lives on PlayerUiState and is flipped by either
                // STATE_ENDED, the user's "Als gehört markieren" menu, or
                // (back to false) a seek that goes well past the end zone.
                recordProgress()
                lastRecorded = s.positionInBook
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
