package ani.dantotsu.animethemes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ThemeRadioTest {
    private fun video(url: String) = ThemeVideoUiModel(1, url, 1080, true, "BD", "NONE", null, false, "")
    private fun theme(id: Int, type: String) = ThemeUiModel(
        id, type, 1, "$type 1", "song $id", "artist",
        listOf(ThemeVersionUiModel(id * 10, 1, "1-12", false, false, listOf(video("https://v/$id.webm")))),
    )

    private fun track(id: Int, ending: Boolean) =
        ThemeTrack(id, if (ending) "ED 1" else "OP 1", "s", "a", null, "u$id", emptyList(), ThemeWarningType.NONE, isEnding = ending)

    @Test
    fun kindsFilter() {
        assertTrue(ThemeKinds.BOTH.accepts(true) && ThemeKinds.BOTH.accepts(false))
        assertTrue(ThemeKinds.OPENINGS.accepts(false))
        assertFalse(ThemeKinds.OPENINGS.accepts(true))
        assertTrue(ThemeKinds.ENDINGS.accepts(true))
        assertEquals(ThemeKinds.BOTH, ThemeKinds.fromInt(9))
    }

    @Test
    fun orderIsAShuffleOfDistinctShows() {
        val shows = (1..20).map { RadioShow(it, null, "s$it") } + RadioShow(1, null, "dup")
        val order = ThemeRadio.order(shows, Random(7))
        assertEquals(20, order.size)
        assertEquals((1..20).toSet(), order.map { it.mediaId }.toSet())
        assertEquals(order, ThemeRadio.order(shows, Random(7)))
    }

    @Test
    fun pickRespectsKinds() {
        val data = AnimeThemesData(1, "s", listOf(theme(1, "OP")), listOf(theme(2, "ED")))
        val choices = ThemeQueue.choices(data)
        repeat(10) { assertEquals(2, ThemeRadio.pick(choices, ThemeKinds.ENDINGS, Random(it))?.themeId) }
        assertNull(ThemeRadio.pick(choices.filter { !it.isEnding }, ThemeKinds.ENDINGS, Random(1)))
    }

    @Test
    fun favouritesQueueFiltersAndKeepsOrder() {
        val favs = listOf(FavouriteTheme(1, "a", track(1, false)), FavouriteTheme(2, "b", track(2, true)), FavouriteTheme(3, "c", track(3, false)))
        assertEquals(listOf(1, 3), ThemeRadio.favouritesQueue(favs, ThemeKinds.OPENINGS, false, Random(1)).map { it.themeId })
        assertEquals(setOf(1, 2, 3), ThemeRadio.favouritesQueue(favs, ThemeKinds.BOTH, true, Random(1)).map { it.themeId }.toSet())
    }

    @Test
    fun sessionSkipsShowsWithoutThemesAndTagsTheShow() = runBlocking {
        val shows = listOf(RadioShow(1, null, "One"), RadioShow(2, null, "Two"))
        ThemeRadioSession.start(shows, ThemeKinds.BOTH, ThemePlaybackPreferences(), seed = 3)
        val found = mutableListOf<ThemeTrack>()
        repeat(3) {
            ThemeRadioSession.next { show ->
                if (show.mediaId == 1) null else AnimeThemesData(2, "two", listOf(theme(5, "OP")), emptyList())
            }?.let { found += it }
        }
        assertEquals(1, found.size)
        assertEquals(2, found[0].mediaId)
        assertEquals("Two", found[0].showTitle)
        assertFalse(ThemeRadioSession.active)
    }
}
