package app.fabula.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fabula.data.FabulaRepository
import app.fabula.player.PlayerController
import app.fabula.ui.book.BookScreen
import app.fabula.ui.home.HomeScreen
import app.fabula.ui.library.LibraryScreen
import app.fabula.ui.player.FullPlayer
import app.fabula.ui.player.MiniPlayer
import app.fabula.ui.series.SeriesDetailScreen
import app.fabula.ui.series.SeriesScreen
import app.fabula.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Startseite", Icons.Filled.Home),
    Library("library", "Bibliothek", Icons.Filled.LibraryBooks),
    Series("series", "Serien", Icons.Filled.Style),
    Settings("settings", "Einstellungen", Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): Tab? =
            entries.firstOrNull { route != null && (route == it.route || route.startsWith("${it.route}/")) }
    }
}

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

    val startTab = if (baseUrl!!.isBlank()) Tab.Settings else Tab.Home
    val playerState by player.state.collectAsState()
    val hasBook = playerState.book != null

    var fullPlayerOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = fullPlayerOpen) { fullPlayerOpen = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Column {
                    if (hasBook) {
                        MiniPlayer(
                            player = player,
                            repository = repository,
                            onClick = { fullPlayerOpen = true }
                        )
                    }
                    FabulaNavigationBar(navController = navController)
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startTab.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(Tab.Home.route) {
                    HomeScreen(
                        repository = repository,
                        onBookClick = { id -> navController.navigate("book/$id") }
                    )
                }
                composable(Tab.Library.route) {
                    LibraryScreen(
                        repository = repository,
                        onBookClick = { id -> navController.navigate("book/$id") },
                        onOpenSettings = { navController.navigate(Tab.Settings.route) }
                    )
                }
                composable(Tab.Series.route) {
                    SeriesScreen(
                        repository = repository,
                        onSeriesClick = { id -> navController.navigate("series/$id") }
                    )
                }
                composable(
                    route = "series/{seriesId}",
                    arguments = listOf(navArgument("seriesId") { type = NavType.IntType })
                ) { entry ->
                    val id = entry.arguments?.getInt("seriesId") ?: return@composable
                    SeriesDetailScreen(
                        seriesId = id,
                        repository = repository,
                        onBack = { navController.popBackStack() },
                        onBookClick = { bookId -> navController.navigate("book/$bookId") }
                    )
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        repository = repository,
                        onDone = {
                            navController.navigate(Tab.Home.route) {
                                popUpTo(Tab.Settings.route) { inclusive = true }
                            }
                        }
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
                        onBack = { navController.popBackStack() },
                        onPlaybackStarted = { fullPlayerOpen = true }
                    )
                }
            }
        }

        // Fullscreen player slides up from the bottom and covers everything
        // (including the nav bar). Back button or the collapse arrow dismisses it.
        AnimatedVisibility(
            visible = hasBook && fullPlayerOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            FullPlayer(
                player = player,
                repository = repository,
                onCollapse = { fullPlayerOpen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun FabulaNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = Tab.fromRoute(currentRoute)

    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = currentTab == tab
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) return@NavigationBarItem
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) }
            )
        }
    }
}
