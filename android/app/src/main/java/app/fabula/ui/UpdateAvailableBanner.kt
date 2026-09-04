package app.fabula.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Notice at the top edge that a newer app version is waiting on the server.
 *
 * Takes its own space rather than floating over the content: an overlay here
 * would sit exactly where the screens keep their title and their menu button.
 *
 * Dismissable, and the dismissal is remembered for that version. A notice that
 * cannot be put away is a notice that starts being ignored -- and the update
 * stays reachable in the settings either way.
 */
@Composable
fun UpdateAvailableBanner(
    versionName: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            // Below the status bar, since this is the topmost thing on screen.
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Update verfügbar: $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Tippen zum Installieren und für die Änderungen",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Hinweis ausblenden",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
