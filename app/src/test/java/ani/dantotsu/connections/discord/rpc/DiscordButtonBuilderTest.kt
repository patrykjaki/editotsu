package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordButtonBuilderTest {

    @Test
    fun `valid anilist id only yields a single AniList button`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 123, kind = "anime", malId = null)
        assertEquals(1, buttons.size)
        assertEquals("View on AniList", buttons[0].label)
        assertEquals("https://anilist.co/anime/123/", buttons[0].url)
    }

    @Test
    fun `valid anilist and mal ids yield both buttons`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 123, kind = "anime", malId = 456)
        assertEquals(2, buttons.size)
        assertEquals("View on AniList", buttons[0].label)
        assertEquals("https://anilist.co/anime/123/", buttons[0].url)
        assertEquals("View on MyAnimeList", buttons[1].label)
        assertEquals("https://myanimelist.net/anime/456", buttons[1].url)
    }

    @Test
    fun `invalid anilist id with valid mal id yields MAL only (no slash zero url)`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 0, kind = "anime", malId = 456)
        assertEquals(1, buttons.size)
        assertEquals("View on MyAnimeList", buttons[0].label)
        assertEquals("https://myanimelist.net/anime/456", buttons[0].url)
    }

    @Test
    fun `invalid ids yield no buttons (no invalid slash zero url)`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 0, kind = "anime", malId = null)
        assertEquals(0, buttons.size)
        val buttons2 = DiscordButtonBuilder.mediaButtons(anilistId = -1, kind = "anime", malId = 0)
        assertEquals(0, buttons2.size)
    }

    @Test
    fun `manga kind uses manga path segments`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 99, kind = "manga", malId = 77)
        assertEquals("https://anilist.co/manga/99/", buttons[0].url)
        assertEquals("https://myanimelist.net/manga/77", buttons[1].url)
    }
}
