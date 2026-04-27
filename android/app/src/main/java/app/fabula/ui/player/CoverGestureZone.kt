package app.fabula.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Overlays the cover area with two transparent gesture zones:
 * - left half: vertical drag adjusts the activity window's brightness,
 * - right half: vertical drag adjusts the music stream volume.
 *
 * A small black indicator with icon + percentage flashes for ~900 ms after
 * each change. Dragging up brightens / raises volume; dragging down does
 * the opposite.
 */
@Composable
fun CoverGestureZone(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    var volumeFloat by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }
    var brightness by remember {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (current >= 0f) current else 0.5f)
    }
    var volumeTick by remember { mutableIntStateOf(0) }
    var brightnessTick by remember { mutableIntStateOf(0) }

    val showVolume by produceState(initialValue = false, key1 = volumeTick) {
        if (volumeTick == 0) {
            value = false
            return@produceState
        }
        value = true
        delay(900)
        value = false
    }
    val showBrightness by produceState(initialValue = false, key1 = brightnessTick) {
        if (brightnessTick == 0) {
            value = false
            return@produceState
        }
        value = true
        delay(900)
        value = false
    }

    Box(modifier = modifier) {
        content()

        // Left half -- brightness.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            val current = activity?.window?.attributes?.screenBrightness ?: -1f
                            if (current >= 0f) brightness = current
                            brightnessTick++
                        }
                    ) { _, dragAmount ->
                        val rangePerPixel = 1f / size.height.coerceAtLeast(1)
                        brightness = (brightness - dragAmount * rangePerPixel).coerceIn(0.01f, 1f)
                        activity?.window?.let { window ->
                            window.attributes = window.attributes.also {
                                it.screenBrightness = brightness
                            }
                        }
                        brightnessTick++
                    }
                }
        )

        // Right half -- volume.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .pointerInput(maxVolume) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            volumeFloat = audioManager
                                .getStreamVolume(AudioManager.STREAM_MUSIC)
                                .toFloat()
                            volumeTick++
                        }
                    ) { _, dragAmount ->
                        val rangePerPixel = maxVolume.toFloat() / size.height.coerceAtLeast(1)
                        volumeFloat = (volumeFloat - dragAmount * rangePerPixel)
                            .coerceIn(0f, maxVolume.toFloat())
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            volumeFloat.roundToInt(),
                            0
                        )
                        volumeTick++
                    }
                }
        )

        OverlayIndicator(
            visible = showBrightness,
            level = brightness,
            icon = Icons.Filled.BrightnessHigh,
            alignment = Alignment.CenterStart
        )
        OverlayIndicator(
            visible = showVolume,
            level = volumeFloat / maxVolume,
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            alignment = Alignment.CenterEnd
        )
    }

    // Re-sync external volume changes (hardware keys etc.) so the indicator
    // doesn't snap back to a stale value the next time the user drags.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            volumeFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        }
    }
}

@Composable
private fun BoxScope.OverlayIndicator(
    visible: Boolean,
    level: Float,
    icon: ImageVector,
    alignment: Alignment
) {
    if (!visible) return
    Column(
        modifier = Modifier
            .align(alignment)
            .padding(20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${(level.coerceIn(0f, 1f) * 100).roundToInt()}%",
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
