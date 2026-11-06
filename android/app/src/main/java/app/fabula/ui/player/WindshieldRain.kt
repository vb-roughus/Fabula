package app.fabula.ui.player

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rain on a windscreen, over the full player while shower mode is on.
 *
 * Small droplets keep landing and stay where they land. Larger drops cling
 * for a while, then run down, swallowing the droplets in their way, growing
 * as they go and leaving a trail of tiny ones behind -- the way water moves
 * on glass rather than the way it falls through air.
 *
 * Purely decorative and out of the way: it takes no touches, so every control
 * underneath works as before; it only runs while [visible]; and it stays off
 * for anyone who has switched animations off in the system settings.
 */
@Composable
fun WindshieldRain(visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    AnimatedVisibility(
        visible = visible && !animationsOff,
        enter = fadeIn(tween(800)),
        exit = fadeOut(tween(600)),
        modifier = modifier
    ) {
        val density = LocalDensity.current.density
        val rain = remember { RainSimulation(density, Random(System.nanoTime())) }
        var frame by remember { mutableLongStateOf(0L) }

        // One step per frame, for as long as the rain is on screen; leaving
        // composition cancels it, so nothing runs while it is not shown.
        LaunchedEffect(rain) {
            var last = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                rain.step((now - last) / 1_000_000_000f)
                last = now
                frame = now
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rain.resize(it.width.toFloat(), it.height.toFloat()) }
        ) {
            // Reading the frame is what redraws the canvas each step.
            if (frame >= 0L) rain.drops.forEach { drawDrop(it) }
        }
    }
}

/** A drop of water on the glass, as a little lens: dark rim below, light gathered inside, a glint above. */
private fun DrawScope.drawDrop(drop: RainSimulation.Drop) {
    val a = drop.alpha
    if (a <= 0f) return
    val r = drop.r
    // A running drop is drawn a little longer than wide.
    val stretch = if (drop.sliding) 1f + min(0.35f, drop.vy / (r * 60f)) else 1f
    val w = r * 2f
    val h = r * 2f * stretch
    val topLeft = Offset(drop.x - r, drop.y - h / 2f)

    drawOval(Color.Black.copy(alpha = 0.22f * a), topLeft + Offset(r * 0.12f, r * 0.22f), Size(w, h))
    drawOval(Color.White.copy(alpha = 0.10f * a), topLeft, Size(w, h))
    drawOval(
        Color.White.copy(alpha = 0.12f * a),
        Offset(drop.x - r * 0.62f, drop.y - h * 0.08f),
        Size(r * 1.24f, h * 0.55f)
    )
    drawCircle(
        Color.White.copy(alpha = 0.6f * a),
        radius = r * 0.26f,
        center = Offset(drop.x - r * 0.36f, drop.y - h * 0.22f)
    )
}

/**
 * The water on the glass, advanced a frame at a time.
 *
 * Kept apart from drawing and free of Compose types, so it can be tested:
 * the counts it promises to keep to are what keeps it cheap.
 */
