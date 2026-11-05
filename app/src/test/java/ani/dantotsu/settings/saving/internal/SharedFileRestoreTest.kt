package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFileRestoreTest {

    @Test
    fun emptyNovelReaderGroupDoesNotReplaceReaderSettings() {
        val backup = mapOf(
            "Reader" to mapOf("Direction" to 2, "TrueColors" to true),
            "NovelReader" to emptyMap<String, Any>(),
            "Player" to mapOf("DefaultSpeed" to 5),
        )
        val merged = PreferencePackager.mergeSharedFiles(backup)
        assertEquals(setOf("Reader", "Player"), merged.keys)
        assertEquals(mapOf("Direction" to 2, "TrueColors" to true), merged["Reader"])
    }

    @Test
    fun novelReaderValuesAreKeptAndReaderWinsOnClash() {
        val backup = mapOf(
            "Reader" to mapOf("Direction" to 2),
            "NovelReader" to mapOf("Direction" to 0, "NovelFontSize" to 18),
        )
        assertEquals(
            mapOf("Direction" to 2, "NovelFontSize" to 18),
            PreferencePackager.mergeSharedFiles(backup)["Reader"],
        )
    }

    @Test
    fun backupWithoutNovelReaderIsUnchanged() {
        val backup = mapOf("Reader" to mapOf("Direction" to 1))
        assertEquals(backup, PreferencePackager.mergeSharedFiles(backup))
    }
}
