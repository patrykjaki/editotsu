package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PlayerEpisodeLabelTest(private val input: String, private val expected: String) {
    @Test fun formatsDisplayWithoutChangingIdentity() {
        assertEquals(expected, PlayerDisplayFormat.episodeNumber(input))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} -> {1}")
        fun cases() = listOf(
            arrayOf("1", "1"), arrayOf("1.0", "1"), arrayOf("2.0", "2"),
            arrayOf("13.0", "13"), arrayOf("1.5", "1.5"), arrayOf("12.5", "12.5"),
            arrayOf("1.00", "1"), arrayOf("0.0", "0"),
            arrayOf("Special 1.0", "Special 1.0"), arrayOf("OVA", "OVA"),
            arrayOf("1.0a", "1.0a"), arrayOf("99999999999999999999.0", "99999999999999999999")
        )
    }
}
