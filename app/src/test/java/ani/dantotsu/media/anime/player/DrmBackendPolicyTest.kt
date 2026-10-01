package ani.dantotsu.media.anime.player

import ani.dantotsu.parsers.DrmInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * MAX MERGE M4B DRM (HOLD2): backend selection, scheme mapping, license
 * plumbing, and response normalization pinned on the JVM.
 */
class DrmBackendPolicyTest {

    private fun drm(scheme: String, url: String) = DrmInfo(scheme, url)

    @Test
    fun `clear video selects MPV backend`() {
        assertEquals(DrmBackendPolicy.Backend.MPV, DrmBackendPolicy.selectBackend(null))
    }

    @Test
    fun `widevine video selects DRM backend`() {
        assertEquals(
            DrmBackendPolicy.Backend.MEDIA3_DRM,
            DrmBackendPolicy.selectBackend(drm("widevine", "https://lic.example/wv"))
        )
    }

    @Test
    fun `playready and clearkey map deterministically`() {
        assertEquals(
            DrmBackendPolicy.Scheme.PLAYREADY,
            DrmBackendPolicy.mapScheme("PlayReady")
        )
        assertEquals(
            DrmBackendPolicy.Scheme.CLEARKEY,
            DrmBackendPolicy.mapScheme("clearkey")
        )
        assertEquals(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
            DrmBackendPolicy.mapScheme("WIDEVINE")!!.uuid
        )
        assertEquals(
            UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"),
            DrmBackendPolicy.mapScheme("playready")!!.uuid
        )
        assertEquals(
            UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"),
            DrmBackendPolicy.mapScheme("ClearKey")!!.uuid
        )
    }

    @Test
    fun `malformed or absent metadata stays clear path`() {
        assertEquals(DrmBackendPolicy.Backend.MPV, DrmBackendPolicy.selectBackend(drm("", "https://lic.example/x")))
        assertEquals(DrmBackendPolicy.Backend.MPV, DrmBackendPolicy.selectBackend(drm("rot13", "https://lic.example/x")))
        assertEquals(DrmBackendPolicy.Backend.MPV, DrmBackendPolicy.selectBackend(drm("widevine", "")))
        assertEquals(DrmBackendPolicy.Backend.MPV, DrmBackendPolicy.selectBackend(drm("widevine", "   ")))
        assertNull(DrmBackendPolicy.mapScheme(null))
        assertNull(DrmBackendPolicy.mapScheme("  "))
        assertNull(DrmBackendPolicy.licenseRequest(drm("widevine", "")))
        assertNull(DrmBackendPolicy.licenseRequest(drm("nope", "https://lic.example/x")))
    }

    @Test
    fun `license url and headers reach request configuration`() {
        val req = DrmBackendPolicy.licenseRequest(
            DrmInfo("widevine", "https://lic.example/wv", mapOf("Authorization" to "Bearer x"))
        )!!
        assertEquals("https://lic.example/wv", req.url)
        assertEquals(mapOf("Authorization" to "Bearer x"), req.headers)
    }

    @Test
    fun `license reaches drm session configuration`() {
        val config = buildDrmConfiguration(
            DrmInfo("widevine", "https://lic.example/wv", mapOf("Authorization" to "Bearer x"))
        )!!
        assertEquals(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"),
            config.scheme
        )
        // licenseUri itself parses through android.net.Uri (stubbed to null on
        // the JVM); the URL string reaching the builder is pinned by the
        // licenseRequest test above, and URI parsing stays device-validated.
        assertEquals(mapOf("Authorization" to "Bearer x"), config.licenseRequestHeaders)
        assertTrue(config.multiSession)
    }

    @Test
    fun `protobuf license passes through`() {
        val raw = byteArrayOf(0x08, 0x01, 0x02)
        val out = DrmBackendPolicy.normalizeLicenseBytes(raw)
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Passthrough)
        assertEquals(raw.toList(), (out as DrmBackendPolicy.LicenseBytes.Passthrough).raw.toList())
        assertTrue(DrmBackendPolicy.normalizeLicenseBytes(byteArrayOf()) is DrmBackendPolicy.LicenseBytes.Passthrough)
    }

    @Test
    fun `base64 license decodes`() {
        val protobuf = byteArrayOf(0x08, 0x10, 0x20, 0x30)
        val b64 = java.util.Base64.getEncoder().encodeToString(protobuf).toByteArray()
        val out = DrmBackendPolicy.normalizeLicenseBytes(b64)
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Decoded)
        assertEquals(protobuf.toList(), (out as DrmBackendPolicy.LicenseBytes.Decoded).bytes.toList())
    }

    @Test
    fun `non license bytes pass through for envelope handling`() {
        val out = DrmBackendPolicy.normalizeLicenseBytes("{\"license\":\"oops\"}".toByteArray())
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Passthrough)
        val garbage = DrmBackendPolicy.normalizeLicenseBytes("!!!not-base64!!!".toByteArray())
        assertTrue(garbage is DrmBackendPolicy.LicenseBytes.Passthrough)
    }
}
