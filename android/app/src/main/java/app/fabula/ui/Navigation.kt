package app.fabula.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Startseite", Icons.Filled.Home),
    Library("library", "Bibliothek", Icons.Filled.LibraryBooks),
    Series("series", "Serien", Icons.Filled.Style);

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

    val startRoute = if (baseUrl!!.isBlank()) "settings" else Tab.Home.route
    val playerState by player.state.collectAsState()
    val hasBook = playerState.book != null

    var fullPlayerOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Fabula",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(16.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Einstellungen") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
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
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    composable(Tab.Home.route) {
                        HomeScreen(
                            repository = repository,
                            onMenuClick = openDrawer,
                            onBookClick = { id -> navController.navigate("book/$id") }
                        )
                    }
                    composable(Tab.Library.route) {
                        LibraryScreen(
                            repository = repository,
                            onMenuClick = openDrawer,
                            onBookClick = { id -> navController.navigate("book/$id") }
                        )
                    }
                    composable(Tab.Series.route) {
                        SeriesScreen(
                            repository = repository,
                            onMenuClick = openDrawer,
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
                    composable("settings") {
                        SettingsScreen(
                            repository = repository,
                            onDone = { navController.popBackStack() }
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

            AnimatedVisibility(
                visible = hasBook && fullPlayerOpen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                // Registered inside AnimatedVisibility so it's added to the
                // back-press dispatcher AFTER the NavHost's own back handler.
                // The dispatcher invokes the most recently registered enabled
                // callback first, so this one wins and minimises the player
                // instead of popping the screen behind it.
                BackHandler(enabled = true) { fullPlayerOpen = false }
                FullPlayer(
                    player = player,
                    repository = repository,
                    onCollapse = { fullPlayerOpen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun FabulaNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = Tab.fromRoute(currentRoute)

    NavigationBar(
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets
    ) {
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
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
