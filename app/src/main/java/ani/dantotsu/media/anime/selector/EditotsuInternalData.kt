package ani.dantotsu.media.anime.selector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Raw release facts a source may put in aniyomi `Video.internalData` as
 * `{"editotsu":1, "torrentName":"..", "fileName":"..", ...}`. Only the names
 * are read here; parsing stays in [SourceRowClassifier] (one parser for all
 * sources). Anything else (missing, not JSON, other extensions' strings,
 * wrong version or types) gives null, and the picker uses text parsing.
 */
data class EditotsuInternalData(val torrentName: String?, val fileName: String?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String?): EditotsuInternalData? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            val version = (obj["editotsu"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (version != 1) return null
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return EditotsuInternalData(torrentName = str("torrentName"), fileName = str("fileName"))
        }
    }
}
