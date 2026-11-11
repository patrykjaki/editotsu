package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChangeMemoryTest {

    @Test
    fun differentSourceClearsRememberedRelease() {
        assertTrue(RememberedSelectionPolicy.clearsMemoryOnSourceChange(2, 3))
        assertTrue(RememberedSelectionPolicy.clearsMemoryOnSourceChange(3, 0))
    }

    @Test
    fun sameSourceKeepsRememberedRelease() {
        // tapping the selected source again in the dropdown
        assertFalse(RememberedSelectionPolicy.clearsMemoryOnSourceChange(2, 2))
    }

    @Test
    fun languageChangeKeepsRememberedRelease() {
        // the language dropdown calls onSourceChange with the current source index
        val current = 4
        assertFalse(RememberedSelectionPolicy.clearsMemoryOnSourceChange(current, current))
    }
}
