package ani.dantotsu.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoShapeTest {

    @Test
    fun `wide transparent art is a logo`() {
        // text band in the middle third, transparent above and below
        assertTrue(LogoShape.looksLikeLogo(400, 150, true) { _, y -> if (y in 50..100) 255 else 0 })
    }

    @Test
    fun `square transparent logo is accepted`() {
        assertTrue(LogoShape.looksLikeLogo(256, 256, true) { x, _ -> if (x % 2 == 0) 255 else 0 })
    }

    @Test
    fun `opaque scene image is not a logo`() {
        assertFalse(LogoShape.looksLikeLogo(256, 256, false) { _, _ -> 255 })
        assertFalse(LogoShape.looksLikeLogo(256, 256, true) { _, _ -> 255 })
    }

    @Test
    fun `tall images and empty sizes are not logos`() {
        assertFalse(LogoShape.looksLikeLogo(100, 300, true) { _, _ -> 0 })
        assertFalse(LogoShape.looksLikeLogo(0, 100, true) { _, _ -> 0 })
    }
}
