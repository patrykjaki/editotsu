package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MpvBootstrapTest {

    private lateinit var fakeClient: FakeMpvClient

    @Before
    fun setup() {
        fakeClient = FakeMpvClient()
    }

    @Test
    fun testApplySettingOptionString() {
        val setting = MpvInitOption("vo", MpvSettingValue.StringValue("gpu"), api = SettingApi.OPTION_STRING)
        applySetting(fakeClient, setting)
        assertEquals("gpu", fakeClient.stringProperties["vo"])
    }

    @Test
    fun testApplySettingOptionStringError_ThrowsException() {
        fakeClient.optionApplyErrorRc = -2
        val setting = MpvInitOption("invalid-opt", MpvSettingValue.StringValue("val"), api = SettingApi.OPTION_STRING)
        try {
            applySetting(fakeClient, setting)
            fail("Expected MpvOptionApplyException")
        } catch (e: MpvOptionApplyException) {
            assertEquals("invalid-opt", e.optionName)
            assertEquals(-2, e.rc)
        }
    }

    @Test
    fun testApplySettingProperties() {
        applySetting(fakeClient, MpvInitOption("title", MpvSettingValue.StringValue("Anime"), api = SettingApi.PROPERTY_STRING))
        assertEquals("Anime", fakeClient.stringProperties["title"])

        applySetting(fakeClient, MpvInitOption("volume", MpvSettingValue.IntValue(100), api = SettingApi.PROPERTY_INT))
        assertEquals(100, fakeClient.intProperties["volume"])

        applySetting(fakeClient, MpvInitOption("speed", MpvSettingValue.DoubleValue(1.5), api = SettingApi.PROPERTY_DOUBLE))
        assertEquals(1.5, fakeClient.doubleProperties["speed"] ?: 0.0, 0.001)

        applySetting(fakeClient, MpvInitOption("pause", MpvSettingValue.BooleanValue(true), api = SettingApi.PROPERTY_BOOLEAN))
        assertEquals(true, fakeClient.booleanProperties["pause"])
    }

    @Test
    fun testVerifySettingTimingGuard() {
        val setting = MpvInitOption(
            name = "vo",
            value = MpvSettingValue.StringValue("gpu"),
            verification = SettingVerification.ReadBackString("gpu"),
            verificationTiming = VerificationTiming.AFTER_INIT
        )
        // Immediate timing should be a no-op even if property is not set
        verifySetting(fakeClient, setting, VerificationTiming.IMMEDIATE)
    }

    @Test
    fun testVerifySettingReadBackString_PassAndFail() {
        fakeClient.stringProperties["vo"] = "gpu"
        val passSetting = MpvInitOption(
            name = "vo",
            value = MpvSettingValue.StringValue("gpu"),
            verification = SettingVerification.ReadBackString("gpu"),
            verificationTiming = VerificationTiming.IMMEDIATE
        )
        verifySetting(fakeClient, passSetting, VerificationTiming.IMMEDIATE)

        val failSetting = MpvInitOption(
            name = "vo",
            value = MpvSettingValue.StringValue("gpu"),
            verification = SettingVerification.ReadBackString("null"),
            verificationTiming = VerificationTiming.IMMEDIATE
        )
        try {
            verifySetting(fakeClient, failSetting, VerificationTiming.IMMEDIATE)
            fail("Expected MpvSettingVerificationException")
        } catch (e: MpvSettingVerificationException) {
            assertEquals("vo", e.settingName)
            assertEquals("null", e.expected)
            assertEquals("gpu", e.actual)
        }
    }

    @Test
    fun testVerifySettingReadBackDoubleWithTolerance() {
        fakeClient.doubleProperties["speed"] = 1.0000001
        val setting = MpvInitOption(
            name = "speed",
            value = MpvSettingValue.DoubleValue(1.0),
            verification = SettingVerification.ReadBackDouble(1.0, tolerance = 1e-4),
            verificationTiming = VerificationTiming.AFTER_INIT
        )
        verifySetting(fakeClient, setting, VerificationTiming.AFTER_INIT)

        val tightToleranceSetting = MpvInitOption(
            name = "speed",
            value = MpvSettingValue.DoubleValue(1.0),
            verification = SettingVerification.ReadBackDouble(1.0, tolerance = 1e-9),
            verificationTiming = VerificationTiming.AFTER_INIT
        )
        try {
            verifySetting(fakeClient, tightToleranceSetting, VerificationTiming.AFTER_INIT)
            fail("Expected MpvSettingVerificationException due to strict tolerance")
        } catch (e: MpvSettingVerificationException) {
            assertEquals("speed", e.settingName)
        }
    }

    @Test
    fun testVerifySettingCustomInvariant() {
        var invariantCalled = false
        fakeClient.stringProperties["custom"] = "none"
        val setting = MpvInitOption(
            name = "custom",
            value = MpvSettingValue.StringValue("none"),
            verification = SettingVerification.CustomInvariant { client ->
                invariantCalled = true
                client.getPropertyString("custom") == "none"
            },
            verificationTiming = VerificationTiming.AFTER_INIT
        )
        verifySetting(fakeClient, setting, VerificationTiming.AFTER_INIT)
        assertTrue(invariantCalled)
    }

    @Test
    fun testRedactionHelper() {
        assertEquals("gpu", redactOptionValue("vo", "gpu"))
        assertEquals("[pathKind: sub-fonts]", redactOptionValue("sub-fonts-dir", "/data/user/0/fonts"))
        assertEquals("<redacted>", redactOptionValue("custom-header", "secret-token"))
    }
}
