package app.fabula.ui.book

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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

// Aged-book palette. Fixed (theme-independent) so an old book looks old in
// both light and dark app themes.
private val AgedBackdrop = Color(0xFF15100A)     // dim, candle-lit library
private val AgedBackdropGlow = Color(0xFF2C2013) // warm glow behind the book
private val Parchment = Color(0xFFEBDCB8)         // page base
private val ParchmentShade = Color(0xFFD8C299)    // stacked page edges
private val ParchmentVignette = Color(0xFFB89A63) // darkened rim / foxing
private val Ink = Color(0xFF40301C)               // main text
private val InkSoft = Color(0xFF6E5638)           // rules, softer text
private val Leather = Color(0xFF3A2413)           // spine / cover frame
private val Gilt = Color(0xFF9A7638)              // faded gold ornaments

/**
 * Full-screen intro shown when a book is opened: a book "riffles" through its
 * chapters -- 3D page turns around the spine -- and settles on the chapter the
 * listener will resume at. For books with many chapters the flip count is
 * bounded ([MAX_FLIPS]) and the shown chapters are sampled across the book, so
 * it always lands exactly on [targetIndex] without animating dozens of pages.
 *
 * Styled like an old book: parchment pages with a vignette and a double-rule
 * frame, serif ink lettering with gilt ornaments, a leather spine and a
 * candle-lit backdrop. Tapping anywhere skips straight to the end.
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

    val density = LocalDensity.current
    // Faded, low-saturation cover so a modern cover photo reads like an old
    // inset plate rather than clashing with the parchment.
    val agedCoverFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.4f) }) }
    val backdropBrush = remember {
        Brush.radialGradient(listOf(AgedBackdropGlow, AgedBackdrop))
    }
    val vignetteBrush = remember {
        Brush.radialGradient(listOf(Color.Transparent, ParchmentVignette.copy(alpha = 0.55f)))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = screenAlpha.value }
            .background(backdropBrush)
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
                        .clip(RoundedCornerShape(6.dp))
                        .background(ParchmentVignette.copy(alpha = 0.55f))
                )
                Box(
                    modifier = Modifier
                        .offset(x = 3.dp, y = 4.dp)
                        .width(pageWidth)
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ParchmentShade)
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
                        .clip(RoundedCornerShape(6.dp))
                        .background(Parchment)
                ) {
                    // Aged rim shading over the whole page.
                    Box(Modifier.fillMaxSize().background(vignetteBrush))
                    // Dark leather spine strip on the left edge.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = pageWidth * 0.955f)
                            .background(Leather)
                    )
                    // Double-rule frame enclosing the page content.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)
                            .border(1.5.dp, InkSoft.copy(alpha = 0.55f))
                            .padding(3.dp)
                            .border(0.8.dp, InkSoft.copy(alpha = 0.4f))
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "❧",
                                color = Gilt,
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            // Cover as a framed, faded plate.
                            Box(
                                modifier = Modifier
                                    .size(62.dp)
                                    .border(1.dp, Leather)
                                    .padding(3.dp)
                                    .background(ParchmentShade)
                            ) {
                                coverUrl?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        colorFilter = agedCoverFilter,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "KAPITEL ${chapterIdx + 1}",
                                color = Ink,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                letterSpacing = 3.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "• • •",
                                color = Gilt,
                                fontFamily = FontFamily.Serif,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink,
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Tippen zum Überspringen",
                color = Parchment.copy(alpha = 0.6f),
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp
            )
        }
    }
}
