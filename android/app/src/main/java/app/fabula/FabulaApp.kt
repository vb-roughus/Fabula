package app.fabula

import android.app.Application
import app.fabula.data.FabulaRepository
import app.fabula.data.LogStore
import app.fabula.data.ServerPreferences
import app.fabula.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        repository = FabulaRepository(preferences, logStore)
        playerController = PlayerController(this, repository)
    }
}
