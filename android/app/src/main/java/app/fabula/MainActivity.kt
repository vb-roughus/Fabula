package app.fabula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import app.fabula.ui.FabulaTheme
import app.fabula.ui.Navigation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = application as FabulaApp

        // Warm up the repository so streamUrl works as early as possible.
        lifecycleScope.launch { appContainer.repository.apiOrNull() }

        setContent {
            FabulaTheme {
                DisposableEffect(Unit) {
                    appContainer.playerController.connect()
                    onDispose { }
                }
                Navigation(
                    repository = appContainer.repository,
                    player = appContainer.playerController
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            (application as FabulaApp).playerController.release()
        }
    }
}
