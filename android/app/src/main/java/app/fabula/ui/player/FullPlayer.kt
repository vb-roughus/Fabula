package app.fabula.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.formatClock
import app.fabula.data.parseTimeSpan
import app.fabula.data.toTimeSpanString
import app.fabula.player.PlayerController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val SPEED_CHOICES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
private const val SLEEP_TIMER_DURATION_MS = 30L * 60 * 1000  // 30 minutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    player: PlayerController,
    repository: FabulaRepository,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by player.state.collectAsState()
    val book = state.book ?: return
    val scope = rememberCoroutineScope()

    var bookmarkManagerOpen by remember { mutableStateOf(false) }
    var bookmarkSavedFlash by remember { mutableStateOf(false) }
    var pulseEnabled by remember { mutableStateOf(false) }
    // Speed is hoisted here so it survives the portrait <-> landscape layout
    // swap (each layout would otherwise re-create its own utility row).
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    // Dusch-Modus controls live behind the water-drop toggle in the utility
    // row and roll out on demand, so the player stays uncluttered.
    var showerExpanded by remember { mutableStateOf(false) }
    val showerEffectivelyOn = state.showerBoostDb > 0f && state.showerSpeakerOnly

    // Aggressive breathing for the gradient when pulse mode is active.
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseValue by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-amount"
    )
    val pulseFactor = if (pulseEnabled) pulseValue else 0f

    val baseColor = MaterialTheme.colorScheme.background
    val accentColor = MaterialTheme.colorScheme.primary
    // Static 10% accent base on the top edge, brightened up to 50% when
    // pulsing -- pre-blended onto the navy so the gradient itself is fully
    // opaque and predictable.
    val topTint = accentColor.copy(alpha = 0.10f + 0.40f * pulseFactor).compositeOver(baseColor)
    val backgroundGradient = Brush.verticalGradient(
        0.0f to topTint,
        0.45f to baseColor,
        1.0f to baseColor
    )

    val pos = state.positionInBook

    val chapters = book.chapters
    val chapterIdx = state.currentChapter?.index ?: -1
    val prevChapter = chapters.getOrNull(chapterIdx - 1)
    val nextChapter = chapters.getOrNull(chapterIdx + 1)

    val chapterStart = state.currentChapter?.let { parseTimeSpan(it.start) } ?: 0.0
    val chapterEnd = state.currentChapter?.let { parseTimeSpan(it.end) } ?: state.durationInBook
    val chapterDuration = (chapterEnd - chapterStart).coerceAtLeast(0.0)
    val chapterPos = (pos - chapterStart).coerceIn(0.0, chapterDuration)

    val whiteText = Color.White
    val mutedText = Color.White.copy(alpha = 0.65f)

    val onAddBookmark: () -> Unit = {
        scope.launch {
            val api = repository.apiOrNull() ?: return@launch
            runCatching {
                api.createBookmark(
                    book.id,
                    CreateBookmarkRequest(
                        position = toTimeSpanString(state.positionInBook),
                        note = null
                    )
                )
                repository.bumpBookmarksRevision()
                bookmarkSavedFlash = true
            }.onFailure { t ->
                repository.logFailure(
                    "FullPlayer.createBookmark book=${book.id} pos=${state.positionInBook}",
                    t
                )
            }
        }
    }

    // Shared building blocks -- assembled differently for portrait vs. landscape
    // so the two orientations never drift out of sync.
    val topBar: @Composable () -> Unit = {
        PlayerTopBar(
            label = if (book.series != null) "AUS DER SERIE" else "WIRD ABGESPIELT",
            title = book.series ?: book.title,
            onCollapse = onCollapse,
            onManageBookmarks = { bookmarkManagerOpen = true },
            whiteText = whiteText,
            mutedText = mutedText
        )
    }
    val cover: @Composable (Modifier) -> Unit = { mod ->
        PlayerCover(
            coverUrl = repository.coverUrl(book),
            title = book.title,
            modifier = mod
        )
    }
    val titleRow: @Composable () -> Unit = {
        PlayerTitleRow(
            title = state.currentChapter?.title ?: book.title,
            author = book.authors.joinToString(", ").ifBlank { book.title },
            bookmarkSaved = bookmarkSavedFlash,
            onAddBookmark = onAddBookmark,
            whiteText = whiteText,
            mutedText = mutedText
        )
    }
    val scrubber: @Composable () -> Unit = {
        PlayerScrubber(
            chapterPos = chapterPos,
            chapterStart = chapterStart,
            chapterDuration = chapterDuration,
            onSeek = { player.seekInBook(it) },
            whiteText = whiteText,
            mutedText = mutedText
        )
    }
    val mainControls: @Composable () -> Unit = {
        PlayerMainControls(
            isPlaying = state.isPlaying,
            hasPrev = prevChapter != null,
            hasNext = nextChapter != null,
            onSkipBack = { player.skip(-30.0) },
            onPrevChapter = { prevChapter?.let { player.jumpToChapter(it) } },
            onTogglePlay = { player.togglePlayPause() },
            onNextChapter = { nextChapter?.let { player.jumpToChapter(it) } },
            onSkipForward = { player.skip(30.0) },
            whiteText = whiteText
        )
    }
    val showerSection: @Composable () -> Unit = {
        PlayerShowerSection(
            visible = showerExpanded,
            boostDb = state.showerBoostDb,
            speakerOnly = state.showerSpeakerOnly,
            onBoostChange = { player.setShowerBoostDb(it) },
            whiteText = whiteText,
            mutedText = mutedText
        )
    }
    val utilityRow: @Composable () -> Unit = {
        PlayerUtilityRow(
            currentSpeed = currentSpeed,
            onSelectSpeed = { currentSpeed = it; player.setSpeed(it) },
            showerEffectivelyOn = showerEffectivelyOn,
            showerExpanded = showerExpanded,
            onToggleShower = { showerExpanded = !showerExpanded },
            pulseEnabled = pulseEnabled,
            onTogglePulse = { pulseEnabled = !pulseEnabled },
            sleepRemainingMs = state.sleepTimerRemainingMs,
            onStartSleep = { player.startSleepTimer(SLEEP_TIMER_DURATION_MS) },
            onCancelSleep = { player.cancelSleepTimer() },
            whiteText = whiteText
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(brush = backgroundGradient)
            // Absorb taps on empty areas (spacers, padding) so they cannot
            // fall through to the NavHost content rendered behind the player.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .systemBarsPadding()
    ) {
        if (maxWidth > maxHeight) {
            // Landscape: a square cover on the left, the controls stacked in a
            // scrollable column on the right so nothing is ever pushed off the
            // (short) screen. This is the case that previously rendered the
            // cover as tall as the screen was wide and hid every control.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                topBar()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    cover(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .padding(top = 8.dp, bottom = 8.dp, end = 20.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        titleRow()
                        Spacer(Modifier.height(16.dp))
                        scrubber()
                        Spacer(Modifier.height(16.dp))
                        mainControls()
                        Spacer(Modifier.height(12.dp))
                        showerSection()
                        utilityRow()
                    }
                }
            }
        } else {
            // Portrait: large square cover with the controls flowing beneath,
            // weighted spacers keeping everything vertically balanced.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                topBar()
                Spacer(Modifier.weight(0.5f))
                cover(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                Spacer(Modifier.weight(0.4f))
                titleRow()
                Spacer(Modifier.height(16.dp))
                scrubber()
                Spacer(Modifier.height(16.dp))
                mainControls()
                Spacer(Modifier.weight(0.3f))
                showerSection()
                utilityRow()
            }
        }
    }

    if (bookmarkManagerOpen) {
        BookmarkManagerSheet(
            bookId = book.id,
            book = book,
            repository = repository,
            onDismiss = { bookmarkManagerOpen = false },
            onPlayBookmark = { bm ->
                player.seekInBook(parseTimeSpan(bm.position))
                player.play()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    label: String,
    title: String,
    onCollapse: () -> Unit,
    onManageBookmarks: () -> Unit,
    whiteText: Color,
    mutedText: Color,
    modifier: Modifier = Modifier
) {
    var moreMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Minimieren",
                tint = whiteText
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = mutedText,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = whiteText,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
        Box {
            IconButton(onClick = { moreMenuOpen = true }) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "Mehr",
                    tint = whiteText
                )
            }
            DropdownMenu(
                expanded = moreMenuOpen,
                onDismissRequest = { moreMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Lesezeichen verwalten") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    },
                    onClick = {
                        moreMenuOpen = false
                        onManageBookmarks()
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayerCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    // Cover -- left half drags the screen brightness, right half the music
    // stream volume. See CoverGestureZone.kt.
    CoverGestureZone(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
    ) {
        coverUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PlayerTitleRow(
    title: String,
    author: String,
    bookmarkSaved: Boolean,
    onAddBookmark: () -> Unit,
    whiteText: Color,
    mutedText: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = whiteText,
                maxLines = 1
            )
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = mutedText,
                maxLines = 1
            )
        }
        IconButton(onClick = onAddBookmark) {
            Icon(
                Icons.Filled.BookmarkAdd,
                contentDescription = "Lesezeichen setzen",
                tint = if (bookmarkSaved) MaterialTheme.colorScheme.primary else whiteText
            )
        }
    }
}

@Composable
private fun PlayerScrubber(
    chapterPos: Double,
    chapterStart: Double,
    chapterDuration: Double,
    onSeek: (Double) -> Unit,
    whiteText: Color,
    mutedText: Color,
    modifier: Modifier = Modifier
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val sliderValue = scrubFraction
        ?: if (chapterDuration > 0) (chapterPos / chapterDuration).toFloat().coerceIn(0f, 1f) else 0f

    // Slim chapter-relative scrubber: time / slider / time on a single line.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatClock(chapterPos),
            style = MaterialTheme.typography.labelSmall,
            color = mutedText,
            modifier = Modifier.width(40.dp)
        )
        Slider(
            value = sliderValue,
            onValueChange = { scrubFraction = it },
            onValueChangeFinished = {
                scrubFraction?.let { fraction ->
                    onSeek(chapterStart + fraction * chapterDuration)
                }
                scrubFraction = null
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = whiteText,
                activeTrackColor = whiteText,
                inactiveTrackColor = whiteText.copy(alpha = 0.25f)
            ),
            thumb = {
                // Wrap the visible 10 dp circle in a 20 dp box so the slider
                // treats the thumb as 20 dp wide/tall -- keeps it aligned with
                // the track wrapper below and gives the track a known half-thumb
                // inset for its drawn line.
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(whiteText)
                    )
                }
            },
            track = { sliderState ->
                val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val fraction = if (range > 0f)
                    ((sliderState.value - sliderState.valueRange.start) / range).coerceIn(0f, 1f)
                else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(whiteText.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(whiteText)
                        )
                    }
                }
            }
        )
        Text(
            formatClock(chapterDuration),
            style = MaterialTheme.typography.labelSmall,
            color = mutedText,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PlayerMainControls(
    isPlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onSkipBack: () -> Unit,
    onPrevChapter: () -> Unit,
    onTogglePlay: () -> Unit,
    onNextChapter: () -> Unit,
    onSkipForward: () -> Unit,
    whiteText: Color,
    modifier: Modifier = Modifier
) {
    // -30s | prev chapter | PLAY/PAUSE | next chapter | +30s
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSkipBack) {
            Icon(
                Icons.Filled.Replay30,
                contentDescription = "30 Sek. zurück",
                tint = whiteText,
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onPrevChapter, enabled = hasPrev) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Vorheriges Kapitel",
                tint = if (hasPrev) whiteText else whiteText.copy(alpha = 0.3f),
                modifier = Modifier.size(40.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(whiteText)
                .clickable { onTogglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Abspielen",
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(40.dp)
            )
        }
        IconButton(onClick = onNextChapter, enabled = hasNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Nächstes Kapitel",
                tint = if (hasNext) whiteText else whiteText.copy(alpha = 0.3f),
                modifier = Modifier.size(40.dp)
            )
        }
        IconButton(onClick = onSkipForward) {
            Icon(
                Icons.Filled.Forward30,
                contentDescription = "30 Sek. vor",
                tint = whiteText,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun PlayerShowerSection(
    visible: Boolean,
    boostDb: Float,
    speakerOnly: Boolean,
    onBoostChange: (Float) -> Unit,
    whiteText: Color,
    mutedText: Color
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) +
            fadeIn(tween(180)),
        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) +
            fadeOut(tween(120))
    ) {
        Column {
            ShowerModeRow(
                boostDb = boostDb,
                speakerOnly = speakerOnly,
                onBoostChange = onBoostChange,
                whiteText = whiteText,
                mutedText = mutedText
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlayerUtilityRow(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    showerEffectivelyOn: Boolean,
    showerExpanded: Boolean,
    onToggleShower: () -> Unit,
    pulseEnabled: Boolean,
    onTogglePulse: () -> Unit,
    sleepRemainingMs: Long?,
    onStartSleep: () -> Unit,
    onCancelSleep: () -> Unit,
    whiteText: Color,
    modifier: Modifier = Modifier
) {
    var speedMenuOpen by remember { mutableStateOf(false) }

    // Speed picker on the left, then shower / pulse / sleep on the right.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { speedMenuOpen = true }) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = null,
                    tint = whiteText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${"%.2f".format(currentSpeed).trimEnd('0').trimEnd('.', ',')}×",
                    color = whiteText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            DropdownMenu(
                expanded = speedMenuOpen,
                onDismissRequest = { speedMenuOpen = false }
            ) {
                SPEED_CHOICES.forEach { s ->
                    DropdownMenuItem(
                        text = { Text("${s}×") },
                        onClick = {
                            onSelectSpeed(s)
                            speedMenuOpen = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = onToggleShower,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (showerEffectivelyOn) Icons.Filled.WaterDrop else Icons.Outlined.WaterDrop,
                contentDescription = if (showerExpanded) "Dusch-Modus ausblenden" else "Dusch-Modus einblenden",
                tint = when {
                    showerEffectivelyOn -> MaterialTheme.colorScheme.primary
                    showerExpanded -> whiteText
                    else -> whiteText.copy(alpha = 0.7f)
                },
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onTogglePulse,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.GraphicEq,
                contentDescription = if (pulseEnabled) "Pulse-Modus aus" else "Pulse-Modus an",
                tint = if (pulseEnabled) MaterialTheme.colorScheme.primary else whiteText.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }

        if (sleepRemainingMs != null) {
            val totalSec = (sleepRemainingMs + 999L) / 1000L  // round up
            val mm = totalSec / 60
            val ss = totalSec % 60
            TextButton(onClick = onCancelSleep) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "Schlaf-Timer aktiv",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "%d:%02d".format(mm, ss),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            IconButton(
                onClick = onStartSleep,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Schlaf-Timer zurücksetzen",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            TextButton(onClick = onStartSleep) {
                Icon(
                    Icons.Outlined.Bedtime,
                    contentDescription = null,
                    tint = whiteText,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "30 min",
                    color = whiteText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowerModeRow(
    boostDb: Float,
    speakerOnly: Boolean,
    onBoostChange: (Float) -> Unit,
    whiteText: Color,
    mutedText: Color
) {
    val active = boostDb > 0f
    val effectivelyOn = active && speakerOnly
    val iconTint = when {
        effectivelyOn -> MaterialTheme.colorScheme.primary
        active && !speakerOnly -> whiteText.copy(alpha = 0.35f)
        else -> whiteText.copy(alpha = 0.7f)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (effectivelyOn) Icons.Filled.WaterDrop else Icons.Outlined.WaterDrop,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Dusch-Modus",
                style = MaterialTheme.typography.labelLarge,
                color = if (effectivelyOn) whiteText else mutedText
            )
            if (active && !speakerOnly) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "· Nur Lautsprecher",
                    style = MaterialTheme.typography.labelSmall,
                    color = whiteText.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (boostDb <= 0f) "AUS" else "+${boostDb.toInt()} dB",
                style = MaterialTheme.typography.labelMedium,
                color = if (effectivelyOn) MaterialTheme.colorScheme.primary else mutedText
            )
        }
        Slider(
            value = boostDb,
            onValueChange = onBoostChange,
            valueRange = 0f..15f,
            steps = 14,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp),
            colors = SliderDefaults.colors(
                thumbColor = if (effectivelyOn) MaterialTheme.colorScheme.primary else whiteText.copy(alpha = 0.5f),
                activeTrackColor = if (effectivelyOn) MaterialTheme.colorScheme.primary else whiteText.copy(alpha = 0.5f),
                inactiveTrackColor = whiteText.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}
