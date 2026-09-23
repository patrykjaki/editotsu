package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordCoverResolverTest {

    @Test
    fun `null and blank resolve to omitted null`() {
        assertNull(DiscordCoverResolver.resolveLargeImage(null))
        assertNull(DiscordCoverResolver.resolveLargeImage(""))
        assertNull(DiscordCoverResolver.resolveLargeImage("   "))
    }

    @Test
    fun `https cover url is sent verbatim`() {
        val url = "https://s4.anilist.co/s4/cover/16498-abc.jpg"
        assertEquals(url, DiscordCoverResolver.resolveLargeImage(url))
    }

    @Test
    fun `http cover url is preserved verbatim (no scheme upgrade)`() {
        val url = "http://s4.anilist.co/s4/cover/16498-abc.jpg"
        assertEquals(url, DiscordCoverResolver.resolveLargeImage(url))
    }

    @Test
    fun `300-char external url is preserved completely`() {
        val url = "https://cdn.example.com/" + "a".repeat(300 - "https://cdn.example.com/".length)
        assertEquals(300, url.length)
        assertEquals(url, DiscordCoverResolver.resolveLargeImage(url))
    }

    @Test
    fun `301-char external url is omitted rather than truncated`() {
        val url = "https://cdn.example.com/" + "a".repeat(301 - "https://cdn.example.com/".length)
        assertEquals(301, url.length)
        assertNull(DiscordCoverResolver.resolveLargeImage(url))
    }

    @Test
    fun `malformed and non http schemes are omitted as null (truthful fallback)`() {
        assertNull(DiscordCoverResolver.resolveLargeImage("not a url"))
        assertNull(DiscordCoverResolver.resolveLargeImage("ftp://example.com/x.png"))
        assertNull(DiscordCoverResolver.resolveLargeImage("file:///sdcard/cover.jpg"))
        assertNull(DiscordCoverResolver.resolveLargeImage("https:///no-host"))
        assertNull(DiscordCoverResolver.resolveLargeImage("https://nohost"))
    }

    @Test
    fun `relative and hostless urls are omitted`() {
        assertNull(DiscordCoverResolver.resolveLargeImage("/cover/abc.jpg"))
        assertNull(DiscordCoverResolver.resolveLargeImage("https:///"))
    }
}
