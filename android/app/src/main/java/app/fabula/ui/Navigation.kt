package app.fabula.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fabula.data.FabulaRepository
import app.fabula.player.PlayerController
import app.fabula.ui.book.BookScreen
import app.fabula.ui.library.LibraryScreen
import app.fabula.ui.player.PlayerSheet
import app.fabula.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val MiniPlayerHeight = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
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
    val playerState by player.state.collectAsState()
    val hasBook = playerState.book != null

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()

    val isExpanded = sheetState.currentValue == SheetValue.Expanded ||
        sheetState.targetValue == SheetValue.Expanded

    // Back button collapses the expanded player instead of leaving the app.
    BackHandler(enabled = isExpanded) {
        scope.launch { sheetState.partialExpand() }
    }

    val expandPlayer: () -> Unit = {
        scope.launch { sheetState.expand() }
    }
    val collapsePlayer: () -> Unit = {
        scope.launch { sheetState.partialExpand() }
    }

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peekHeight = if (hasBook) MiniPlayerHeight + navBarInset else 0.dp

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetSwipeEnabled = hasBook,
        sheetDragHandle = null,
        sheetContent = {
            if (hasBook) {
                PlayerSheet(
                    player = player,
                    repository = repository,
                    isExpanded = isExpanded,
                    onRequestExpand = expandPlayer,
                    onRequestCollapse = collapsePlayer,
                    onOpenBook = { id ->
                        collapsePlayer()
                        navController.navigate("book/$id")
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination
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
                        onBack = { navController.popBackStack() },
                        onPlaybackStarted = expandPlayer
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
        }
    }
}
