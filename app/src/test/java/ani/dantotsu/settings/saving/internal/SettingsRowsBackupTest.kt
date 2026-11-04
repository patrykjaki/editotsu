package ani.dantotsu.settings.saving.internal

import android.content.SharedPreferences
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.screens.SettingsScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every pref a settings row writes survives backup and restore with its value and type. */
class SettingsRowsBackupTest {

    /** SharedPreferences over a map; only reads are used by the packager. */
    private class MapPrefs(private val values: Map<String, Any>) : SharedPreferences {
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String?) = key in values
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /** A value that differs from the default, stored the way PrefManager stores it. */
    private fun sample(key: PrefName): Any = when (val d = key.data.default) {
        is Boolean -> !d
        is Int -> d + 1
        is Long -> d + 1
        is Float -> d + 0.5f
        is String -> d + "x"
        is Set<*> -> hashSetOf("a", "b")
        is List<*> -> "serialized-list" // PrefManager stores lists as a serialized string
        else -> error("no sample for ${key.name}: ${d::class}")
    }

    private val rowKeys = SettingsScreens.all.flatMap { s -> s.rows().flatMap { it.keys } }.toSet()

    private fun kept(key: PrefName) = when (key.data.prefLocation) {
        Location.Irrelevant -> BackupSections.keepHistoryKey(key.name)
        Location.Protected -> BackupSections.keepProtectedKey(key.name)
        Location.AnimeDownloads, Location.ExtensionSettings -> false
        else -> true
    }

    @Test
    fun everyRowKeyRoundTrips() {
        val byLocation = rowKeys.groupBy { it.data.prefLocation }
        val prefs = byLocation.mapValues { (_, keys) -> MapPrefs(keys.associate { it.name to sample(it) }) }
        val decoded = PreferencePackager.decodePayload(PreferencePackager.pack(prefs))

        for (key in rowKeys.filter(::kept)) {
            val restored = decoded[key.data.prefLocation.name]?.get(key.name)
            val expected = sample(key)
            if (expected is Set<*>) {
                assertEquals("${key.name} lost", expected, (restored as? List<*>)?.toSet())
            } else {
                assertEquals("${key.name} lost or changed type", expected, restored)
            }
        }
    }

    @Test
    fun rowsCoverEveryBackedUpSettingsLocation() {
        // The settings locations a backup always includes all have rows; a row key elsewhere
        // must be one the backup skips on purpose (history filter, downloads).
        val always = BackupSections.locationsFor(emptySet()).toSet()
        val elsewhere = rowKeys.filter { it.data.prefLocation !in always }
        for (key in elsewhere) {
            assertTrue("${key.name} in ${key.data.prefLocation}",
                key.data.prefLocation in setOf(Location.Irrelevant, Location.Protected))
        }
    }
}
