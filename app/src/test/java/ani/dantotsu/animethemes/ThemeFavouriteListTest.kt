package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFavouriteListTest {
    private fun fav(id: Int, version: Int = 1) = FavouriteTheme(
        100, "Show", ThemeTrack(id, "OP $id", "s", "a", "1-12", "https://v/$id.webm", emptyList(), ThemeWarningType.NONE, version = version),
    )

    @Test
    fun toggleAddsOnTopAndRemovesByTheme() {
        var list = ThemeFavouriteList.toggle(emptyList(), fav(1))
        list = ThemeFavouriteList.toggle(list, fav(2))
        assertEquals(listOf(2, 1), list.map { it.track.themeId })
        // Same theme, other version: the heart is per theme, so it is removed.
        list = ThemeFavouriteList.toggle(list, fav(1, version = 2))
        assertEquals(listOf(2), list.map { it.track.themeId })
        assertTrue(ThemeFavouriteList.contains(list, 2))
        assertFalse(ThemeFavouriteList.contains(ThemeFavouriteList.remove(list, 2), 2))
    }

    @Test
    fun encodeDecodeRoundTripAndBadInput() {
        val list = listOf(fav(1), fav(2))
        assertEquals(list, ThemeFavouriteList.decode(ThemeFavouriteList.encode(list)))
        assertEquals(emptyList<FavouriteTheme>(), ThemeFavouriteList.decode("not json"))
        assertEquals(emptyList<FavouriteTheme>(), ThemeFavouriteList.decode(""))
        assertEquals(emptyList<FavouriteTheme>(), ThemeFavouriteList.decode("[]"))
    }

    @Test
    fun oldTracksWithoutNewFieldsStillDecode() {
        val json = """[{"mediaId":1,"animeTitle":"A","track":{"themeId":3,"tag":"OP 1","songTitle":"s","artist":"a","episodes":null,"url":"u","fallbackUrls":[],"warning":"NONE"}}]"""
        val decoded = ThemeFavouriteList.decode(json)
        assertEquals(3, decoded.single().track.themeId)
        assertEquals(null, decoded.single().track.audioUrl)
    }
}
