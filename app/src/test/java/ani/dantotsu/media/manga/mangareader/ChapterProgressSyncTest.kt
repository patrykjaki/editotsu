package ani.dantotsu.media.manga.mangareader

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tracker progress gate shared by the chapter buttons and the continuous-mode boundary. */
class ChapterProgressSyncTest {

    private fun sync(
        loggedIn: Boolean = true,
        askIndividually: Boolean = false,
        incognito: Boolean = false,
        saveProgress: Boolean = true,
        isAdult: Boolean = false,
        updateForAdult: Boolean = false,
    ) = chapterProgressSync(loggedIn, askIndividually, incognito, saveProgress, isAdult, updateForAdult)

    @Test
    fun `logged out users get no request and no dialog`() {
        assertEquals(ChapterProgressSync.NONE, sync(loggedIn = false))
        assertEquals(ChapterProgressSync.NONE, sync(loggedIn = false, askIndividually = true))
    }

    @Test
    fun `ask individually shows the dialog`() {
        assertEquals(ChapterProgressSync.ASK, sync(askIndividually = true))
        assertEquals(ChapterProgressSync.ASK, sync(askIndividually = true, saveProgress = false))
    }

    @Test
    fun `incognito never asks or updates`() {
        assertEquals(ChapterProgressSync.NONE, sync(incognito = true))
        assertEquals(ChapterProgressSync.NONE, sync(incognito = true, askIndividually = true))
    }

    @Test
    fun `saved choice decides without asking`() {
        assertEquals(ChapterProgressSync.UPDATE, sync())
        assertEquals(ChapterProgressSync.NONE, sync(saveProgress = false))
    }

    @Test
    fun `adult titles follow the adult update setting`() {
        assertEquals(ChapterProgressSync.NONE, sync(isAdult = true, updateForAdult = false))
        assertEquals(ChapterProgressSync.UPDATE, sync(isAdult = true, updateForAdult = true))
    }
}