internal class RainSimulation(
    private val density: Float,
    private val random: Random
) {
    class Drop(
        var x: Float,
        var y: Float,
        var r: Float,
        val big: Boolean,
        var clingUntil: Float = 0f,
        var sliding: Boolean = false,
        var vy: Float = 0f,
        var alpha: Float = 0f,
        var sinceTrail: Float = 0f,
        val phase: Float = 0f
    )

    private val _drops = ArrayList<Drop>()
    val drops: List<Drop> get() = _drops

    private var width = 0f
    private var height = 0f
    private var time = 0f
    private var smallDue = 0f
    private var bigDue = 0f

    fun resize(w: Float, h: Float) {
        width = w
        height = h
    }

    fun step(rawDt: Float) {
        if (width <= 0f || height <= 0f) return
        // A long gap -- the app was in the background -- is not a minute of rain.
        val dt = rawDt.coerceIn(0f, MAX_STEP)
        time += dt

        spawn(dt)

        val iterator = _drops.listIterator()
        while (iterator.hasNext()) {
            val drop = iterator.next()
            drop.alpha = min(1f, drop.alpha + dt / FADE_IN)
            if (drop.big) move(drop, dt)
        }
        absorb()
        _drops.removeAll { it.y - it.r > height }

        // The oldest droplets give way to new ones, so the glass never fills up.
        var small = _drops.count { !it.big }
        if (small > MAX_SMALL) {
            val iter = _drops.iterator()
            while (iter.hasNext() && small > MAX_SMALL) {
                if (!iter.next().big) {
                    iter.remove()
                    small--
                }
            }
        }
    }

    private fun spawn(dt: Float) {
        smallDue += dt * SMALL_PER_SECOND
        while (smallDue >= 1f) {
            smallDue -= 1f
            // Mostly tiny, now and then a little bigger.
            val r = (SMALL_MIN + (SMALL_MAX - SMALL_MIN) * random.nextFloat() * random.nextFloat()) * density
            _drops.add(Drop(random.nextFloat() * width, random.nextFloat() * height, r, big = false))
        }

        bigDue += dt * BIG_PER_SECOND
        while (bigDue >= 1f) {
            bigDue -= 1f
            if (_drops.count { it.big } >= MAX_BIG) continue
            val r = (BIG_MIN + (BIG_MAX - BIG_MIN) * random.nextFloat()) * density
            _drops.add(
                Drop(
                    x = random.nextFloat() * width,
                    y = random.nextFloat() * height * 0.7f,
                    r = r,
                    big = true,
                    clingUntil = time + CLING_MIN + random.nextFloat() * (CLING_MAX - CLING_MIN),
                    phase = random.nextFloat() * 2f * PI.toFloat()
                )
            )
        }
    }

    private fun move(drop: Drop, dt: Float) {
        if (!drop.sliding) {
            if (time >= drop.clingUntil) {
                drop.sliding = true
                drop.vy = 0f
            }
            return
        }

        // Heavier drops run faster; none faster than water does on glass.
        val gravity = GRAVITY * density * (drop.r / (BIG_MAX * density))
        drop.vy = min(drop.vy + gravity * dt, MAX_SPEED * density)
        val dy = drop.vy * dt
        drop.y += dy
        drop.x += sin(time * 3f + drop.phase) * WOBBLE * density * dt

        // Now and then it catches on the glass for a moment.
        if (random.nextFloat() < STALL_PER_SECOND * dt) {
            drop.sliding = false
            drop.clingUntil = time + 0.15f + random.nextFloat() * 0.6f
        }

        // What it leaves behind.
        drop.sinceTrail += dy
        if (drop.sinceTrail > TRAIL_SPACING * density) {
            drop.sinceTrail = 0f
            if (random.nextFloat() < 0.7f) {
                val r = (TRAIL_MIN + (TRAIL_MAX - TRAIL_MIN) * random.nextFloat()) * density
                trail.add(
                    Drop(
                        x = drop.x + (random.nextFloat() - 0.5f) * drop.r * 0.6f,
                        y = drop.y - drop.r * 1.5f,
                        r = r,
                        big = false,
                        alpha = 0.9f
                    )
                )
            }
        }
    }

    private val trail = ArrayList<Drop>()

    /** A running drop takes up the droplets it runs into, and grows a little. */
    private fun absorb() {
        if (trail.isNotEmpty()) {
            _drops.addAll(trail)
            trail.clear()
        }
        val runners = _drops.filter { it.big && it.sliding }
        if (runners.isEmpty()) return
        _drops.removeAll { small ->
            if (small.big) return@removeAll false
            val hit = runners.firstOrNull { runner ->
                // Only what lies ahead of it -- not the trail it has just left.
                small.y >= runner.y - runner.r * 0.3f && run {
                    val dx = small.x - runner.x
                    val dy = small.y - runner.y
                    dx * dx + dy * dy < (runner.r + small.r * 0.5f) * (runner.r + small.r * 0.5f)
                }
            }
            if (hit != null) {
                hit.r = min(sqrt(hit.r * hit.r + small.r * small.r * 0.35f), BIG_MAX * 1.4f * density)
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val MAX_STEP = 0.05f
        const val FADE_IN = 0.15f

        const val SMALL_PER_SECOND = 22f
        const val MAX_SMALL = 240
        const val SMALL_MIN = 1.1f
        const val SMALL_MAX = 3.2f

        const val BIG_PER_SECOND = 0.9f
        const val MAX_BIG = 12
        const val BIG_MIN = 4.5f
        const val BIG_MAX = 8.5f
        const val CLING_MIN = 0.6f
        const val CLING_MAX = 4.5f

        const val GRAVITY = 260f
        const val MAX_SPEED = 220f
        const val WOBBLE = 14f
        const val STALL_PER_SECOND = 0.6f

        const val TRAIL_SPACING = 16f
        const val TRAIL_MIN = 0.8f
        const val TRAIL_MAX = 1.8f
    }
}
