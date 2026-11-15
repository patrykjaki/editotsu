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
    fun `meta line joins format, years and episodes`() {
        assertEquals("TV · 2023 · 12 episodes", watchOrderMeta("TV", "Jan 6 – Mar 24, 2023", "12 episodes"))
        assertEquals("TV · 2026", watchOrderMeta("TV", "Oct 9, 2026 – ?", null))
        assertEquals("TV · 2025–2026", watchOrderMeta("TV", "Oct 4, 2025 – Mar 21, 2026", null))
        assertEquals("TV", watchOrderMeta("TV", "Period unknown", null))
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
    fun `coming date is as precise as AniList knows it`() {
        val us = java.util.Locale.US
        assertEquals("Jul 8, 2027", watchOrderComingDate(2027, 7, 8, us))
        assertEquals("Jul 2027", watchOrderComingDate(2027, 7, null, us))
        assertEquals("2027", watchOrderComingDate(2027, null, null, us))
        assertEquals("2027", watchOrderComingDate(2027, 2, 31, us))
        assertEquals(null, watchOrderComingDate(null, 7, 8, us))
    }

    @Test
    fun `completed and rewatching count as seen`() {
        assertEquals(true, watchOrderCompleted("COMPLETED"))
        assertEquals(true, watchOrderCompleted("REPEATING"))
        assertEquals(false, watchOrderCompleted("CURRENT"))
        assertEquals(false, watchOrderCompleted(null))
    }

    @Test
    fun `the story is what prequel and sequel links reach`() {
        // 1 -> 2 -> 3 sequels, 4 is a side story of 2 (no link here), 5 links to 3 but is not in the order
        val links = mapOf(1 to listOf(2), 2 to listOf(1, 3), 3 to listOf(2, 5), 4 to emptyList())
        assertEquals(setOf(1, 2, 3), watchOrderStory(3, links))
        assertEquals(setOf(4), watchOrderStory(4, links))
    }

    @Test
    fun `only main entries take a number`() {
        assertEquals(listOf(1, null, 2, null, 3), watchOrderNumbers(listOf(false, true, false, true, false)))
    }
}
