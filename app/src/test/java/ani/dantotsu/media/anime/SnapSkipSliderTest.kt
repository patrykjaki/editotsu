package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LANE-A P0: typed skip times snap to the dialog slider grid instead of
 * throwing IllegalArgumentException on assignment.
 */
class SnapSkipSliderTest {

    @Test
    fun `off-grid values snap to nearest step`() {
        // Explicit 5 s grid (the dialog's grid before upstream 55501e6e).
        assertEquals(5f, snapSkipSliderValue(7, step = 5f))
        assertEquals(85f, snapSkipSliderValue(83, step = 5f))
        assertEquals(85f, snapSkipSliderValue(86, step = 5f))
    }

    @Test
    fun `on-grid and default values pass through`() {
        assertEquals(85f, snapSkipSliderValue(85))
        assertEquals(120f, snapSkipSliderValue(120))
        assertEquals(0f, snapSkipSliderValue(0))
    }

    @Test
    fun `default 1 s grid keeps every whole second`() {
        assertEquals(7f, snapSkipSliderValue(7))
        assertEquals(83f, snapSkipSliderValue(83))
        assertEquals(86f, snapSkipSliderValue(86))
    }

    @Test
    fun `out-of-range values clamp`() {
        assertEquals(120f, snapSkipSliderValue(122))
        assertEquals(120f, snapSkipSliderValue(1000))
        assertEquals(0f, snapSkipSliderValue(-5))
    }

    @Test
    fun `every int 0 to 200 yields a valid grid value`() {
        for (i in 0..200) {
            val v = snapSkipSliderValue(i)
            assertEquals(0f, v % 1f)
            assertEquals(true, v in 0f..120f)
        }
    }
}
