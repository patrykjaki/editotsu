package ani.dantotsu.media.anime.player

import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import ani.dantotsu.util.Logger
import org.json.JSONObject
import java.util.UUID

/**
 * MAX MERGE M4B DRM (upstream be6c4d99, adapted to the pinned Media3 1.8.0
 * [MediaDrmCallback] shape, whose key/provision calls return raw bytes):
 * normalises license responses from servers that do not return a bare
 * Widevine blob - base64 text, or a JSON envelope like
 * `{"status":"OK","license":"<base64>"}`. MediaDrm expects decoded protobuf
 * and fails either form with ERROR_DRM_LICENSE_PARSE (cdmError 70). The HTTP
 * work is left to [HttpMediaDrmCallback].
 */
@UnstableApi
class WrappedDrmCallback(private val delegate: HttpMediaDrmCallback) : MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): ByteArray = delegate.executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(
        uuid: UUID,
        request: ExoMediaDrm.KeyRequest,
    ): ByteArray {
        val response = delegate.executeKeyRequest(uuid, request)
        // HOLD3: scheme-aware normalization. Only Widevine bodies are
        // unwrapped; PlayReady/ClearKey/unknown responses pass through
        // untouched so their formats can never be mishandled here.
        val scheme = DrmBackendPolicy.schemeForUuid(uuid)
        return when (val normalized = DrmBackendPolicy.normalizeLicenseForScheme(response, scheme)) {
            is DrmBackendPolicy.LicenseBytes.Decoded -> normalized.bytes.also {
                Logger.log("DRM: decoded base64 license (${response.size} -> ${it.size} bytes)")
            }
            is DrmBackendPolicy.LicenseBytes.Passthrough ->
                if (scheme == DrmBackendPolicy.Scheme.WIDEVINE) unwrapJsonEnvelope(response)
                else response
        }
    }

    private fun unwrapJsonEnvelope(raw: ByteArray): ByteArray {
        if (!DrmBackendPolicy.isJsonEnvelopeCandidate(raw)) return raw
        val text = String(DrmBackendPolicy.stripJsonPrefix(raw), Charsets.UTF_8)
        val encoded = runCatching {
            val obj = JSONObject(text)
            obj.optString("license").ifEmpty { obj.optString("licenseMessage") }
        }.getOrNull()
        if (encoded.isNullOrEmpty()) {
            // Never place raw license bodies in errors/logs.
            throw IllegalStateException(DrmBackendPolicy.LICENSE_ENVELOPE_ERROR)
        }
        Logger.log("DRM: unwrapped JSON license envelope")
        return runCatching {
            Base64.decode(encoded.trim(), Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isNotEmpty() && it[0] == 0x08.toByte() } ?: raw
    }

    companion object {
        /** Session managers that route license responses through [WrappedDrmCallback]. */
        fun providerFor(dataSourceFactory: DataSource.Factory) =
            DrmSessionManagerProvider { mediaItem: MediaItem ->
                val config = mediaItem.localConfiguration?.drmConfiguration
                val licenseUri = config?.licenseUri
                if (config == null || licenseUri == null) {
                    DrmSessionManager.DRM_UNSUPPORTED
                } else {
                    val http = HttpMediaDrmCallback(licenseUri.toString(), true, dataSourceFactory)
                    config.licenseRequestHeaders.forEach { (name, value) ->
                        http.setKeyRequestProperty(name, value)
                    }
                    DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(config.scheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(config.multiSession)
                        .setPlayClearSamplesWithoutKeys(config.playClearContentWithoutKey)
                        .build(WrappedDrmCallback(http))
                        // A downloaded item carries the id of a license the CDM already
                        // holds, so restoring it needs no network.
                        .also { it.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, config.keySetId) }
                }
            }
    }
}
