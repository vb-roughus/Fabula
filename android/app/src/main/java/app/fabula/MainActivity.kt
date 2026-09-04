package app.fabula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.fabula.data.OFFLINE_DISPLAY_DELAY_MS
import app.fabula.ui.FabulaTheme
import app.fabula.ui.Navigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = application as FabulaApp

        // Warm up the repository so streamUrl works as early as possible.
        lifecycleScope.launch { appContainer.repository.apiOrNull() }

        setContent {
            val themeMode by appContainer.repository.themeMode.collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // null (nothing tried yet) is deliberately not treated as offline,
            // so the accent doesn't flash orange during startup.
            val serverOnline by appContainer.repository.serverOnline.collectAsState()
            // Repainting the whole app orange is a loud answer to what is often
            // a lift or a tunnel, so the colour waits until being offline has
            // actually held. The delay outlasts the first automatic retry, which
            // means an outage that heals itself never shows up here at all.
            // Coming back is immediate -- good news needs no confirmation.
            var offlineForLongEnough by remember { mutableStateOf(false) }
            LaunchedEffect(serverOnline) {
                if (serverOnline == false) {
                    delay(OFFLINE_DISPLAY_DELAY_MS)
                    offlineForLongEnough = true
                } else {
                    offlineForLongEnough = false
                }
            }
            FabulaTheme(darkTheme = darkTheme, offline = offlineForLongEnough) {
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
