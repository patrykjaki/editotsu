package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Theme favourites are user data: a backup that does not carry them must not wipe them. */
class FavouritesRestoreTest {

    private val favourites = """[{"mediaId":1,"animeTitle":"x"}]"""

    /** General as a 0.7 backup writes it: settings only, no AnimeThemes keys. */
    private val backup07 = """
        {"format":"editotsu-settings","version":1,"appVersion":"0.7.0-beta01","exportedAt":1,
         "payload":{"General":{
           "SubscriptionNotificationInterval":{"type":"kotlin.Int","value":3},
           "UseAlarmManager":{"type":"kotlin.Boolean","value":true}
         }}}
    """.trimIndent()

    private fun decodedGeneral(json: String): Map<String, *> {
        val check = validateBackupPayload(json) as BackupCheck.Valid
        return PreferencePackager.decodePayload(check.payloadJson).getValue("General")
    }

    @Test
    fun oldBackupKeepsCurrentFavourites() {
        val current = mapOf("AnimeThemesFavourites" to favourites, "UseAlarmManager" to false, "Stale" to 1)
        val result = PreferencePackager.restoredContents(Location.General, current, decodedGeneral(backup07))
        assertEquals(favourites, result["AnimeThemesFavourites"])
        assertEquals(true, result["UseAlarmManager"])
        assertEquals(3, result["SubscriptionNotificationInterval"])
        // Settings the backup does not carry still go back to their defaults.
        assertFalse("Stale" in result)
    }

    @Test
    fun backupFavouritesReplaceCurrentOnes() {
        val incoming = mapOf("AnimeThemesFavourites" to "[]")
        val current = mapOf("AnimeThemesFavourites" to favourites)
        val result = PreferencePackager.restoredContents(Location.General, current, incoming)
        assertEquals("[]", result["AnimeThemesFavourites"])
    }

    @Test
    fun otherLocationsKeepNothing() {
        val current = mapOf("AnimeThemesFavourites" to favourites)
        val result = PreferencePackager.restoredContents(Location.Player, current, emptyMap<String, Any>())
        assertEquals(emptyMap<String, Any>(), result)
    }
}
