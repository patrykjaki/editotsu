package ani.dantotsu.connections.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSubtitleLanguagesTest {

    @Test
    fun defaultEnglishNameReadsAsCode() {
        // The pref default is setOf("English"); the picker and Wyzie use codes.
        assertEquals(setOf("en"), OnlineSubtitleLanguages.codes(setOf("English")))
    }

    @Test
    fun codesAndNamesMixAndUnknownPassesThrough() {
        assertEquals(setOf("ja", "en", "xx"), OnlineSubtitleLanguages.codes(setOf("ja", "english", "xx")))
    }

    @Test
    fun everyOfferedCodeHasAnEnglishName() {
        for (code in OnlineSubtitleLanguages.CODES) {
            val name = OnlineSubtitleLanguages.englishName(code)
            assertEquals(code, OnlineSubtitleLanguages.codes(setOf(name)).single())
        }
    }
}
