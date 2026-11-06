package app.fabula.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The rain on the full player. Decorative -- but it runs every frame, so the
 * limits that keep it cheap are worth pinning down.
 */
class RainSimulationTest {

    private fun rain(seed: Int = 7) = RainSimulation(density = 2.5f, random = Random(seed)).apply {
        resize(1080f, 2340f)
    }

    private fun RainSimulation.run(seconds: Float, fps: Int = 60) {
        repeat((seconds * fps).toInt()) { step(1f / fps) }
    }

    @Test
    fun `nothing falls before the size is known`() {
        val rain = RainSimulation(density = 2.5f, random = Random(1))

        rain.step(0.016f)

        assertTrue(rain.drops.isEmpty())
    }

    @Test
    fun `droplets gather on the glass`() {
        val rain = rain()

        rain.run(seconds = 2f)

        assertTrue(rain.drops.count { !it.big } > 20)
    }

    /** What keeps it cheap: however long it rains, the glass never holds more than this. */
    @Test
    fun `the number of drops stays bounded`() {
        val rain = rain()

        repeat(20) {
            rain.run(seconds = 3f)
            assertTrue(rain.drops.count { !it.big } <= RainSimulation.MAX_SMALL)
            assertTrue(rain.drops.count { it.big } <= RainSimulation.MAX_BIG)
        }
    }

    @Test
    fun `big drops run down and off the glass`() {
        val rain = rain()

        rain.run(seconds = 30f)

        assertTrue(rain.drops.any { it.big && it.sliding })
        assertTrue(rain.drops.all { it.y - it.r <= 2340f })
    }

    /** Back from the background, a minute's gap is one short step, not a minute of rain at once. */
    @Test
    fun `a long gap counts as one short step`() {
        val rain = rain()

        rain.step(60f)

        val spawned = rain.drops.size
        assertTrue(spawned <= (RainSimulation.SMALL_PER_SECOND * RainSimulation.MAX_STEP).toInt() + 2)
    }

    @Test
    fun `drops fade in rather than pop up`() {
        val rain = rain()

        rain.run(seconds = 0.1f)
        val fresh = rain.drops.last()

        assertTrue(fresh.alpha < 1f)
        rain.run(seconds = 0.5f)
        // Unless a running drop has taken it up in the meantime.
        if (fresh in rain.drops) assertEquals(1f, fresh.alpha, 0.001f)
    }
}
