package app.fabula.player

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.fabula.FabulaApp
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Media3 session service. Hosts a single ExoPlayer and exposes it through a
 * MediaSession so that system UI (notification, lockscreen, Bluetooth, Wear)
 * can control playback while the Activity is gone.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var speakerOnly: Boolean = true
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var boostJob: Job? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = updateSpeakerState()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = updateSpeakerState()
    }

    private fun updateSpeakerState() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        speakerOnly = outputs.none { d ->
            d.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }
        applyBoost((application as FabulaApp).showerBoostDb.value)
    }

    private fun applyBoost(configuredDb: Float) {
        val le = loudnessEnhancer ?: return
        val effectiveDb = if (speakerOnly) configuredDb else 0f
        if (effectiveDb <= 0f) {
            le.setEnabled(false)
        } else {
            le.setTargetGain((effectiveDb * 100).toInt())
            le.setEnabled(true)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val app = application as FabulaApp
        val prefs = app.preferences
        val logStore = app.logStore
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Attach the JWT to every audio stream request. ExoPlayer's
                // OkHttpDataSource invokes this interceptor on every HTTP
                // request, including Range follow-ups, so seeks keep working.
                val token = runBlocking { prefs.authToken.first() }
                val req = if (!token.isNullOrBlank()) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                val started = System.currentTimeMillis()
                val response = try {
                    chain.proceed(req)
                } catch (t: Throwable) {
                    logStore.e(
                        "Stream",
                        "${req.method} ${req.url} -> network error after ${System.currentTimeMillis() - started} ms",
                        t
                    )
                    throw t
                }
                if (!response.isSuccessful) {
                    logStore.w(
                        "Stream",
                        "${req.method} ${req.url} -> ${response.code} ${response.message}"
                    )
                }
                response
            }
            .build()
        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent("Fabula/0.1 (Android)")
        // DefaultDataSource handles file:// itself and delegates http(s) to the
        // OkHttp factory. Without this wrapper an offline file:// URI would be
        // handed to OkHttpDataSource, which only speaks HTTP, and fail.
        val baseFactory = DefaultDataSource.Factory(this, httpFactory)
        // Swap /api/stream/<id> for a downloaded copy at open() time. Doing it
        // here rather than when building MediaItems means a book can be part
        // local and part remote with no bookkeeping in PlayerController, and a
        // deleted download silently falls back to streaming on the next open.
        val offlineStore = app.offlineStore
        val dataSourceFactory = ResolvingDataSource.Factory(baseFactory) { spec ->
            offlineStore.localFileForStreamUri(spec.uri)
                ?.let { spec.withUri(Uri.fromFile(it)) }
                ?: spec
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(ResumptionCallback(app, serviceScope))
            .build()

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
        updateSpeakerState()

        boostJob = serviceScope.launch {
            app.showerBoostDb.collect { db -> applyBoost(db) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        boostJob?.cancel()
        serviceScope.cancel()
        (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(audioDeviceCallback)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

/**
 * Answers the system when it wants to resume playback with nothing loaded.
 *
 * This happens on a headphone or steering-wheel button press after the service
 * has been stopped, and from the resume control in the system's media area. The
 * session is asked what to play; a session that does not answer leaves the
 * system to pick another app, which is how a button press ends up starting
 * something else entirely.
 *
 * Answered from the local record only where possible -- the last position is in
 * the progress store and a downloaded book's detail is in its manifest -- so a
 * button press works with no connection at all. The server is consulted only for
 * a book that was never downloaded, which could not be played offline anyway.
 */
private class ResumptionCallback(
    private val app: FabulaApp,
    private val scope: CoroutineScope
) : MediaSession.Callback {

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            runCatching { lastPlayed() }.fold(
                onSuccess = { resumption ->
                    if (resumption != null) future.set(resumption)
                    // No history, or nothing playable: reject rather than
                    // hand back an empty queue, so the system can fall back
                    // instead of silently doing nothing.
                    else future.setException(
                        UnsupportedOperationException("Keine fortsetzbare Wiedergabe")
                    )
                },
                onFailure = { future.setException(it) }
            )
        }
        return future
    }

    private suspend fun lastPlayed(): MediaSession.MediaItemsWithStartPosition? {
        val recent = app.progressStore.mostRecent() ?: return null
        val book = app.offlineStore.cachedBook(recent.bookId)
            ?: runCatching { app.repository.apiOrNull()?.getBook(recent.bookId) }.getOrNull()
            ?: return null

        val items = buildPlaybackItems(book, app.repository, app.offlineStore)
        if (items.isEmpty()) return null

        val (index, offsetMs) = mapBookPositionToMedia(fileStartsOf(book), recent.positionSec)
        return MediaSession.MediaItemsWithStartPosition(items, index, offsetMs)
    }
}
