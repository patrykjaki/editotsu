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

    @Test
    fun `relation label comes from the tracker's relation only`() {
        assertEquals("Side story", watchOrderRelation("SIDE_STORY"))
        assertEquals("Prequel", watchOrderRelation("PREQUEL"))
        assertEquals("", watchOrderRelation(null))
        assertEquals("", watchOrderRelation(" "))
    }

    @Test
    fun `rows are earlier, current or later against the open title`() {
        assertEquals(WatchOrderPlace.EARLIER, watchOrderPlace(0, 2))
        assertEquals(WatchOrderPlace.CURRENT, watchOrderPlace(2, 2))
        assertEquals(WatchOrderPlace.LATER, watchOrderPlace(3, 2))
        assertEquals(WatchOrderPlace.LATER, watchOrderPlace(0, -1))
    }

    @Test
    fun `extras are optional, seasons and films are not`() {
        assertEquals(false, watchOrderOptional("TV", ""))
        assertEquals(false, watchOrderOptional("Movie", "Sequel"))
        assertEquals(true, watchOrderOptional("OVA", ""))
        assertEquals(true, watchOrderOptional("Special", "Prequel"))
        assertEquals(true, watchOrderOptional("TV", "Side story"))
        assertEquals(true, watchOrderOptional("Movie", "Summary"))
    }

    @Test
    fun `only main entries take a number`() {
        assertEquals(listOf(1, null, 2, null, 3), watchOrderNumbers(listOf(false, true, false, true, false)))
    }
}
