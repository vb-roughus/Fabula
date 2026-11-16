package app.fabula.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The smallest cover worth drawing in the full player -- the size of the mini
 * player's thumbnail. Below it the cover is left out rather than shown as a
 * smudge, which is what a phone held sideways usually comes down to.
 */
internal val PLAYER_MIN_COVER: Dp = 40.dp

/**
 * Screens shorter than this tighten the gaps between the controls, which hands
 * the cover a little more of a height that the controls otherwise take almost
 * entirely. Phones held sideways land here; tablets and phones held upright
 * don't.
 */
internal val PLAYER_SHORT_HEIGHT: Dp = 480.dp

/**
 * The cover's edge length in the space left over once the controls have been
 * laid out: the largest square that fits, or zero when that square would be
 * smaller than [PLAYER_MIN_COVER].
 */
internal fun playerCoverSide(width: Dp, height: Dp): Dp {
    val side = minOf(width, height)
    return if (side < PLAYER_MIN_COVER) 0.dp else side
}

/** Whether the player is laid out on a screen short enough to tighten its gaps. */
internal fun playerIsShort(height: Dp): Boolean = height < PLAYER_SHORT_HEIGHT
