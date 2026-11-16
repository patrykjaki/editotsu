package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordProgressTextTest {
    @Test
    fun numberDropsTitleAndLabels() {
        assertEquals("1", DiscordProgressText.number("Ch. 1 - Ryomen Sukuna"))
        assertEquals("1", DiscordProgressText.number("1-Sub & Dub"))
        assertEquals("12.5", DiscordProgressText.number("12.5"))
        assertEquals("3", DiscordProgressText.number("3.0"))
        assertEquals("Special", DiscordProgressText.number(" Special "))
    }

    @Test
    fun progressWithAndWithoutTotal() {
        assertEquals("Chapter 1 of 272", DiscordProgressText.progress("Chapter", "1", 272))
        assertEquals("Episode 3", DiscordProgressText.progress("Episode", "3", null))
        assertEquals("Episode 3", DiscordProgressText.progress("Episode", "3", 0))
    }
}
