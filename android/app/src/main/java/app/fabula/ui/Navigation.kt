package app.fabula.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fabula.data.FabulaRepository
import app.fabula.player.PlayerController
import app.fabula.ui.book.BookScreen
import app.fabula.ui.library.LibraryScreen
import app.fabula.ui.player.PlayerBar
import app.fabula.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

@Composable
fun Navigation(
    repository: FabulaRepository,
    player: PlayerController
) {
    val navController = rememberNavController()
    val baseUrl by produceState<String?>(initialValue = null) {
        value = repository.baseUrlFlow.first()
    }

    if (baseUrl == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (baseUrl!!.isBlank()) "settings" else "library"

    // navigationBarsPadding keeps the whole app UI above the system
    // navigation buttons. Status bar inset is handled per-screen by the
    // Material3 TopAppBars.
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.weight(1f)
        ) {
            composable("library") {
                LibraryScreen(
                    repository = repository,
                    onBookClick = { id -> navController.navigate("book/$id") },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable(
                route = "book/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.IntType })
            ) { entry ->
                val bookId = entry.arguments?.getInt("bookId") ?: return@composable
                BookScreen(
                    bookId = bookId,
                    repository = repository,
                    player = player,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    repository = repository,
                    onDone = {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate("library") {
                                popUpTo("settings") { inclusive = true }
                            }
                        }
                    }
                )
            }
        }
        PlayerBar(
            player = player,
            onOpenBook = { id -> navController.navigate("book/$id") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
