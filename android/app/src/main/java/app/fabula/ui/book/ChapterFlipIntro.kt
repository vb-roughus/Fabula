package app.fabula.ui.book

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import app.fabula.data.ChapterDto
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Tuning constants for the flip sequence.
private const val MAX_FLIPS = 9          // upper bound on animated page turns
private const val FLIP_FAST_MS = 55      // fastest turn (early pages)
private const val FLIP_SLOW_MS = 300     // slowest turn (settling on the target)
private const val HOLD_FAST_MS = 8       // pause between early pages
private const val HOLD_SLOW_MS = 120     // pause between the final pages
private const val FINAL_HOLD_MS = 620L   // dwell on the target chapter before leaving
private const val FADE_OUT_MS = 300

/**
 * Full-screen intro shown when a book is opened: a book "riffles" through its
 * chapters -- 3D page turns around the spine -- and settles on the chapter the
 * listener will resume at. For books with many chapters the flip count is
 * bounded ([MAX_FLIPS]) and the shown chapters are sampled across the book, so
 * it always lands exactly on [targetIndex] without animating dozens of pages.
 *
 * The overlay is opaque (paints the app background) so it covers the book
 * screen while it plays. Tapping anywhere skips straight to the end.
 */
@Composable
fun ChapterFlipIntro(
    coverUrl: String?,
    chapters: List<ChapterDto>,
    targetIndex: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (chapters.isEmpty()) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }
    val target = targetIndex.coerceIn(0, chapters.lastIndex)

    // Chapter indices to show, in order, always ending on `target`. Short books
    // show every chapter up to the target; long books show a sampled subset.
    val pageIndices = remember(target) {
        val steps = minOf(target, MAX_FLIPS)
        if (steps <= 0) listOf(target)
        else (0..steps).map { k -> (k.toFloat() / steps * target).roundToInt() }
    }

    var pagePos by remember { mutableIntStateOf(0) }
    val flip = remember { Animatable(1f) }          // 1 = turned away, 0 = flat on the stack
    val screenAlpha = remember { Animatable(1f) }
    var finishing by remember { mutableStateOf(false) }

    fun finishNow() {
        if (!finishing) {
            finishing = true
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        val lastPage = (pageIndices.size - 1).coerceAtLeast(1)
        for (k in pageIndices.indices) {
            pagePos = k
            flip.snapTo(1f)
            // Ease-in on the fraction (frac*frac) keeps the early turns fast and
            // only decelerates the last few -- the "settling" feel.
            val frac = (k.toFloat() / lastPage).let { it * it }
            flip.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = lerp(FLIP_FAST_MS, FLIP_SLOW_MS, frac),
                    easing = LinearEasing
                )
            )
            delay(lerp(HOLD_FAST_MS, HOLD_SLOW_MS, frac).toLong())
        }
        delay(FINAL_HOLD_MS)
        screenAlpha.animateTo(0f, tween(FADE_OUT_MS))
        finishNow()
    }

    val pageColor = MaterialTheme.colorScheme.surface
    val spineColor = MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectTapGestures { finishNow() } },
        contentAlignment = Alignment.Center
    ) {
        val pageWidth = minOf(maxWidth * 0.66f, 300.dp)
        val chapterIdx = pageIndices[pagePos].coerceIn(0, chapters.lastIndex)
        val chapter = chapters[chapterIdx]

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Two static "page edges" behind the front page to read as a book.
                Box(
                    modifier = Modifier
                        .offset(x = 7.dp, y = 8.dp)
                        .width(pageWidth)
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(spineColor.copy(alpha = 0.4f))
                )
                Box(
                    modifier = Modifier
                        .offset(x = 3.dp, y = 4.dp)
                        .width(pageWidth)
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(spineColor.copy(alpha = 0.7f))
                )

                // The animating front page, turning around its left spine.
                Box(
                    modifier = Modifier
                        .width(pageWidth)
                        .aspectRatio(0.72f)
                        .graphicsLayer {
                            rotationY = flip.value * -118f
                            cameraDistance = 16f * density.density
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            alpha = 1f - flip.value * 0.55f
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(pageColor)
                ) {
                    // Thin darker spine strip on the left edge.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = pageWidth * 0.94f)
                            .background(spineColor)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 26.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(66.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(spineColor)
                        ) {
                            coverUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Kapitel ${chapterIdx + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Tippen zum Überspringen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 12.sp
            )
        }
    }
}
