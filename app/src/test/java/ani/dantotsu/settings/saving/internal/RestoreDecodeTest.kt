package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RestoreDecodeTest {

    @Test
    fun longKeepsFullPrecision() {
        val big = 9_007_199_254_740_993L // 2^53 + 1, not representable as a Double
        val decoded = PreferencePackager.decodePayload(
            """{"General":{"Id":{"type":"kotlin.Long","value":$big}}}"""
        )
        assertEquals(big, decoded["General"]?.get("Id"))
    }

    @Test
    fun unknownTypesAreSkippedNotFailed() {
        val decoded = PreferencePackager.decodePayload(
            """{"General":{
                "Odd":{"type":"kotlin.Double","value":1.5},
                "Bad":{"type":"kotlin.Int","value":"x"},
                "Keep":{"type":"kotlin.Int","value":3}
            }}"""
        ).getValue("General")
        assertEquals(mapOf("Keep" to 3), decoded)
        assertFalse("Odd" in decoded)
    }

    @Test
    fun otherTypesStillRoundTrip() {
        val decoded = PreferencePackager.decodePayload(
            """{"Player":{
                "F":{"type":"kotlin.Float","value":0.8},
                "B":{"type":"kotlin.Boolean","value":true},
                "S":{"type":"kotlin.String","value":"a"},
                "Set":{"type":"java.util.HashSet","value":["x","y"]}
            }}"""
        ).getValue("Player")
        assertEquals(0.8f, decoded["F"])
        assertEquals(true, decoded["B"])
        assertEquals("a", decoded["S"])
        assertEquals(listOf("x", "y"), decoded["Set"])
    }
}
