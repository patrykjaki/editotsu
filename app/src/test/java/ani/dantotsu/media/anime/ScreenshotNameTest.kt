package ani.dantotsu.media.anime

import ani.dantotsu.media.anime.player.ScreenshotName
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotNameTest {
    @Test
    fun episodeNameHasTitleEpisodeAndTime() {
        assertEquals(
            "Kusuriya no Hitorigoto 2nd Season E5 12m34s.jpg",
            ScreenshotName.episode("Kusuriya no Hitorigoto 2nd Season", "5", 754_000L),
        )
    }

    @Test
    fun themeNameDropsSeparatorAndUnsafeChars() {
        assertEquals(
            "Re Zero OP1 Redo 1h02m03s.jpg",
            ScreenshotName.theme("Re:Zero", "OP1 · Redo", 3_723_000L),
        )
    }

    @Test
    fun keepsNonLatinTitlesAndFallsBack() {
        assertEquals("薬屋のひとりごと E1 0m00s.jpg", ScreenshotName.episode("薬屋のひとりごと", "1", 0L))
        assertEquals("0m05s.jpg", ScreenshotName.episode(null, " ", 5_000L))
    }
}
