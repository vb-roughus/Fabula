package app.fabula.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    /** The next volume of the series -- set only once it has been fetched and
     *  its queue built, so a UI that offers to skip ahead can rely on the skip
     *  actually working. Null whenever series mode is off, the current book is
     *  the last one, or the preparation has not succeeded (yet). */
    val seriesNext: BookDetailDto? = null,
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
    private var connecting = false
    private var pollJob: Job? = null
    private var progressJob: Job? = null
    private var syncJob: Job? = null
    private var sleepJob: Job? = null
    private var prepareJob: Job? = null

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
        // `controller` stays null while the session is being built, so without
        // the second guard a second call during that window would build a
        // second controller and leak the first.
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            startPolling()
            startProgressSync()
            startSeriesPreparation()
        }, MoreExecutors.directExecutor())
    }

    /**
     * Lets go of the session, but only while nothing is playing.
     *
     * The Activity is routinely finished while playback carries on in the
     * foreground service -- backing out of the app is the normal way to listen.
     * Releasing then took down the listener that continues a series at the end
     * of a book, which is why continuing worked only when the app happened to
     * still be open. Staying connected costs one binder connection to a service
     * that is running anyway.
     *
     * Returns whether the session was actually let go of.
     */
    fun releaseIfIdle(): Boolean {
        if (controller?.isPlaying == true) return false
        release()
        return true
    }

    /**
     * Disconnects from the session and stops the background jobs.
     *
     * Deliberately survivable: the scope stays alive and the audio-device
     * callback stays registered, so a later [connect] brings a fully working
     * controller back. Cancelling the scope here used to leave a reconnected
     * controller with no polling and no progress sync at all.
     */
    fun release() {
        pollJob?.cancel()
        progressJob?.cancel()
        syncJob?.cancel()
        sleepJob?.cancel()
        prepareJob?.cancel()
        controller?.release()
        controller = null
        connecting = false
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

    /**
     * Opens [book] in the player, resuming where it was left off.
     *
     * [startOverIfAtEnd] is set by the series continuation, which hands on to
     * books that may well have been heard already: those would otherwise resume
     * on their final seconds and end again immediately. See
     * [startsFromBeginning].
     */
    suspend fun loadBook(book: BookDetailDto, startOverIfAtEnd: Boolean = false) {
        if (controller == null) return
        val items = buildPlaybackItems(book, repository, offlineStore)
        if (items.isEmpty()) return
        val start = resumePositionOf(book, startOverIfAtEnd)
        install(book, items, start)
    }

    /** Where playback should pick a book up, and whether it counts as finished. */
    private data class ResumePoint(val positionSec: Double, val finished: Boolean)

    /**
     * Reconciles the local progress record with the server's.
     *
     * An unsynced local entry was produced here and never reached the server, so
     * it is by definition newer -- taking the server value would rewind the
     * listener by exactly the stretch that failed to save. Once synced, the two
     * agree and the server value is used as before.
     */
    private suspend fun resumePositionOf(
        book: BookDetailDto,
        startOverIfAtEnd: Boolean
    ): ResumePoint {
        val savedProgress = runCatching { repository.apiOrNull()?.getProgress(book.id) }.getOrNull()
        val localProgress = progressStore.local(book.id)

        val useLocal = localProgress != null && (!localProgress.synced || savedProgress == null)
        val resumeSec = if (useLocal) localProgress!!.positionSec
            else parseTimeSpan(savedProgress?.position)
        val resumeFinished = if (useLocal) localProgress!!.finished
            else savedProgress?.finished == true

        val restart = startOverIfAtEnd &&
            startsFromBeginning(resumeSec, resumeFinished, parseTimeSpan(book.duration))
        return if (restart) ResumePoint(0.0, finished = false)
        else ResumePoint(resumeSec, resumeFinished)
    }

    /**
     * Hands a prepared queue to the player. Purely local -- no network, no
     * suspension -- which is what lets the series handover happen at the instant
     * a book ends rather than whenever the connection gets round to answering.
     */
    private fun install(book: BookDetailDto, items: List<MediaItem>, start: ResumePoint) {
        val c = controller ?: return
        // Whatever was prepared was prepared to follow the book being left.
        preparedNext = null
        prepareRetryAtMs = 0L
        fileStarts = fileStartsOf(book)
        val (startIndex, startOffsetMs) = mapBookToMedia(start.positionSec)

        c.setMediaItems(items, startIndex, startOffsetMs)
        c.prepare()

        // Session-scoped state has to survive swapping the book, otherwise
        // series mode would forget itself the moment it did its job -- and a
        // running sleep timer would lose its countdown while its job kept
        // ticking. Only `highlightStartSec` is deliberately dropped: a capture
        // in progress belongs to the book being left behind. `seriesNext` goes
        // too: it described the book just installed, and the volume after it
        // has yet to be prepared.
        val carried = _state.value
        _state.value = PlayerUiState(
            book = book,
            isPlaying = false,
            positionInBook = start.positionSec,
            durationInBook = parseTimeSpan(book.duration),
            currentChapter = chapterAt(book, start.positionSec),
            finished = start.finished,
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

    private fun mapBookToMedia(seconds: Double): Pair<Int, Long> =
        mapBookPositionToMedia(fileStarts, seconds)

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

        // A book that stops on an error looks exactly like one that ended, from
        // the outside: the sound stops and nothing follows. The difference is
        // only visible here, so it goes into the log the user can send.
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            repository.logFailure(
                "Player.error(${_state.value.book?.id ?: "-"} @ ${_state.value.positionInBook.toInt()}s)",
                error
            )
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
     * The next volume, fetched and turned into a playable queue while the
     * current book is still running. See [SERIES_PREPARE_LEAD_SEC].
     */
    private data class PreparedNext(
        /** The book this was prepared to follow -- a seek into another book
         *  makes it stale, so it is checked rather than assumed. */
        val afterBookId: Int,
        val book: BookDetailDto,
        val items: List<MediaItem>,
        val start: ResumePoint
    )

    private var preparedNext: PreparedNext? = null

    /** Wall clock before which no further preparation attempt is made. */
    private var prepareRetryAtMs = 0L

    /**
     * Keeps [preparedNext] in step with what is playing.
     *
     * Runs for as long as the controller is connected rather than being tied to
     * an end-of-book event, so a failed attempt is simply retried while there is
     * still time -- which is the whole point of starting minutes early.
     */
    private fun startSeriesPreparation() {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            while (true) {
                runCatching { prepareSeriesNextIfDue() }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                delay(SERIES_PREPARE_POLL_MS)
            }
        }
    }

    private suspend fun prepareSeriesNextIfDue() {
        val s = _state.value
        val book = s.book

        // Anything that makes a prepared volume meaningless drops it, so the
        // skip card never offers a book that no longer follows this one.
        if (!s.seriesMode || book?.seriesId == null) {
            forgetPreparedNext()
            return
        }
        preparedNext?.let { if (it.afterBookId != book.id) forgetPreparedNext() }
        if (preparedNext != null) return

        if (!preparationDue(s.positionInBook, s.durationInBook)) return
        if (System.currentTimeMillis() < prepareRetryAtMs) return

        val prepared = try {
            prepareNextAfter(book)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            repository.logFailure("Series.prepare", t)
            null
        }

        // A book can have moved on while the fetch was in flight.
        if (_state.value.book?.id != book.id) return

        if (prepared == null) {
            // Either there is nothing after this book, or it could not be
            // reached. Both are retried: the series may simply have been
            // extended, and a connection may come back.
            prepareRetryAtMs = System.currentTimeMillis() + SERIES_PREPARE_RETRY_MS
            return
        }
        preparedNext = prepared
        _state.value = _state.value.copy(seriesNext = prepared.book)
    }

    private fun forgetPreparedNext() {
        if (preparedNext == null && _state.value.seriesNext == null) return
        preparedNext = null
        prepareRetryAtMs = 0L
        _state.value = _state.value.copy(seriesNext = null)
    }

    /**
     * The next book of the series, ready to play. Books already heard are
     * played again rather than skipped -- the series is followed volume by
     * volume, whatever each one's state.
     *
     * A volume that cannot be fetched or has nothing playable is passed over in
     * favour of the one after it: a single unreachable book must not end the
     * series where the listener expects it to carry on.
     */
    private suspend fun prepareNextAfter(current: BookDetailDto): PreparedNext? {
        val seriesId = current.seriesId ?: return null
        for (id in idsAfter(seriesOrder(seriesId), current.id)) {
            val candidate = bookDetail(id) ?: continue
            val items = buildPlaybackItems(candidate, repository, offlineStore)
            if (items.isEmpty()) continue
            return PreparedNext(
                afterBookId = current.id,
                book = candidate,
                items = items,
                start = resumePositionOf(candidate, startOverIfAtEnd = true)
            )
        }
        return null
    }

    /**
     * Continues with the next book of the series the finished book belongs to.
     * Called from STATE_ENDED, so it must not block.
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
                handOverTo(finishedBook)
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
     * Starts the next volume at once, without waiting out the rest of the
     * current one. Offered by the skip card, which appears only while there is
     * something prepared to skip to.
     */
    fun skipToSeriesNext() {
        if (advancingSeries) return
        val current = _state.value.book ?: return
        val prepared = preparedNext?.takeIf { it.afterBookId == current.id } ?: return
        advancingSeries = true
        scope.launch {
            try {
                // Skipping the closing minute is not abandoning the book, it is
                // being done with it -- so it counts as heard and leaves
                // "Weiter hören" instead of lingering there with a rest nobody
                // will play.
                _state.value = _state.value.copy(finished = true)
                recordProgress()
                install(prepared.book, prepared.items, prepared.start)
                play()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                repository.logFailure("Series.skip", t)
            } finally {
                advancingSeries = false
            }
        }
    }

    /**
     * Installs whatever follows [current]. Uses the volume prepared minutes ago
     * where there is one; otherwise it falls back to fetching now, which is
     * what happens when the preparation window was skipped over -- a seek
     * straight to the end, or series mode switched on during the last minute.
     */
    private suspend fun handOverTo(current: BookDetailDto) {
        val prepared = preparedNext?.takeIf { it.afterBookId == current.id }
            ?: prepareNextAfter(current)
            ?: return
        install(prepared.book, prepared.items, prepared.start)
        play()
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
        syncJob?.cancel()
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
        pollJob?.cancel()
        progressJob?.cancel()
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
