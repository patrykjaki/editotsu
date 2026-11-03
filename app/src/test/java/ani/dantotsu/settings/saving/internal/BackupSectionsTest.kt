package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSectionsTest {

    @Test
    fun settingsAreAlwaysIncluded() {
        val locations = BackupSections.locationsFor(emptySet())
        assertEquals(
            listOf(Location.General, Location.UI, Location.Player, Location.Reader, Location.NovelReader),
            locations
        )
    }

    @Test
    fun sectionsAddTheirLocations() {
        val all = BackupSections.locationsFor(BackupSection.entries.toSet())
        assertTrue(Location.ExtensionSettings in all)
        assertTrue(Location.Irrelevant in all)
        assertTrue(Location.Protected in all)
        assertFalse(Location.AnimeDownloads in all)
    }

    @Test
    fun historyKeepsProgressAndPickerMemory() {
        assertTrue(BackupSections.keepHistoryKey("21_5"))
        assertTrue(BackupSections.keepHistoryKey("21_5_max"))
        assertTrue(BackupSections.keepHistoryKey("Selected-21"))
        assertTrue(BackupSections.keepHistoryKey("SelectedKey-21"))
        assertTrue(BackupSections.keepHistoryKey("Incognito"))
    }

    @Test
    fun historySkipsCachesTokensAndPaths() {
        assertFalse(BackupSections.keepHistoryKey("CommentAuthResponse"))
        assertFalse(BackupSections.keepHistoryKey("DownloadsDir"))
        assertFalse(BackupSections.keepHistoryKey("HasUpdatedPrefs"))
        assertFalse(BackupSections.keepHistoryKey("media_cover_21"))
        assertFalse(BackupSections.keepHistoryKey("banner_anime_url"))
    }

    @Test
    fun missingExtensionsUsesNamesAndSkipsInstalled() {
        val backedUp = mapOf(
            "pkg.a" to BackupSections.extensionEntry("anime", "Torrentio", "1.0"),
            "pkg.b" to BackupSections.extensionEntry("manga", "MangaDex", "2.0"),
            "pkg.c" to "anime||1.0",
        )
        assertEquals(listOf("MangaDex", "pkg.c"), BackupSections.missingExtensions(backedUp, setOf("pkg.a")))
    }

    @Test
    fun extraKeysPassValidation() {
        val json = """{"format":"editotsu-settings","version":1,"appVersion":"x","exportedAt":1,"payload":{
            "General":{"AnimeExtensionRepos":{"type":"java.util.HashSet","value":["https://a"]}},
            "app_prefs":{"hidden_anime_catalogues":{"type":"java.util.HashSet","value":["1"]}},
            "installed_extensions":{"pkg.a":{"type":"kotlin.String","value":"anime|A|1"}},
            "Irrelevant":{"21_5":{"type":"kotlin.Long","value":120000}}
        }}"""
        assertTrue(validateBackupPayload(json) is BackupCheck.Valid)
    }

    @Test
    fun appPrefsSkipUpdateCounters() {
        assertFalse(BackupSections.keepAppPrefKey("animeext_updates_count"))
        assertTrue(BackupSections.keepAppPrefKey("hidden_anime_catalogues"))
    }
}
