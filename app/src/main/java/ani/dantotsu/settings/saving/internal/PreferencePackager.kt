package ani.dantotsu.settings.saving.internal

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ani.dantotsu.connections.mal.MALKeystore
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import java.io.File
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferencePackager {
    //map one or more preference maps for import/export

    companion object {

        /** Names of backed-up extensions that were not installed when the last restore ran. */
        @Volatile
        var lastMissingExtensions: List<String> = emptyList()
            private set

        /**
         * @return a json string of the packed preferences
         */
        fun pack(map: Map<Location, SharedPreferences>, includeExtensionSettings: Boolean = false): String {
            val prefsMap = packagePreferences(map).toMutableMap()
            if (includeExtensionSettings) prefsMap.putAll(packSourcePrefs())
            return Gson().toJson(prefsMap)
        }

        /** Packs everything the chosen [sections] cover; see [BackupSections]. */
        fun packSections(sections: Set<BackupSection>): String {
            val context = Injekt.get<Application>()
            val locations = BackupSections.locationsFor(sections)
                .filter { it != Location.ExtensionSettings }
            val prefsMap = packagePreferences(
                locations.associateWith {
                    context.getSharedPreferences(it.location, Context.MODE_PRIVATE)
                }
            ).toMutableMap()
            if (BackupSection.Extensions in sections) {
                prefsMap.putAll(packSourcePrefs())
                prefsMap[BackupSections.APP_PREFS] = packageOne(
                    PreferenceManager.getDefaultSharedPreferences(context)
                ) { BackupSections.keepAppPrefKey(it) }
                prefsMap[BackupSections.NOVEL_PLUGIN_STORAGE] = packageOne(
                    context.getSharedPreferences(BackupSections.NOVEL_PLUGIN_STORAGE, Context.MODE_PRIVATE)
                )
                prefsMap[BackupSections.INSTALLED_EXTENSIONS] = installedExtensions()
                    .mapValues { (_, entry) -> typed(entry) }
            }
            return Gson().toJson(prefsMap.filter { (key, value) -> BackupSections.keepInPayload(key, value.isEmpty()) })
        }

        private fun packSourcePrefs(): Map<String, Map<String, Any>> {
            val result = mutableMapOf<String, Map<String, Any>>()
            try {
                val context = Injekt.get<Application>()
                val sharedPrefsDir = File(context.filesDir.parent, "shared_prefs")
                val files = sharedPrefsDir.listFiles { _, name ->
                    name.startsWith("source_") && name.endsWith(".xml")
                }
                files?.forEach { file ->
                    val prefName = file.name.removeSuffix(".xml")
                    val packed = packageOne(context.getSharedPreferences(prefName, Context.MODE_PRIVATE))
                    if (packed.isNotEmpty()) result[prefName] = packed
                }
            } catch (e: Exception) {
                Logger.log("Backup: source settings not packed: ${e.message}")
            }
            return result
        }

        private fun installedExtensions(): Map<String, String> {
            val result = sortedMapOf<String, String>()
            runCatching {
                Injekt.get<AnimeExtensionManager>().installedExtensionsFlow.value.forEach {
                    result[it.pkgName] = BackupSections.extensionEntry("anime", it.name, it.versionName)
                }
            }
            runCatching {
                Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value.forEach {
                    result[it.pkgName] = BackupSections.extensionEntry("manga", it.name, it.versionName)
                }
            }
            runCatching {
                Injekt.get<NovelExtensionManager>().installedExtensionsFlow.value.forEach {
                    result[it.pkgName] = BackupSections.extensionEntry("novel", it.name, it.versionName)
                }
            }
            return result
        }

        private fun installedPackages(): Set<String> = installedExtensions().keys

        /**
         * Validates [json] (a versioned envelope or a bare payload) and writes it.
         * Nothing is written when validation fails.
         * @return true if successful, false if error
         */
        fun unpack(json: String): Boolean {
            val payloadJson = when (val check = validateBackupPayload(json)) {
                is BackupCheck.Valid -> check.payloadJson
                is BackupCheck.Invalid -> {
                    Logger.log("Backup restore rejected: ${check.reason}")
                    return false
                }
            }
            return unpackagePreferences(decodePayload(payloadJson))
        }

        /** Payload json to location -> key -> typed value; null for types restore cannot write. */
        internal fun decodePayload(payloadJson: String): Map<String, Map<String, Any?>> {
            val type = object :
                TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type
            val rawPrefsMap: Map<String, Map<String, Map<String, Any>>> =
                Gson().fromJson(payloadJson, type)

            val deserializedMap = mutableMapOf<String, Map<String, Any?>>()
            rawPrefsMap.forEach { (prefName, prefValueMap) ->
                val innerMap = mutableMapOf<String, Any?>()
                prefValueMap.forEach { (key, typeValueMap) ->
                    val value = typeValueMap["value"]
                    innerMap[key] = when (typeValueMap["type"] as? String) {
                        "kotlin.Int" -> (value as? Double)?.toInt()
                        "kotlin.String" -> value.toString()
                        "kotlin.Boolean" -> value as? Boolean
                        "kotlin.Float" -> value.toString().toFloatOrNull()
                        "kotlin.Long" -> (value as? Double)?.toLong()
                        "java.util.HashSet" -> value as? ArrayList<*>
                        else -> null
                    }
                }
                deserializedMap[prefName] = innerMap
            }
            return deserializedMap
        }

        private fun typed(value: Any): Map<String, Any> = mapOf(
            "type" to value.javaClass.kotlin.qualifiedName.orEmpty(),
            "value" to value
        )

        /** Only value types the restore side can write back; anything else is skipped. */
        private fun packageOne(
            preferences: SharedPreferences,
            keep: (String) -> Boolean = { true },
        ): Map<String, Any> {
            val prefMap = mutableMapOf<String, Any>()
            preferences.all.forEach { (key, value) ->
                if (value != null && keep(key)) {
                    val packed = typed(value)
                    if (packed["type"] in PACKABLE_TYPES) prefMap[key] = packed
                }
            }
            return prefMap
        }

        private val PACKABLE_TYPES = setOf(
            "kotlin.Int", "kotlin.String", "kotlin.Boolean",
            "kotlin.Float", "kotlin.Long", "java.util.HashSet"
        )

        /**
         * @return a map of location names to a map of preference names to their values
         */
        private fun packagePreferences(map: Map<Location, SharedPreferences>): Map<String, Map<String, Any>> {
            val result = mutableMapOf<String, Map<String, Any>>()
            for ((location, preferences) in map) {
                result[location.name] = when (location) {
                    Location.Irrelevant -> packageOne(preferences) { BackupSections.keepHistoryKey(it) }
                    Location.Protected -> packageOne(preferences) { BackupSections.keepProtectedKey(it) } +
                        malTokenForBackup(preferences)
                    else -> packageOne(preferences)
                }
            }
            return result
        }

        private fun malTokenForBackup(protected: SharedPreferences): Map<String, Any> {
            val encrypted = protected.getString("MALEncryptedToken", null)
            if (encrypted.isNullOrBlank()) return emptyMap()
            val plain = MALKeystore.decrypt(encrypted).getOrNull() ?: return emptyMap()
            return mapOf(BackupSections.MAL_TOKEN_BACKUP to typed(plain))
        }

        /** Encrypts a restored MAL token with this install's key; drops the plaintext copy. */
        private fun restoreMalToken(context: Context) {
            val protected = context.getSharedPreferences(Location.Protected.location, Context.MODE_PRIVATE)
            val plain = protected.getString(BackupSections.MAL_TOKEN_BACKUP, null)
            protected.edit().remove(BackupSections.MAL_TOKEN_BACKUP).commit()
            if (plain.isNullOrBlank()) return
            val encrypted = MALKeystore.encrypt(plain).getOrNull()
            if (encrypted == null) {
                Logger.log("Backup restore: MAL token could not be encrypted; MAL login not restored")
                return
            }
            protected.edit().putString("MALEncryptedToken", encrypted).commit()
        }

        /**
         * @return true if successful, false if error
         */
        private fun unpackagePreferences(map: Map<String, Map<String, *>>): Boolean {
            val context = Injekt.get<Application>()
            var success = true
            lastMissingExtensions = emptyList()
            map.forEach { (location, prefMap) ->
                val locationEnum = Location.entries.find { it.name == location }
                when {
                    location == BackupSections.INSTALLED_EXTENSIONS -> {
                        val backedUp = prefMap.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
                        lastMissingExtensions =
                            BackupSections.missingExtensions(backedUp, installedPackages())
                    }
                    location == BackupSections.APP_PREFS -> {
                        if (!writePrefs(PreferenceManager.getDefaultSharedPreferences(context), prefMap, clear = false)) success = false
                    }
                    location == BackupSections.NOVEL_PLUGIN_STORAGE -> {
                        val pref = context.getSharedPreferences(location, Context.MODE_PRIVATE)
                        if (!writePrefs(pref, prefMap, clear = false)) success = false
                    }
                    locationEnum == Location.Irrelevant -> {
                        val pref = context.getSharedPreferences(Location.Irrelevant.location, Context.MODE_PRIVATE)
                        if (!writePrefs(pref, prefMap, clear = false)) success = false
                    }
                    locationEnum == Location.ExtensionSettings -> Unit
                    locationEnum != null -> {
                        if (!PrefManager.importAllPrefs(prefMap, locationEnum)) success = false
                        if (locationEnum == Location.Protected) restoreMalToken(context)
                    }
                    location.startsWith("source_") -> {
                        val pref = context.getSharedPreferences(location, Context.MODE_PRIVATE)
                        if (!writePrefs(pref, prefMap, clear = true)) success = false
                    }
                }
            }
            return success
        }

        private fun writePrefs(pref: SharedPreferences, prefs: Map<String, *>, clear: Boolean): Boolean {
            return try {
                var hadError = false
                if (clear) pref.edit().clear().apply()
                with(pref.edit()) {
                    prefs.forEach { (key, value) ->
                        when (value) {
                            null -> Unit
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is Float -> putFloat(key, value)
                            is Long -> putLong(key, value)
                            is String -> putString(key, value)
                            is ArrayList<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                            is Set<*> -> putStringSet(key, value.map { it.toString() }.toSet())
                            else -> hadError = true
                        }
                    }
                    apply()
                }
                !hadError
            } catch (e: Exception) {
                Logger.log("Backup restore: ${e.message}")
                false
            }
        }
    }
}
