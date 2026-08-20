package app.fabula.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Picking the decode reduction for the cover embedded in the media metadata.
 *
 * Worth pinning because both ends fail quietly: too little reduction pushes a
 * large bitmap through Binder to the system session, too much leaves a blurry
 * square on the dashboard, and an off-by-one power of two is invisible in code
 * review.
 */
class CoverArtTest {

    @Test
    fun `leaves an image that is already small enough alone`() {
        assertEquals(1, sampleSizeFor(640, 640, 640))
        assertEquals(1, sampleSizeFor(300, 200, 640))
    }

    /** Reduction is by powers of two, and the result must land at or under the cap. */
    @Test
    fun `halves until the longest edge fits`() {
        assertEquals(2, sampleSizeFor(1280, 1280, 640))
        assertEquals(4, sampleSizeFor(1600, 1600, 640))
        assertEquals(8, sampleSizeFor(3000, 2000, 640))
    }

    /**
     * Exactly twice the cap needs one halving, not two. A hair past it needs
     * two -- integer division is what decides here, so 1281/2 is 640 and still
     * counts as fitting.
     */
    @Test
    fun `does not over-shrink at the boundary`() {
        assertEquals(2, sampleSizeFor(1280, 640, 640))
        assertEquals(2, sampleSizeFor(1281, 640, 640))
        assertEquals(4, sampleSizeFor(1282, 640, 640))
    }

    /** The longest edge decides, whichever way round the cover is. */
    @Test
    fun `measures the longest edge, not the width`() {
        assertEquals(4, sampleSizeFor(300, 2000, 640))
        assertEquals(4, sampleSizeFor(2000, 300, 640))
    }

    /**
     * A failed bounds decode reports zero. Shrinking on a guess would be worse
     * than not shrinking, so it declines to.
     */
    @Test
    fun `declines to guess when the bounds are unknown`() {
        assertEquals(1, sampleSizeFor(0, 0, 640))
        assertEquals(1, sampleSizeFor(-1, 500, 640))
        assertEquals(1, sampleSizeFor(500, 0, 640))
    }

    /** A nonsensical cap must not spin the halving loop forever. */
    @Test
    fun `tolerates a zero or negative cap`() {
        assertEquals(1, sampleSizeFor(2000, 2000, 0))
        assertEquals(1, sampleSizeFor(2000, 2000, -10))
    }
}
