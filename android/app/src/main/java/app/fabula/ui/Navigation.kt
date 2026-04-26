package app.fabula.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import app.fabula.ui.series.SeriesManagementScreen
import app.fabula.ui.series.SeriesScreen
import app.fabula.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val MiniPlayerHeight = 76.dp
private val NavigationBarContentHeight = 80.dp

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Startseite", Icons.Outlined.Home),
    Library("library", "Bibliothek", Icons.Outlined.LibraryBooks),
    Series("series", "Serien", Icons.Outlined.Style);

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
    // Only read whether we have a book at all -- avoid recomposing Navigation
    // (and reallocating its gradients) every position tick.
    val hasBookFlow = remember(player) {
        player.state.map { it.book != null }.distinctUntilChanged()
    }
    val hasBook by hasBookFlow.collectAsState(initial = false)

    var fullPlayerOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val sysNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val miniHeight = if (hasBook) MiniPlayerHeight else 0.dp
    val bottomOverlayInset = miniHeight + NavigationBarContentHeight + sysNavBarInset

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(260.dp)) {
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
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    label = { Text("Serien verwalten") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("series-manage")
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
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
        // App-wide background gradient: subtle brand-green tint at the top and
        // bottom, navy base in the middle. Applied to the outer Box AND every
        // screen Scaffold uses Color.Transparent so the gradient is visible
        // across the whole app, not just the drawer / sub-menus.
        val baseColor = MaterialTheme.colorScheme.background
        val accentColor = MaterialTheme.colorScheme.primary
        val appBackground = remember(baseColor, accentColor) {
            val tintTop = accentColor.copy(alpha = 0.22f).compositeOver(baseColor)
            val tintBottom = accentColor.copy(alpha = 0.14f).compositeOver(baseColor)
            Brush.verticalGradient(
                0.0f to tintTop,
                0.40f to baseColor,
                0.75f to baseColor,
                1.0f to tintBottom
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = appBackground)
        ) {
            // Content fills the entire screen including under the bottom overlay.
            // Each screen's scrollable content adds LocalContentBottomInset to its
            // contentPadding so the last item can scroll above the nav bar.
            CompositionLocalProvider(
                LocalContentBottomInset provides PaddingValues(bottom = bottomOverlayInset)
            ) {
                val tabRoutes = Tab.entries.map { it.route }
                fun tabDirection(from: String?, to: String?): Int {
                    val f = tabRoutes.indexOf(from)
                    val t = tabRoutes.indexOf(to)
                    return if (f < 0 || t < 0) 0 else (t - f)
                }

                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        when {
                            dir > 0 -> slideInHorizontally(initialOffsetX = { it })
                            dir < 0 -> slideInHorizontally(initialOffsetX = { -it })
                            else -> fadeIn()
                        }
                    },
                    exitTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        when {
                            dir > 0 -> slideOutHorizontally(targetOffsetX = { -it })
                            dir < 0 -> slideOutHorizontally(targetOffsetX = { it })
                            else -> fadeOut()
                        }
                    },
                    popEnterTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        when {
                            dir > 0 -> slideInHorizontally(initialOffsetX = { it })
                            dir < 0 -> slideInHorizontally(initialOffsetX = { -it })
                            else -> fadeIn()
                        }
                    },
                    popExitTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        when {
                            dir > 0 -> slideOutHorizontally(targetOffsetX = { -it })
                            dir < 0 -> slideOutHorizontally(targetOffsetX = { it })
                            else -> fadeOut()
                        }
                    }
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
                            onDone = { navController.popBackStack() },
                            onManageSeries = { navController.navigate("series-manage") }
                        )
                    }
                    composable("series-manage") {
                        SeriesManagementScreen(
                            repository = repository,
                            onBack = { navController.popBackStack() }
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

            // Bottom overlay: optional mini player + transparent nav bar. A
            // vertical gradient (transparent at the top, mostly opaque at the
            // bottom) sits behind both so scrolling content fades smoothly
            // behind the tab bar instead of cutting off harshly.
            val overlayScrim = MaterialTheme.colorScheme.background
            val overlayBrush = remember(overlayScrim) {
                Brush.verticalGradient(
                    colors = listOf(
                        overlayScrim.copy(alpha = 0f),
                        overlayScrim.copy(alpha = 0.6f),
                        overlayScrim.copy(alpha = 0.92f),
                        overlayScrim.copy(alpha = 0.97f)
                    )
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(overlayBrush)
            ) {
                if (hasBook) {
                    MiniPlayer(
                        player = player,
                        repository = repository,
                        onClick = { fullPlayerOpen = true }
                    )
                }
                FabulaNavigationBar(navController = navController)
            }

            AnimatedVisibility(
                visible = hasBook && fullPlayerOpen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
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
                    if (selected) {
                        // Already on this tab -- pop everything stacked on
                        // top of it so we land back on its root screen.
                        navController.popBackStack(tab.route, inclusive = false)
                    } else {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}
