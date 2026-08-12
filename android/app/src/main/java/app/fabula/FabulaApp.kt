package app.fabula

import android.app.Application
import app.fabula.data.DownloadManager
import app.fabula.data.FabulaRepository
import app.fabula.data.LogStore
import app.fabula.data.OfflineStore
import app.fabula.data.PendingUploadStore
import app.fabula.data.ProgressStore
import app.fabula.data.UploadSyncer
import app.fabula.data.ServerPreferences
import app.fabula.player.DownloadService
import app.fabula.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FabulaApp : Application() {

    lateinit var preferences: ServerPreferences
        private set

    lateinit var logStore: LogStore
        private set

    lateinit var repository: FabulaRepository
        private set

    lateinit var playerController: PlayerController
        private set

    /** Downloaded audio on disk. Read by PlaybackService on every stream open,
     *  so it must exist before any playback starts. */
    lateinit var offlineStore: OfflineStore
        private set

    lateinit var downloadManager: DownloadManager
        private set

    /** Durable local listening progress; the player treats it as the truth. */
    lateinit var progressStore: ProgressStore
        private set

    /** Bookmarks and highlights waiting to reach the server. */
    lateinit var pendingUploads: PendingUploadStore
        private set

    lateinit var uploadSyncer: UploadSyncer
        private set

    /** Hot mirror of the persisted shower-boost preference — PlaybackService
     *  subscribes to this to avoid its own DataStore coroutine setup. */
    private val _showerBoostDb = MutableStateFlow(0f)
    val showerBoostDb: StateFlow<Float> = _showerBoostDb.asStateFlow()
    fun setShowerBoostLive(db: Float) { _showerBoostDb.value = db }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        preferences = ServerPreferences(this)
        logStore = LogStore(this)
        // Keep the LogStore's volatile enabled-flag in sync with the
        // DataStore-backed preference so toggling in Settings takes effect
        // immediately for both Retrofit and the streaming OkHttpClient.
        appScope.launch {
            preferences.diagnosticsEnabled.collect { logStore.setEnabled(it) }
        }
        appScope.launch {
            preferences.showerBoostDb.collect { _showerBoostDb.value = it }
        }
        repository = FabulaRepository(preferences, logStore)
        offlineStore = OfflineStore(this, logStore)
        downloadManager = DownloadManager(
            context = this,
            repository = repository,
            offlineStore = offlineStore,
            logStore = logStore,
            scope = appScope,
            startForegroundService = { DownloadService.start(this) }
        )
        progressStore = ProgressStore(this, logStore)
        pendingUploads = PendingUploadStore(this, logStore)
        uploadSyncer = UploadSyncer(repository, pendingUploads, logStore, appScope).also { it.start() }
        playerController = PlayerController(this, repository, progressStore, uploadSyncer)

        // One handover attempt per cold start, plus one after each successful
        // manual reconnect. Never on a timer -- reconnecting is the user's call.
        appScope.launch {
            playerController.syncPendingProgress()
            uploadSyncer.requestSync()
        }
        appScope.launch {
            repository.reconnects.collect {
                playerController.syncPendingProgress()
                uploadSyncer.requestSync()
            }
        }
    }
}
