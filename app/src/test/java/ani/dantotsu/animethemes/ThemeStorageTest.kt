package ani.dantotsu.animethemes

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeStorageTest {

    private fun track(id: Int, audio: String?) =
        ThemeTrack(id, "OP 1", "s", "a", null, "https://v/$id.webm", emptyList(), ThemeWarningType.NONE, audioUrl = audio)

    @Test
    fun cacheDropsOldestUntilUnderLimit() {
        val timestamps = mapOf("a" to 3L, "b" to 1L, "c" to 2L)
        val sizes = mapOf("a" to 40, "b" to 40, "c" to 40)
        assertEquals(listOf("b", "c"), AnimeThemesRepository.oldestOverLimit(timestamps, sizes, total = 130, limit = 50))
        assertEquals(emptyList<String>(), AnimeThemesRepository.oldestOverLimit(timestamps, sizes, total = 50, limit = 50))
    }

    @Test
    fun cacheCapIsSomeMegabytes() {
        assertEquals(true, AnimeThemesRepository.MAX_CACHE_BYTES >= 2 * 1024 * 1024L)
    }

    @Test
    fun folderFilesBecomeIndexRecords() {
        val index = ThemeDownloadIndex.fromFolder(
            mapOf("Show - OP 1.webm" to "content://1", "Show - OP 1.ogg" to "content://2", "notes.txt" to "content://3")
        )
        assertEquals(setOf("file:Show - OP 1.webm", "file:Show - OP 1.ogg"), index.keys)
        assertEquals(-1L, index.getValue("file:Show - OP 1.webm").downloadId)
        assertEquals("content://1", index.getValue("file:Show - OP 1.webm").uri)
    }

    @Test
    fun downloadAllCountsThemesWithoutAudio() {
        val tracks = listOf(track(1, "https://a/1.ogg"), track(2, null), track(3, null))
        assertEquals(2, ThemeDownloadNames.missingFiles(tracks, ThemeFileKind.AUDIO))
        assertEquals(0, ThemeDownloadNames.missingFiles(tracks, ThemeFileKind.VIDEO))
    }
}
