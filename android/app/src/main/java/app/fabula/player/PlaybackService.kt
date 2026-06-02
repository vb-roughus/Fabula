package app.fabula.player

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.fabula.FabulaApp
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
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent("Fabula/0.1 (Android)")

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

        mediaSession = MediaSession.Builder(this, player).build()

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
