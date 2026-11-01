package ani.dantotsu.media.anime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSpoilerShieldTest {
    @Test
    fun `off never shields`() {
        assertFalse(EpisodeSpoilerShield.shields(false, 25f, 3))
    }

    @Test
    fun `episodes past progress are shielded, watched ones are not`() {
        assertFalse(EpisodeSpoilerShield.shields(true, 3f, 3))
        assertTrue(EpisodeSpoilerShield.shields(true, 4f, 3))
        assertTrue(EpisodeSpoilerShield.shields(true, 3.5f, 3))
    }

    @Test
    fun `not on the list shields everything, unknown numbers too`() {
        assertTrue(EpisodeSpoilerShield.shields(true, 1f, null))
        assertTrue(EpisodeSpoilerShield.shields(true, 9999f, 12))
    }
}
