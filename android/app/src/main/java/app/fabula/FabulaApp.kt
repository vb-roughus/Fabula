package app.fabula

import android.app.Application
import app.fabula.data.FabulaRepository
import app.fabula.data.ServerPreferences
import app.fabula.player.PlayerController

class FabulaApp : Application() {

    lateinit var preferences: ServerPreferences
        private set

    lateinit var repository: FabulaRepository
        private set

    lateinit var playerController: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = ServerPreferences(this)
        repository = FabulaRepository(preferences)
        playerController = PlayerController(this, repository)
    }
}
