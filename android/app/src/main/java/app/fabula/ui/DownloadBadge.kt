package app.fabula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import app.fabula.FabulaApp
import app.fabula.data.DownloadStatus

/** What a book's cover badge should show, or null for "nothing downloaded". */
data class DownloadBadgeState(
    /** Every track is on disk. */
    val complete: Boolean,
    /** Non-null while a download is running or queued. */
    val percent: Int?,
    /** Some tracks present, nothing running. */
    val partial: Boolean
)

/**
 * Collects the download state once and hands back a lookup by book id.
 *
 * Screens call this a single time and pass the result down to their tiles --
 * collecting per tile would spin up a collector for every visible cover.
 */
@Composable
fun rememberDownloadBadges(): (Int) -> DownloadBadgeState? {
    val app = LocalContext.current.applicationContext as FabulaApp
    val downloadedBooks by app.offlineStore.downloadedBooks.collectAsState()
    val states by app.downloadManager.states.collectAsState()

    return { bookId ->
        val running = states[bookId]
        val complete = downloadedBooks[bookId] == true
        val hasAny = downloadedBooks.containsKey(bookId)
        when {
            running != null && (running.status == DownloadStatus.Running ||
                running.status == DownloadStatus.Queued ||
                running.status == DownloadStatus.Paused) ->
                DownloadBadgeState(complete = false, percent = running.percent, partial = false)
            complete -> DownloadBadgeState(complete = true, percent = null, partial = false)
            hasAny -> DownloadBadgeState(complete = false, percent = null, partial = true)
            else -> null
        }
    }
}

/** Small chip for a book cover: done, in progress, or partially downloaded. */
@Composable
fun DownloadBadge(state: DownloadBadgeState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f))
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.percent != null -> CircularProgressIndicator(
                progress = { state.percent / 100f },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            state.complete -> Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Offline verfügbar",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            else -> Icon(
                Icons.Filled.DownloadForOffline,
                contentDescription = "Teilweise heruntergeladen",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
