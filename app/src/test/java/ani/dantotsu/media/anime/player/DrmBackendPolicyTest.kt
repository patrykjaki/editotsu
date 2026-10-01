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

    private fun decide(
        drm: DrmInfo?,
        supported: Boolean = true,
    ) = DrmBackendPolicy.decideBackend(drm) { supported }

    @Test
    fun `clear video selects MPV backend`() {
        assertEquals(DrmBackendPolicy.BackendDecision.ClearMpv, decide(null))
    }

    @Test
    fun `widevine video selects DRM backend`() {
        assertEquals(
            DrmBackendPolicy.BackendDecision.DrmReady,
            decide(drm("widevine", "https://lic.example/wv"))
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
    fun `malformed or absent metadata fails closed, never MPV`() {
        val bad = listOf(
            drm("", "https://lic.example/x"),
            drm("rot13", "https://lic.example/x"),
            drm("widevine", ""),
            drm("widevine", "   ")
        )
        for (d in bad) {
            val decision = decide(d)
            assertTrue(
                "drm=$d",
                decision is DrmBackendPolicy.BackendDecision.Unsupported
            )
        }
        assertNull(DrmBackendPolicy.mapScheme(null))
        assertNull(DrmBackendPolicy.mapScheme("  "))
        assertNull(DrmBackendPolicy.licenseRequest(drm("widevine", "")))
        assertNull(DrmBackendPolicy.licenseRequest(drm("nope", "https://lic.example/x")))
    }

    @Test
    fun `unsupported decision carries an explicit reason`() {
        val decision = decide(drm("rot13", "https://lic.example/x"))
        assertTrue(decision is DrmBackendPolicy.BackendDecision.Unsupported)
        assertTrue((decision as DrmBackendPolicy.BackendDecision.Unsupported).reason.isNotBlank())
    }

    @Test
    fun `device without scheme support fails closed`() {
        val decision = decide(drm("playready", "https://lic.example/pr"), supported = false)
        assertTrue(decision is DrmBackendPolicy.BackendDecision.Unsupported)
        assertEquals(
            DrmBackendPolicy.BackendDecision.DrmReady,
            decide(drm("playready", "https://lic.example/pr"), supported = true)
        )
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
        val out = DrmBackendPolicy.normalizeWidevineLicense(raw)
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Passthrough)
        assertEquals(raw.toList(), (out as DrmBackendPolicy.LicenseBytes.Passthrough).raw.toList())
        assertTrue(DrmBackendPolicy.normalizeWidevineLicense(byteArrayOf()) is DrmBackendPolicy.LicenseBytes.Passthrough)
    }

    @Test
    fun `base64 license decodes`() {
        val protobuf = byteArrayOf(0x08, 0x10, 0x20, 0x30)
        val b64 = java.util.Base64.getEncoder().encodeToString(protobuf).toByteArray()
        val out = DrmBackendPolicy.normalizeWidevineLicense(b64)
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Decoded)
        assertEquals(protobuf.toList(), (out as DrmBackendPolicy.LicenseBytes.Decoded).bytes.toList())
    }

    @Test
    fun `non license bytes pass through for envelope handling`() {
        val out = DrmBackendPolicy.normalizeWidevineLicense("{\"license\":\"oops\"}".toByteArray())
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Passthrough)
        val garbage = DrmBackendPolicy.normalizeWidevineLicense("!!!not-base64!!!".toByteArray())
        assertTrue(garbage is DrmBackendPolicy.LicenseBytes.Passthrough)
    }

    @Test
    fun `non-widevine bodies are never transformed`() {
        val protobuf = byteArrayOf(0x08, 0x10, 0x20)
        val b64 = java.util.Base64.getEncoder().encodeToString(protobuf).toByteArray()
        val envelope = "{\"license\":\"oops\"}".toByteArray()
        for (scheme in listOf(
            DrmBackendPolicy.Scheme.PLAYREADY,
            DrmBackendPolicy.Scheme.CLEARKEY,
            null
        )) {
            for (body in listOf(protobuf, b64, envelope)) {
                val out = DrmBackendPolicy.normalizeLicenseForScheme(body, scheme)
                assertTrue(out is DrmBackendPolicy.LicenseBytes.Passthrough)
                assertEquals(
                    body.toList(),
                    (out as DrmBackendPolicy.LicenseBytes.Passthrough).raw.toList()
                )
            }
        }
    }

    @Test
    fun `widevine normalization still applies through the scheme gate`() {
        val protobuf = byteArrayOf(0x08, 0x10, 0x20, 0x30)
        val b64 = java.util.Base64.getEncoder().encodeToString(protobuf).toByteArray()
        val out = DrmBackendPolicy.normalizeLicenseForScheme(
            b64, DrmBackendPolicy.Scheme.WIDEVINE
        )
        assertTrue(out is DrmBackendPolicy.LicenseBytes.Decoded)
    }

    @Test
    fun `envelope candidacy tolerates whitespace and BOM`() {
        assertTrue(DrmBackendPolicy.isJsonEnvelopeCandidate("{\"a\":1}".toByteArray()))
        assertTrue(DrmBackendPolicy.isJsonEnvelopeCandidate("  \n\t {\"a\":1}".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"a\":1}".toByteArray()
        assertTrue(DrmBackendPolicy.isJsonEnvelopeCandidate(bom))
        assertTrue(!DrmBackendPolicy.isJsonEnvelopeCandidate(byteArrayOf(0x08, 0x01)))
        assertTrue(!DrmBackendPolicy.isJsonEnvelopeCandidate("oops".toByteArray()))
        assertTrue(!DrmBackendPolicy.isJsonEnvelopeCandidate(byteArrayOf()))
        assertTrue(!DrmBackendPolicy.isJsonEnvelopeCandidate("   ".toByteArray()))
    }

    @Test
    fun `envelope failure message carries no body`() {
        assertEquals(
            "license server returned an error response",
            DrmBackendPolicy.LICENSE_ENVELOPE_ERROR
        )
    }
}
