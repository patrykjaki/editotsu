package ani.dantotsu.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchOrderMetaTest {

    @Test
    fun `episode count is read and unknown counts are dropped`() {
        assertEquals(12, watchOrderEpisodeCount("12ep × 24min."))
        assertEquals(1, watchOrderEpisodeCount("1 ep"))
        assertEquals(null, watchOrderEpisodeCount("0ep × ? min."))
        assertEquals(null, watchOrderEpisodeCount(""))
        assertEquals(null, watchOrderEpisodeCount("? min."))
    }

    @Test
    fun `meta line joins what is known and drops an unknown end date`() {
        assertEquals("TV · Jan 6 – Mar 24, 2023 · 12 episodes", watchOrderMeta("TV", "Jan 6 – Mar 24, 2023", "12 episodes"))
        assertEquals("TV · Oct 9, 2026", watchOrderMeta("TV", "Oct 9, 2026 – ?", null))
        assertEquals("Movie", watchOrderMeta("Movie", "?", null))
        assertEquals("", watchOrderMeta("", "", null))
    }
}
