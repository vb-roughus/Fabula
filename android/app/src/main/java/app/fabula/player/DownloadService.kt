package app.fabula.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.fabula.FabulaApp
import app.fabula.MainActivity
import app.fabula.R
import app.fabula.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows progress while books download.
 *
 * Deliberately owns no work: the queue and the transfers live in
 * [app.fabula.data.DownloadManager] on the application scope, so a service
 * restart can't tear a download in half. This class only holds the foreground
 * status and mirrors the manager's state into a notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as FabulaApp
        val downloads = app.downloadManager

        if (intent?.action == ACTION_CANCEL_ALL) {
            downloads.cancelAll()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must go foreground within a few seconds of being started.
        startForegroundCompat(buildNotification(null))

        if (collector == null) {
            collector = scope.launch {
                // Only the visible fields, so a 250 ms byte tick doesn't
                // rebuild the notification when nothing on it changed.
                downloads.states
                    .map { states -> states.values.firstOrNull { it.status == DownloadStatus.Running || it.status == DownloadStatus.Queued || it.status == DownloadStatus.Paused } }
                    .map { it?.let { s -> NotifModel(s.title, s.percent, s.doneTracks, s.totalTracks, s.status) } }
                    .distinctUntilChanged()
                    .collect { model ->
                        if (model == null) return@collect
                        notificationManager()?.notify(NOTIF_ID, buildNotification(model))
                    }
            }
            scope.launch {
                // Stop as soon as nothing is queued or running any more.
                downloads.busy.first { !it }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private data class NotifModel(
        val title: String,
        val percent: Int,
        val doneTracks: Int,
        val totalTracks: Int,
        val status: DownloadStatus
    )

    private fun startForegroundCompat(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun buildNotification(model: NotifModel?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = when {
            model == null -> "Wird vorbereitet…"
            model.status == DownloadStatus.Paused -> "Wartet auf WLAN · ${model.percent} %"
            model.totalTracks > 0 ->
                "${model.percent} % · Track ${(model.doneTracks + 1).coerceAtMost(model.totalTracks)} von ${model.totalTracks}"
            else -> "${model.percent} %"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(model?.title?.takeIf { it.isNotBlank() } ?: "Hörbuch wird geladen")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, model?.percent ?: 0, model == null)
            .setContentIntent(open)
            .addAction(0, "Abbrechen", cancel)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Fortschritt beim Herunterladen von Hörbüchern" }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 4711
        const val ACTION_CANCEL_ALL = "app.fabula.action.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
