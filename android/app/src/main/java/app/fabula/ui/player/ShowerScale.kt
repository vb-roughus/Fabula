package app.fabula.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One named step of shower mode: how much louder, and what it is called. */
internal data class ShowerLevel(val db: Float, val label: String)

/** The named steps, quietest first. The scale itself moves in single decibels. */
internal val SHOWER_LEVELS = listOf(
    ShowerLevel(3f, "Leicht"),
    ShowerLevel(6f, "Mittel"),
    ShowerLevel(9f, "Stark"),
    ShowerLevel(12f, "Sehr stark"),
    ShowerLevel(15f, "Maximal")
)

/** The top of the scale; the boost goes no higher. */
internal const val SHOWER_MAX_DB = 15

/** The named step a boost sits on, or null for off or for a value in between. */
internal fun showerLevelFor(db: Float): ShowerLevel? =
    SHOWER_LEVELS.firstOrNull { abs(it.db - db) < 0.5f }

/** The whole decibel under the centre mark, for the scale moved [positionPx] along at [stepPx] per decibel. */
internal fun scaleStepAt(positionPx: Float, stepPx: Float): Int =
    if (stepPx <= 0f) 0 else (positionPx / stepPx).roundToInt().coerceIn(0, SHOWER_MAX_DB)

/**
 * How visible a tick is at [distancePx] from the centre: full in the middle,
 * fading towards the edges, never quite gone -- the look of a dial that turns
 * away from you.
 */
internal fun tickAlpha(distancePx: Float, halfWidthPx: Float): Float =
    if (halfWidthPx <= 0f) 1f else (1f - abs(distancePx) / halfWidthPx).coerceIn(0.12f, 1f)

/**
 * Shower mode's strength, set on a scale you swipe under a fixed mark --
 * the way Spotify's sleep timer sets its minutes.
 *
 * A tick per decibel, long ones labelled at the named steps. A flick keeps it
 * turning and it settles on the nearest tick; every tick it passes gives a
 * short buzz, and tapping a spot on the scale turns it there. The value shown
 * large above is what is set; it applies as the scale passes each tick, so
 * the change can be heard while choosing.
 */
@Composable
internal fun ShowerScaleControl(
    boostDb: Float,
    speakerOnly: Boolean,
    onBoostChange: (Float) -> Unit,
    playerFg: Color,
    playerMuted: Color
) {
    val step = boostDb.roundToInt().coerceIn(0, SHOWER_MAX_DB)
    val effectivelyOn = step > 0 && speakerOnly
    val accent = if (effectivelyOn) MaterialTheme.colorScheme.primary else playerFg

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (step > 0) Icons.Filled.WaterDrop else Icons.Outlined.WaterDrop,
                contentDescription = null,
                tint = if (effectivelyOn) MaterialTheme.colorScheme.primary else playerMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (step > 0 && !speakerOnly) "Duschmodus · wirkt nur über den Lautsprecher" else "Duschmodus",
                style = MaterialTheme.typography.labelMedium,
                color = playerMuted
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (step == 0) "Aus" else "+$step dB",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (step == 0) playerMuted else accent
        )
        Text(
            showerLevelFor(step.toFloat())?.label ?: if (step == 0) "Wischen zum Einschalten" else " ",
            style = MaterialTheme.typography.labelMedium,
            color = playerMuted
        )
        Spacer(Modifier.height(6.dp))
        ShowerScale(
            step = step,
            onStepChange = { onBoostChange(it.toFloat()) },
            markColor = if (step == 0) playerFg else accent,
            tickColor = playerFg
        )
    }
}

@Composable
private fun ShowerScale(
    step: Int,
    onStepChange: (Int) -> Unit,
    markColor: Color,
    tickColor: Color
) {
    val density = LocalDensity.current
    val stepPx = with(density) { 18.dp.toPx() }
    val maxPx = SHOWER_MAX_DB * stepPx
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = tickColor)

    // Where the scale stands under the centre mark, in pixels from "Aus".
    var position by remember { mutableFloatStateOf(step * stepPx) }
    var moving by remember { mutableStateOf(false) }
    var reported by remember { mutableIntStateOf(step) }

    // Follow a value changed from elsewhere, but never fight a finger or a fling.
    LaunchedEffect(step, stepPx) {
        if (!moving) {
            position = step * stepPx
            reported = step
        }
    }

    fun follow(px: Float) {
        position = px.coerceIn(0f, maxPx)
        val now = scaleStepAt(position, stepPx)
        if (now != reported) {
            reported = now
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onStepChange(now)
        }
    }

    // Runs on to the tick nearest to where it would come to rest.
    suspend fun settle(velocity: Float) {
        val projected = exponentialDecay<Float>().calculateTargetValue(position, -velocity)
        val target = scaleStepAt(projected, stepPx) * stepPx
        animate(position, target, initialVelocity = -velocity, animationSpec = spring(dampingRatio = 0.85f, stiffness = 250f)) { value, _ ->
            follow(value)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> follow(position - delta) },
                onDragStarted = { moving = true },
                onDragStopped = { velocity ->
                    settle(velocity)
                    moving = false
                }
            )
            .pointerInput(stepPx) {
                detectTapGestures { tap ->
                    val target = scaleStepAt(position + (tap.x - size.width / 2f), stepPx) * stepPx
                    scope.launch {
                        moving = true
                        animate(position, target, animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)) { value, _ ->
                            follow(value)
                        }
                        moving = false
                    }
                }
            }
    ) {
        val centre = size.width / 2f
        val half = size.width / 2f
        val baseline = size.height * 0.62f
        val minor = 9.dp.toPx()
        val major = 18.dp.toPx()

        for (db in 0..SHOWER_MAX_DB) {
            val x = centre + db * stepPx - position
            if (x < -stepPx || x > size.width + stepPx) continue
            val isMajor = db % 3 == 0
            val alpha = tickAlpha(x - centre, half)
            val height = if (isMajor) major else minor
            drawLine(
                color = tickColor.copy(alpha = alpha * if (isMajor) 0.9f else 0.55f),
                start = Offset(x, baseline - height),
                end = Offset(x, baseline),
                strokeWidth = (if (isMajor) 2.dp else 1.5.dp).toPx(),
                cap = StrokeCap.Round
            )
            if (isMajor) {
                val label = textMeasurer.measure(if (db == 0) "Aus" else "+$db", labelStyle)
                drawText(
                    label,
                    color = tickColor.copy(alpha = alpha * 0.8f),
                    topLeft = Offset(x - label.size.width / 2f, baseline + 4.dp.toPx())
                )
            }
        }

        // The fixed mark the scale turns under: a line through the ticks and a
        // small pointer above it.
        val markTop = baseline - major - 6.dp.toPx()
        drawLine(
            color = markColor,
            start = Offset(centre, markTop),
            end = Offset(centre, baseline + 2.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        val pointer = Path().apply {
            moveTo(centre - 5.dp.toPx(), markTop - 7.dp.toPx())
            lineTo(centre + 5.dp.toPx(), markTop - 7.dp.toPx())
            lineTo(centre, markTop - 1.dp.toPx())
            close()
        }
        drawPath(pointer, markColor)
    }
}
