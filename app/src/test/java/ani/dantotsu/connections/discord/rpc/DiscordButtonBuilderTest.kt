package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordButtonBuilderTest {

    private val app = DiscordPresenceButton("Get Editotsu", "https://github.com/patrykjaki/editotsu/releases")

    @Test
    fun `anilist id gives the show button and the app button`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 123, kind = "anime", malId = 456)
        assertEquals(listOf(DiscordPresenceButton("View Anime", "https://anilist.co/anime/123/"), app), buttons)
    }

    @Test
    fun `without an anilist id the show button opens MAL (no slash zero url)`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 0, kind = "anime", malId = 456)
        assertEquals(listOf(DiscordPresenceButton("View Anime", "https://myanimelist.net/anime/456"), app), buttons)
    }

    @Test
    fun `invalid ids leave only the app button`() {
        assertEquals(listOf(app), DiscordButtonBuilder.mediaButtons(anilistId = 0, kind = "anime", malId = null))
        assertEquals(listOf(app), DiscordButtonBuilder.mediaButtons(anilistId = -1, kind = "anime", malId = 0))
    }

    @Test
    fun `manga kind uses manga label and path`() {
        val buttons = DiscordButtonBuilder.mediaButtons(anilistId = 99, kind = "manga", malId = 77)
        assertEquals(listOf(DiscordPresenceButton("View Manga", "https://anilist.co/manga/99/"), app), buttons)
    }
}
