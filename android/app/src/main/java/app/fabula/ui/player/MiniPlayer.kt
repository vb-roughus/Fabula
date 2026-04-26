package app.fabula.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fabula.data.FabulaRepository
import app.fabula.data.parseTimeSpan
import app.fabula.player.PlayerController
import app.fabula.ui.BrandGreen400
import app.fabula.ui.BrandGreen500
import coil3.compose.AsyncImage

@Composable
fun MiniPlayer(
    player: PlayerController,
    repository: FabulaRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by player.state.collectAsState()
    val book = state.book ?: return

    val chapterStart = state.currentChapter?.let { parseTimeSpan(it.start) } ?: 0.0
    val chapterEnd = state.currentChapter?.let { parseTimeSpan(it.end) } ?: state.durationInBook
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0.0)
    val chapterPos = (state.positionInBook - chapterStart).coerceIn(0.0, chapterDuration)
    val pct = if (chapterDuration > 0) (chapterPos / chapterDuration).toFloat().coerceIn(0f, 1f) else 0f

    val miniGradient = Brush.horizontalGradient(
        listOf(BrandGreen500, BrandGreen400)
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(miniGradient)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                repository.coverUrl(book)?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            val onGreen = MaterialTheme.colorScheme.onPrimary
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onGreen,
                    maxLines = 1
                )
                Text(
                    text = state.currentChapter?.title ?: book.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = onGreen.copy(alpha = 0.75f),
                    maxLines = 1
                )
            }
            IconButton(
                onClick = { player.togglePlayPause() },
                modifier = Modifier.size(40.dp)
            ) {
                if (state.isPlaying) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = onGreen,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Abspielen",
                        tint = onGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            val onGreen = MaterialTheme.colorScheme.onPrimary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(onGreen.copy(alpha = 0.2f), RoundedCornerShape(1.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct)
                        .height(2.dp)
                        .background(onGreen, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
