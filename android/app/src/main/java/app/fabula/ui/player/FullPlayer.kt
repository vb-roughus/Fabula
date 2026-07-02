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
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fabula.data.CreateBookmarkRequest
import app.fabula.data.CreateHighlightRequest
import app.fabula.data.FabulaRepository
import app.fabula.data.HighlightDto
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
    var highlightManagerOpen by remember { mutableStateOf(false) }
    // While capturing, the frozen end position (book seconds) of the second
    // tap; the create dialog reads it together with state.highlightStartSec.
    var highlightDialogOpen by remember { mutableStateOf(false) }
    var highlightEndSec by remember { mutableFloatStateOf(0f) }
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

    val highlightsRevision by repository.highlightsRevision.collectAsState()
    var highlights by remember { mutableStateOf<List<HighlightDto>>(emptyList()) }
    LaunchedEffect(book.id, highlightsRevision) {
        runCatching {
            val api = repository.apiOrNull() ?: return@runCatching
            highlights = api.listHighlights(book.id)
        }
    }
    // Highlight ranges intersecting the current chapter, as [0..1] fractions
    // of the chapter-relative scrubber, for the coloured bands on the track.
    val highlightBands: List<Pair<Float, Float>> = if (chapterDuration <= 0.0) emptyList() else
        highlights.mapNotNull { h ->
            val hs = parseTimeSpan(h.start)
            val he = parseTimeSpan(h.end)
            if (he <= chapterStart || hs >= chapterStart + chapterDuration) null
            else {
                val s = ((hs - chapterStart) / chapterDuration).coerceIn(0.0, 1.0).toFloat()
                val e = ((he - chapterStart) / chapterDuration).coerceIn(0.0, 1.0).toFloat()
                if (e > s) s to e else null
            }
        }
    val capturing = state.highlightStartSec != null

    // Foreground for the player, derived from the theme so the controls stay
    // legible in both dark mode (light-on-navy) and light mode (dark-on-light).
    // Previously hardcoded white, which vanished on the light background.
    val playerFg = MaterialTheme.colorScheme.onBackground
    val playerMuted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)

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

    // First tap marks the start, second tap freezes the end and opens the
    // dialog to add a description/note before saving.
    val onToggleHighlight: () -> Unit = {
        if (state.highlightStartSec == null) {
            player.beginHighlight()
        } else {
            highlightEndSec = state.positionInBook.toFloat()
            highlightDialogOpen = true
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
            onManageHighlights = { highlightManagerOpen = true },
            capturing = capturing,
            onDiscardHighlight = { player.cancelHighlight() },
            playerFg = playerFg,
            playerMuted = playerMuted
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
            highlightCapturing = capturing,
            onToggleHighlight = onToggleHighlight,
            playerFg = playerFg,
            playerMuted = playerMuted
        )
    }
    val scrubber: @Composable () -> Unit = {
        PlayerScrubber(
            chapterPos = chapterPos,
            chapterStart = chapterStart,
            chapterDuration = chapterDuration,
            onSeek = { player.seekInBook(it) },
            highlightBands = highlightBands,
            playerFg = playerFg,
            playerMuted = playerMuted
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
            playerFg = playerFg
        )
    }
    val showerSection: @Composable () -> Unit = {
        PlayerShowerSection(
            visible = showerExpanded,
            boostDb = state.showerBoostDb,
            speakerOnly = state.showerSpeakerOnly,
            onBoostChange = { player.setShowerBoostDb(it) },
            playerFg = playerFg,
            playerMuted = playerMuted
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
            playerFg = playerFg
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

    if (highlightManagerOpen) {
        HighlightManagerSheet(
            bookId = book.id,
            book = book,
            repository = repository,
            onDismiss = { highlightManagerOpen = false },
            onPlayHighlight = { h ->
                player.seekInBook(parseTimeSpan(h.start))
                player.play()
            }
        )
    }

    if (highlightDialogOpen) {
        val startSec = state.highlightStartSec ?: highlightEndSec.toDouble()
        val endSec = highlightEndSec.toDouble()
        val lo = minOf(startSec, endSec)
        val hi = maxOf(startSec, endSec)
        CreateHighlightDialog(
            startSec = lo,
            endSec = hi,
            onDismiss = {
                highlightDialogOpen = false
                player.cancelHighlight()
            },
            onSave = { title, note ->
                highlightDialogOpen = false
                player.cancelHighlight()
                scope.launch {
                    val api = repository.apiOrNull() ?: return@launch
                    runCatching {
                        api.createHighlight(
                            book.id,
                            CreateHighlightRequest(
                                start = toTimeSpanString(lo),
                                end = toTimeSpanString(hi),
                                title = title,
                                note = note
                            )
                        )
                        repository.bumpHighlightsRevision()
                    }.onFailure { t ->
                        repository.logFailure(
                            "FullPlayer.createHighlight book=${book.id} $lo..$hi",
                            t
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun CreateHighlightDialog(
    startSec: Double,
    endSec: Double,
    onDismiss: () -> Unit,
    onSave: (title: String?, note: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Markierung speichern") },
        text = {
            Column {
                Text(
                    "${formatClock(startSec)} – ${formatClock(endSec)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Beschreibung (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notiz (optional)") },
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title.trim().ifBlank { null }, note.trim().ifBlank { null })
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Verwerfen") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    label: String,
    title: String,
    onCollapse: () -> Unit,
    onManageBookmarks: () -> Unit,
    onManageHighlights: () -> Unit,
    capturing: Boolean,
    onDiscardHighlight: () -> Unit,
    playerFg: Color,
    playerMuted: Color,
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
                tint = playerFg
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = playerMuted,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = playerFg,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
        Box {
            IconButton(onClick = { moreMenuOpen = true }) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "Mehr",
                    tint = playerFg
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
                DropdownMenuItem(
                    text = { Text("Markierungen verwalten") },
                    leadingIcon = {
                        Icon(Icons.Filled.BorderColor, contentDescription = null)
                    },
                    onClick = {
                        moreMenuOpen = false
                        onManageHighlights()
                    }
                )
                if (capturing) {
                    DropdownMenuItem(
                        text = { Text("Markierung verwerfen") },
                        leadingIcon = {
                            Icon(Icons.Outlined.BorderColor, contentDescription = null)
                        },
                        onClick = {
                            moreMenuOpen = false
                            onDiscardHighlight()
                        }
                    )
                }
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
    highlightCapturing: Boolean,
    onToggleHighlight: () -> Unit,
    playerFg: Color,
    playerMuted: Color,
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
                color = playerFg,
                maxLines = 1
            )
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = playerMuted,
                maxLines = 1
            )
        }
        IconButton(onClick = onToggleHighlight) {
            Icon(
                imageVector = if (highlightCapturing) Icons.Filled.BorderColor else Icons.Outlined.BorderColor,
                contentDescription = if (highlightCapturing) "Markierung beenden" else "Passage markieren",
                tint = if (highlightCapturing) HighlightColor else playerFg
            )
        }
        IconButton(onClick = onAddBookmark) {
            Icon(
                Icons.Filled.BookmarkAdd,
                contentDescription = "Lesezeichen setzen",
                tint = if (bookmarkSaved) MaterialTheme.colorScheme.primary else playerFg
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScrubber(
    chapterPos: Double,
    chapterStart: Double,
    chapterDuration: Double,
    onSeek: (Double) -> Unit,
    highlightBands: List<Pair<Float, Float>>,
    playerFg: Color,
    playerMuted: Color,
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
            color = playerMuted,
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
                thumbColor = playerFg,
                activeTrackColor = playerFg,
                inactiveTrackColor = playerFg.copy(alpha = 0.25f)
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
                            .background(playerFg)
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
                    // Highlighter bands behind the track line: the Material3
                    // track is measured at slider_width - thumb_width, so this
                    // Canvas' [0..1] maps 1:1 to the thumb's travel and lines up
                    // with the fill below.
                    if (highlightBands.isNotEmpty()) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        ) {
                            val h = size.height
                            highlightBands.forEach { (s, e) ->
                                val x = size.width * s
                                val w = (size.width * (e - s)).coerceAtLeast(3.dp.toPx())
                                drawRoundRect(
                                    color = HighlightColor.copy(alpha = 0.85f),
                                    topLeft = Offset(x, 0f),
                                    size = Size(w, h),
                                    cornerRadius = CornerRadius(h / 2f, h / 2f)
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(playerFg.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.dp))
                                .background(playerFg)
                        )
                    }
                }
            }
        )
        Text(
            formatClock(chapterDuration),
            style = MaterialTheme.typography.labelSmall,
            color = playerMuted,
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
    playerFg: Color,
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
                tint = playerFg,
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onPrevChapter, enabled = hasPrev) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Vorheriges Kapitel",
                tint = if (hasPrev) playerFg else playerFg.copy(alpha = 0.3f),
                modifier = Modifier.size(40.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(playerFg)
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
                tint = if (hasNext) playerFg else playerFg.copy(alpha = 0.3f),
                modifier = Modifier.size(40.dp)
            )
        }
        IconButton(onClick = onSkipForward) {
            Icon(
                Icons.Filled.Forward30,
                contentDescription = "30 Sek. vor",
                tint = playerFg,
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
    playerFg: Color,
    playerMuted: Color
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
                playerFg = playerFg,
                playerMuted = playerMuted
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
    playerFg: Color,
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
                    tint = playerFg,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${"%.2f".format(currentSpeed).trimEnd('0').trimEnd('.', ',')}×",
                    color = playerFg,
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
                    showerExpanded -> playerFg
                    else -> playerFg.copy(alpha = 0.7f)
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
                tint = if (pulseEnabled) MaterialTheme.colorScheme.primary else playerFg.copy(alpha = 0.7f),
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
                    tint = playerFg,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "30 min",
                    color = playerFg,
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
    playerFg: Color,
    playerMuted: Color
) {
    val active = boostDb > 0f
    val effectivelyOn = active && speakerOnly
    val iconTint = when {
        effectivelyOn -> MaterialTheme.colorScheme.primary
        active && !speakerOnly -> playerFg.copy(alpha = 0.35f)
        else -> playerFg.copy(alpha = 0.7f)
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
                color = if (effectivelyOn) playerFg else playerMuted
            )
            if (active && !speakerOnly) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "· Nur Lautsprecher",
                    style = MaterialTheme.typography.labelSmall,
                    color = playerFg.copy(alpha = 0.4f)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (boostDb <= 0f) "AUS" else "+${boostDb.toInt()} dB",
                style = MaterialTheme.typography.labelMedium,
                color = if (effectivelyOn) MaterialTheme.colorScheme.primary else playerMuted
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
                thumbColor = if (effectivelyOn) MaterialTheme.colorScheme.primary else playerFg.copy(alpha = 0.5f),
                activeTrackColor = if (effectivelyOn) MaterialTheme.colorScheme.primary else playerFg.copy(alpha = 0.5f),
                inactiveTrackColor = playerFg.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}
