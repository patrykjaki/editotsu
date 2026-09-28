package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 4): two-phase restore — validate everything
 * before anything may mutate. The validator is pure, so rejection
 * structurally cannot touch live preferences (no PrefManager reference).
 */
class BackupFormatTest {

    private fun entry(type: String, value: String) =
        """"k":{"type":"$type","value":$value}"""

    private fun legacy(vararg entries: String) =
        """{"General":{${entries.joinToString(",")}}}"""

    private fun enveloped(payload: String, version: Int = 1) =
        """{"format":"editotsu-settings","version":$version,"appVersion":"0.5","exportedAt":1,"payload":$payload}"""

    @Test
    fun `valid current backup passes`() {
        val check = validateBackupPayload(enveloped(legacy(entry("kotlin.Boolean", "true"))))
        assertTrue(check is BackupCheck.Valid)
        check as BackupCheck.Valid
        assertEquals(1, check.version)
        assertEquals(false, check.isLegacy)
    }

    @Test
    fun `valid legacy backup passes`() {
        val check = validateBackupPayload(legacy(entry("kotlin.Int", "3")))
        assertTrue(check is BackupCheck.Valid)
        assertEquals(true, (check as BackupCheck.Valid).isLegacy)
    }

    @Test
    fun `malformed json rejected`() {
        val check = validateBackupPayload("""{"General":{""")
        assertTrue(check is BackupCheck.Invalid)
    }

    @Test
    fun `structurally invalid json rejected`() {
        // Location value must be an object.
        assertTrue(validateBackupPayload("""{"General":[]}""") is BackupCheck.Invalid)
        // Entry must be an object.
        assertTrue(validateBackupPayload("""{"General":{"k":true}}""") is BackupCheck.Invalid)
        // Missing value.
        assertTrue(validateBackupPayload("""{"General":{"k":{"type":"kotlin.Boolean"}}}""") is BackupCheck.Invalid)
        // Empty locations map.
        assertTrue(validateBackupPayload("""{}""") is BackupCheck.Invalid)
    }

    @Test
    fun `unsupported future version rejected`() {
        val check = validateBackupPayload(enveloped(legacy(entry("kotlin.Boolean", "true")), version = 99))
        assertTrue(check is BackupCheck.Invalid)
    }

    @Test
    fun `unknown format rejected`() {
        val check = validateBackupPayload(
            """{"format":"other-app","version":1,"payload":{}}"""
        )
        assertTrue(check is BackupCheck.Invalid)
    }

    @Test
    fun `invalid value type rejected`() {
        // unpack would null-write this; validation must stop it first.
        val check = validateBackupPayload(legacy(entry("java.util.ArrayList", "[]")))
        assertTrue(check is BackupCheck.Invalid)
    }

    @Test
    fun `unknown location rejected`() {
        val check = validateBackupPayload("""{"Nope":{"k":{"type":"kotlin.Boolean","value":true}}}""")
        assertTrue(check is BackupCheck.Invalid)
    }

    @Test
    fun `source locations accepted`() {
        val check = validateBackupPayload(
            """{"source_xyz":{"k":{"type":"kotlin.String","value":"v"}}}"""
        )
        assertTrue(check is BackupCheck.Valid)
    }

    @Test
    fun `envelope round trip preserves payload`() {
        val payload = legacy(entry("kotlin.String", "\"hi\""))
        val check = validateBackupPayload(wrapBackupEnvelope(payload, "0.5", 7))
        assertTrue(check is BackupCheck.Valid)
        assertEquals(payload, (check as BackupCheck.Valid).payloadJson)
    }
}
