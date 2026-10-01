package ani.dantotsu.media.anime.player

import ani.dantotsu.parsers.DrmInfo
import java.util.UUID

/**
 * MAX MERGE M4B DRM (HOLD2, upstream be6c4d99 adapted): pure DRM backend
 * policy. MPV stays the default for clear/non-DRM content; DRM-tagged
 * content routes to the narrow Media3 DRM backend implementing
 * [PlaybackEngine]. Android CDM keys are never extracted for libmpv.
 */
object DrmBackendPolicy {

    enum class Scheme(val uuid: UUID) {
        WIDEVINE(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")),
        PLAYREADY(UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95")),
        CLEARKEY(UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")),
    }

    /** Case-insensitive scheme mapping; null/blank/unknown stays null (= clear path). */
    fun mapScheme(raw: String?): Scheme? = when (raw?.trim()?.lowercase()) {
        "widevine" -> Scheme.WIDEVINE
        "playready" -> Scheme.PLAYREADY
        "clearkey", "clear-key", "clear_key" -> Scheme.CLEARKEY
        else -> null
    }

    /**
     * Backend selection (HOLD3 D4, fail-closed):
     * - drm == null → [ClearMpv] (correct: no DRM signal at all)
     * - unknown scheme or blank license URL → [Unsupported] (an explicitly
     *   DRM-identified stream is NOT clear content; sending it to MPV would
     *   only produce a confusing playback failure)
     * - scheme known but absent on this device → [Unsupported]
     * - otherwise → [DrmReady]
     *
     * @param isSchemeSupportedOnDevice device CDM capability probe
     * (android MediaDrm on device; injected in tests).
     */
    sealed interface BackendDecision {
        data object ClearMpv : BackendDecision
        data object DrmReady : BackendDecision
        data class Unsupported(val reason: String) : BackendDecision
    }

    fun decideBackend(
        drm: DrmInfo?,
        isSchemeSupportedOnDevice: (UUID) -> Boolean = { false },
    ): BackendDecision {
        if (drm == null) return BackendDecision.ClearMpv
        val scheme = mapScheme(drm.scheme)
            ?: return BackendDecision.Unsupported("unknown DRM scheme '${drm.scheme}'")
        if (drm.licenseUrl.isBlank()) {
            return BackendDecision.Unsupported("missing license URL for ${scheme.name}")
        }
        if (!runCatching { isSchemeSupportedOnDevice(scheme.uuid) }.getOrDefault(false)) {
            return BackendDecision.Unsupported("${scheme.name} not supported on this device")
        }
        return BackendDecision.DrmReady
    }

    /** Reverse lookup for scheme-aware license handling; null when unknown. */
    fun schemeForUuid(uuid: UUID): Scheme? = Scheme.entries.firstOrNull { it.uuid == uuid }

    /**
     * Video-level backend routing (HOLD4 B5): an explicit parse error fails
     * closed before [decideBackend] is even consulted.
     */
    fun decideForVideo(
        drm: DrmInfo?,
        drmParseError: String?,
        isSchemeSupportedOnDevice: (UUID) -> Boolean = { false },
    ): BackendDecision {
        if (drmParseError != null) {
            return BackendDecision.Unsupported("invalid DRM metadata: $drmParseError")
        }
        return decideBackend(drm, isSchemeSupportedOnDevice)
    }

    /** Validated license request carried into the DRM session configuration. */
    data class LicenseRequest(val url: String, val headers: Map<String, String>)

    fun licenseRequest(drm: DrmInfo): LicenseRequest? {
        if (mapScheme(drm.scheme) == null) return null
        val url = drm.licenseUrl.trim()
        if (url.isEmpty()) return null
        return LicenseRequest(url, drm.licenseHeaders)
    }

    /**
     * License-response pre-normalization without JSON (pure JVM bytes),
     * Widevine only. A SignedMessage protobuf starts with 0x08 and passes
     * through; anything else is a base64 candidate accepted only when it
     * decodes to a 0x08-led payload, otherwise raw bytes pass through for
     * the Android layer's JSON-envelope handling. Non-Widevine schemes must
     * use [normalizeLicenseForScheme] so their bodies are never transformed.
     */
    sealed interface LicenseBytes {
        data class Passthrough(val raw: ByteArray) : LicenseBytes
        data class Decoded(val bytes: ByteArray) : LicenseBytes
    }

    fun normalizeWidevineLicense(raw: ByteArray): LicenseBytes {
        if (raw.isEmpty() || raw[0] == 0x08.toByte()) {
            return LicenseBytes.Passthrough(raw)
        }
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(String(raw, Charsets.UTF_8).trim())
        }.getOrNull()
        if (decoded != null && decoded.isNotEmpty() && decoded[0] == 0x08.toByte()) {
            return LicenseBytes.Decoded(decoded)
        }
        return LicenseBytes.Passthrough(raw)
    }

    /** Scheme gate: only Widevine bodies are normalized, the rest pass through. */
    fun normalizeLicenseForScheme(raw: ByteArray, scheme: Scheme?): LicenseBytes =
        if (scheme == Scheme.WIDEVINE) normalizeWidevineLicense(raw)
        else LicenseBytes.Passthrough(raw)

    /**
     * JSON-envelope candidacy tolerant of leading ASCII whitespace and a
     * UTF-8 BOM: only the first non-ws byte matters. Anything else is never
     * JSON and must not reach a JSON parser.
     */
    fun isJsonEnvelopeCandidate(raw: ByteArray): Boolean {
        var i = 0
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < raw.size && (raw[i] == 0x20.toByte() || raw[i] == 0x09.toByte() || raw[i] == 0x0A.toByte() || raw[i] == 0x0D.toByte())) {
            i++
        }
        return i < raw.size && raw[i] == '{'.code.toByte()
    }

    /** Fixed envelope-failure message: raw license bodies never enter logs/errors. */
    const val LICENSE_ENVELOPE_ERROR = "license server returned an error response"

    /**
     * HOLD4 H3: strip a UTF-8 BOM and leading ASCII JSON whitespace so the
     * envelope bytes that reach the JSON parser never carry the BOM the
     * candidacy check already tolerated.
     */
    fun stripJsonPrefix(raw: ByteArray): ByteArray {
        var i = 0
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < raw.size && (raw[i] == 0x20.toByte() || raw[i] == 0x09.toByte() || raw[i] == 0x0A.toByte() || raw[i] == 0x0D.toByte())) {
            i++
        }
        return if (i == 0) raw else raw.copyOfRange(i, raw.size)
    }
}
