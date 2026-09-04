package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordPresenceMapperTest {

    @Test
    fun `anime presence uses WATCHING activity type`() {
        val p = DiscordPresenceMapper.animePresence(
            name = "Frieren",
            details = "Episode 1",
            state = "Episode : 1/28",
            largeImageKey = null,
            largeImageText = "Frieren",
            smallImageKey = DiscordPresenceMapper.ANIME_SMALL_IMAGE_KEY,
            smallImageText = DiscordPresenceMapper.ANIME_SMALL_TEXT,
            startTimestamp = null,
            endTimestamp = null,
            buttons = emptyList(),
        )
        assertEquals(DiscordActivityType.WATCHING, p.type)
        assertEquals("Frieren", p.name)
    }

    @Test
    fun `manga presence uses WATCHING activity type`() {
        val p = DiscordPresenceMapper.mangaPresence(
            name = "Berserk",
            details = "Chapter 1",
            state = "Chapter : 1/??",
            largeImageKey = null,
            largeImageText = "Berserk",
            smallImageKey = DiscordPresenceMapper.MANGA_SMALL_IMAGE_KEY,
            smallImageText = DiscordPresenceMapper.MANGA_SMALL_TEXT,
            buttons = emptyList(),
        )
        assertEquals(DiscordActivityType.WATCHING, p.type)
    }

    @Test
    fun `anime uses anime semantic small image, manga uses manga semantic small image`() {
        val a = DiscordPresenceMapper.animePresence(
            "A", "d", "s", null, "A",
            DiscordPresenceMapper.ANIME_SMALL_IMAGE_KEY,
            DiscordPresenceMapper.ANIME_SMALL_TEXT, null, null, emptyList(),
        )
        assertEquals("anime", a.smallImageKey)
        assertEquals("Watching on Editotsu", a.smallImageText)

        val m = DiscordPresenceMapper.mangaPresence(
            "M", "d", "s", null, "M",
            DiscordPresenceMapper.MANGA_SMALL_IMAGE_KEY,
            DiscordPresenceMapper.MANGA_SMALL_TEXT, emptyList(),
        )
        assertEquals("manga", m.smallImageKey)
        assertEquals("Reading on Editotsu", m.smallImageText)
    }

    @Test
    fun `anime never uses CUSTOM_STATUS`() {
        val a = DiscordPresenceMapper.animePresence(
            "A", "d", "s", null, "A",
            DiscordPresenceMapper.ANIME_SMALL_IMAGE_KEY,
            DiscordPresenceMapper.ANIME_SMALL_TEXT, null, null, emptyList(),
        )
        assertNotEquals(DiscordActivityType.CUSTOM_STATUS, a.type)
        assertEquals(DiscordActivityType.WATCHING, a.type)
    }

    @Test
    fun `anime paused uses paused hover text`() {
        val p = DiscordPresenceMapper.animePresence(
            "A", "d", "s", null, "A",
            DiscordPresenceMapper.ANIME_PAUSED_SMALL_IMAGE_KEY,
            DiscordPresenceMapper.ANIME_PAUSED_SMALL_TEXT, null, null, emptyList(),
        )
        assertEquals("anime_paused", p.smallImageKey)
        assertEquals("Paused on Editotsu", p.smallImageText)
    }

    @Test
    fun `manga never uses CUSTOM_STATUS`() {
        val m = DiscordPresenceMapper.mangaPresence(
            "M", "d", "s", null, "M",
            DiscordPresenceMapper.MANGA_SMALL_IMAGE_KEY,
            DiscordPresenceMapper.MANGA_SMALL_TEXT, emptyList(),
        )
        assertNotEquals(DiscordActivityType.CUSTOM_STATUS, m.type)
        assertEquals(DiscordActivityType.WATCHING, m.type)
    }
}
