package ani.dantotsu.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 (upstream e954d9f1): mask-until-tapped contract.
 */
class SpoilerMaskTest {

    @Test
    fun `pref on masks spoiler tags`() {
        assertFalse(spoilerChipRevealed(hideSpoilerTags = true, isSpoiler = true))
    }

    @Test
    fun `pref on reveals non-spoiler tags`() {
        assertTrue(spoilerChipRevealed(hideSpoilerTags = true, isSpoiler = false))
    }

    @Test
    fun `pref off reveals everything`() {
        assertTrue(spoilerChipRevealed(hideSpoilerTags = false, isSpoiler = true))
        assertTrue(spoilerChipRevealed(hideSpoilerTags = false, isSpoiler = false))
    }
}
