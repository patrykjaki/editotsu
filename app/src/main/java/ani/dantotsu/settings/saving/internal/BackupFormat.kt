package ani.dantotsu.settings.saving.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MAX MERGE M3 HOLD (Blocker 4): two-phase restore contract.
 *
 * Phase A ([validateBackupPayload]) parses and fully validates WITHOUT
 * touching live preferences. Only a [BackupCheck.Valid] payload may
 * proceed to Phase B (`PreferencePackager.unpack`), so malformed,
 * structurally invalid, or unsupported backups can never partially clear
 * live preference locations.
 *
 * New M3 backups carry an envelope (`format`/`version`/`payload`).
 * Legacy bare-location backups remain supported but are validated with
 * the same strictness. Unknown locations and unhandled value types are
 * REJECTED (fail closed) — `unpack` would otherwise null-write unknown
 * types or silently skip unknown locations after clearing known ones.
 *
 * Framework-free (kotlinx.serialization only), unit-proved
 * (BackupFormatTest).
 */
const val BACKUP_FORMAT_TYPE = "editotsu-settings"
const val BACKUP_FORMAT_VERSION = 1

private val BACKUP_VALUE_TYPES = setOf(
    "kotlin.Int",
    "kotlin.String",
    "kotlin.Boolean",
    "kotlin.Float",
    "kotlin.Long",
    "java.util.HashSet"
)

sealed interface BackupCheck {
    /** Canonical payload JSON ready for `unpack`, plus provenance. */
    data class Valid(
        val payloadJson: String,
        val version: Int,
        val isLegacy: Boolean,
    ) : BackupCheck

    data class Invalid(val reason: String) : BackupCheck
}

/** Wrap a packed payload in a versioned M3 envelope. */
fun wrapBackupEnvelope(payloadJson: String, appVersion: String, exportedAt: Long): String {
    // Payload stays raw JSON (not a string) so restore can validate it.
    return """{"format":"$BACKUP_FORMAT_TYPE","version":$BACKUP_FORMAT_VERSION,"appVersion":"$appVersion","exportedAt":$exportedAt,"payload":$payloadJson}"""
}

private val lenientJson = Json { ignoreUnknownKeys = false }

fun validateBackupPayload(json: String): BackupCheck {
    if (json.isBlank()) return BackupCheck.Invalid("empty backup")
    val root = try {
        lenientJson.parseToJsonElement(json)
    } catch (_: Exception) {
        return BackupCheck.Invalid("malformed JSON")
    }
    if (root !is JsonObject) return BackupCheck.Invalid("top level must be an object")

    val format = (root["format"] as? JsonPrimitive)?.contentOrNull
    if (format != null || root.containsKey("version") || root.containsKey("payload")) {
        if (format != BACKUP_FORMAT_TYPE) {
            return BackupCheck.Invalid("unknown format: $format")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: return BackupCheck.Invalid("missing backup version")
        if (version < 1 || version > BACKUP_FORMAT_VERSION) {
            return BackupCheck.Invalid("unsupported backup version: $version")
        }
        val payload = root["payload"] as? JsonObject
            ?: return BackupCheck.Invalid("missing backup payload")
        val locationsError = checkLocationsMap(payload)
        if (locationsError != null) return BackupCheck.Invalid(locationsError)
        return BackupCheck.Valid(
            payloadJson = payload.toString(),
            version = version,
            isLegacy = false
        )
    }

    val legacyError = checkLocationsMap(root)
    if (legacyError != null) return BackupCheck.Invalid(legacyError)
    return BackupCheck.Valid(payloadJson = root.toString(), version = 0, isLegacy = true)
}

/** Null when valid, else the rejection reason. */
private fun checkLocationsMap(map: JsonObject): String? {
    if (map.isEmpty()) return "no preference locations"
    val knownNames = Location.entries.map { it.name }.toSet()
    for ((location, entries) in map) {
        if (location !in knownNames && !location.startsWith("source_")) {
            return "unknown location: $location"
        }
        if (entries !is JsonObject) return "location is not an object: $location"
        for ((key, typeValue) in entries) {
            if (typeValue !is JsonObject) return "entry is not an object: $location/$key"
            val typeName = (typeValue["type"] as? JsonPrimitive)?.contentOrNull
            if (typeName !in BACKUP_VALUE_TYPES) {
                return "unsupported value type: $location/$key ($typeName)"
            }
            if (!typeValue.containsKey("value")) return "missing value: $location/$key"
            // M3 HOLD Blocker 1: the JSON value must match the declared
            // type — unpack would otherwise null-write after clearing.
            val shapeError = checkValueShape(location, key, typeName!!, typeValue["value"])
            if (shapeError != null) return shapeError
        }
    }
    return null
}

private fun checkValueShape(
    location: String,
    key: String,
    typeName: String,
    value: kotlinx.serialization.json.JsonElement?,
): String? {
    val where = "$location/$key ($typeName)"
    if (value == null || value is kotlinx.serialization.json.JsonNull) {
        return "null value: $where"
    }
    when (typeName) {
        "kotlin.Boolean" -> {
            if (value !is JsonPrimitive || value.booleanOrNull == null) {
                return "not a boolean: $where"
            }
        }
        "kotlin.String" -> {
            if (value !is JsonPrimitive || !value.isString) {
                return "not a string: $where"
            }
        }
        "kotlin.Int" -> {
            if (value !is JsonPrimitive || value.intOrNull == null) {
                return "not an int: $where"
            }
        }
        "kotlin.Long" -> {
            if (value !is JsonPrimitive || value.longOrNull == null) {
                return "not a long: $where"
            }
        }
        "kotlin.Float" -> {
            if (value !is JsonPrimitive || value.floatOrNull == null) {
                return "not a float: $where"
            }
        }
        "java.util.HashSet" -> {
            if (value !is kotlinx.serialization.json.JsonArray) {
                return "not an array: $where"
            }
            for (element in value) {
                if (element !is JsonPrimitive || !element.isString) {
                    return "non-string set element: $where"
                }
            }
        }
    }
    return null
}
