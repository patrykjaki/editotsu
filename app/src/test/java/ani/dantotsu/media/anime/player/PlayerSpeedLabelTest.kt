package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSpeedLabelTest {
    @Test fun noTrailingZeros() {
        assertEquals("2x", PlayerDisplayFormat.speed(2f))
        assertEquals("1x", PlayerDisplayFormat.speed(1f))
        assertEquals("1.5x", PlayerDisplayFormat.speed(1.5f))
        assertEquals("1.25x", PlayerDisplayFormat.speed(1.25f))
        assertEquals("0.33x", PlayerDisplayFormat.speed(0.33f))
        assertEquals("10x", PlayerDisplayFormat.speed(10f))
    }
}
