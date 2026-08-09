package app.fabula.ui.book

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

// Tuning for the entrance. A three-quarter turn reads as a whirl without
// spinning so long that it looks like a loading spinner.
private const val WHIRL_TURN_DEGREES = -270f
// Alpha runs ahead of the scale so the icon is legible while it is still
// turning, instead of only popping in at the very end.
private const val WHIRL_FADE_SPEED = 2.5f

/**
 * An icon that spins into place the first time it appears.
 *
 * The bouncy spring overshoots slightly past its resting value, and that
 * overshoot is what produces the settle at the end. Rotation, scale and alpha
 * are all derived from that single value, so they stay in lockstep for free.
 *
 * [play] is decided by the caller: only the caller knows whether the icon is
 * genuinely new or was already there when its row was first composed.
 * [onEntranceFinished] fires once the motion has settled, so the caller can
 * remember not to replay it -- important inside a LazyColumn, whose rows are
 * disposed and re-created as they scroll in and out of view.
 */
@Composable
fun WhirlInIcon(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    iconSize: Dp,
    play: Boolean,
    onEntranceFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!play) {
        // Already earned -- render at rest, with no animation machinery at all.
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(iconSize)
        )
        return
    }

    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
        onEntranceFinished()
    }

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        // Lambda form: read on the draw pass, so the spring doesn't recompose
        // the row on every frame -- same reasoning as the full-player open
        // animation in Navigation.kt.
        modifier = modifier
            .size(iconSize)
            .graphicsLayer {
                val p = progress.value
                rotationZ = (1f - p) * WHIRL_TURN_DEGREES
                scaleX = p
                scaleY = p
                alpha = (p * WHIRL_FADE_SPEED).coerceAtMost(1f)
            }
    )
}
