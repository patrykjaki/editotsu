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
        assertEquals(5f, snapSkipSliderValue(7))
        assertEquals(85f, snapSkipSliderValue(83))
        assertEquals(85f, snapSkipSliderValue(86))
    }

    @Test
    fun `on-grid and default values pass through`() {
        assertEquals(85f, snapSkipSliderValue(85))
        assertEquals(120f, snapSkipSliderValue(120))
        assertEquals(0f, snapSkipSliderValue(0))
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
            assertEquals(0f, v % 5f)
            assertEquals(true, v in 0f..120f)
        }
    }
}
