package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Player/cast episode titles: strip the episode label, keep numbers inside the title. */
class EpisodeTitleCleanupTest {
    private fun clean(t: String) = MediaNameAdapter.removeEpisodeNumberCompletely(t)

    @Test
    fun `numbers inside a title survive`() {
        // Seen in the player: "The Fall of Shiganshina ()".
        assertEquals("The Fall of Shiganshina (1)", clean("The Fall of Shiganshina (1)"))
        assertEquals("To You, in 2000 Years", clean("To You, in 2000 Years"))
        assertEquals("Phase 2", clean("Phase 2"))
    }

    @Test
    fun `episode labels are removed`() {
        assertEquals("The Beginning", clean("Episode 1 The Beginning"))
        assertEquals("Title", clean("Ep. 12 Title"))
    }

    @Test
    fun `a leading number is removed`() {
        assertEquals("Title", clean("12. Title"))
        assertEquals("Title", clean("12 - Title"))
        assertEquals("Title", clean("3: Title"))
        assertEquals("", clean("7"))
    }
}
