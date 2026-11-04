package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeDownloadNamesTest {
    private val t = ThemeTrack(
        7, "ED 4", "Korogaru Iwa, Kimi ni Asa ga Furu", "Kessoku Band", "12", "https://v/x.webm", emptyList(),
        ThemeWarningType.NONE, entryId = 70, version = 2, versionCount = 2, audioUrl = "https://a/x.ogg", showTitle = "Bocchi the Rock!",
    )

    @Test
    fun fileNameNamesShowTagAndSong() {
        assertEquals("Bocchi the Rock! - ED 4 v2 - Korogaru Iwa, Kimi ni Asa ga Furu.webm", ThemeDownloadNames.fileName(t, ThemeFileKind.VIDEO))
        assertEquals("Bocchi the Rock! - ED 4 v2 - Korogaru Iwa, Kimi ni Asa ga Furu.ogg", ThemeDownloadNames.fileName(t, ThemeFileKind.AUDIO))
    }

    @Test
    fun fileNameDropsCharactersFileSystemsRefuse() {
        val odd = t.copy(showTitle = "Re:Zero / Part 2?", songTitle = "A<b>|c*", versionCount = 1)
        assertEquals("Re Zero Part 2 - ED 4 - A b c.webm", ThemeDownloadNames.fileName(odd, ThemeFileKind.VIDEO))
        assertEquals("theme-7.webm", ThemeDownloadNames.fileName(t.copy(showTitle = "", tag = "", songTitle = ""), ThemeFileKind.VIDEO))
    }

    @Test
    fun keyAndUrlPerKind() {
        assertEquals("7:70:AUDIO", ThemeDownloadNames.key(t, ThemeFileKind.AUDIO))
        assertEquals("https://a/x.ogg", ThemeDownloadNames.url(t, ThemeFileKind.AUDIO))
        assertEquals("https://v/x.webm", ThemeDownloadNames.url(t, ThemeFileKind.VIDEO))
        assertNull(ThemeDownloadNames.url(t.copy(audioUrl = null), ThemeFileKind.AUDIO))
    }
}
