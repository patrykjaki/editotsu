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

    enum class Backend { MPV, MEDIA3_DRM }

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
     * Backend selection:
     * - drm == null → MPV
     * - unknown scheme or blank license URL → MPV (malformed stays clear)
     * - otherwise → Media3 DRM backend
     */
    fun selectBackend(drm: DrmInfo?): Backend {
        if (drm == null) return Backend.MPV
        if (mapScheme(drm.scheme) == null) return Backend.MPV
        if (drm.licenseUrl.isBlank()) return Backend.MPV
        return Backend.MEDIA3_DRM
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
     * License-response pre-normalization without JSON (pure JVM bytes):
     * a Widevine SignedMessage protobuf starts with 0x08 and passes through;
     * anything else is a base64 candidate that must decode to a 0x08-led
     * payload to be accepted, otherwise the raw bytes pass through for the
     * Android layer's JSON-envelope handling.
     */
    sealed interface LicenseBytes {
        data class Passthrough(val raw: ByteArray) : LicenseBytes
        data class Decoded(val bytes: ByteArray) : LicenseBytes
    }

    fun normalizeLicenseBytes(raw: ByteArray): LicenseBytes {
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
}
