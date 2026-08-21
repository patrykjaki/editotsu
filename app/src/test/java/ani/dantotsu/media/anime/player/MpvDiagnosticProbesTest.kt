package ani.dantotsu.media.anime.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MpvDiagnosticProbesTest {

    private lateinit var fakeClient: FakeMpvClient
    private lateinit var clientFactory: FakeMpvClientFactory

    @Before
    fun setup() {
        MpvNativeRuntime.resetForTesting()
        fakeClient = FakeMpvClient()
        clientFactory = FakeMpvClientFactory { fakeClient }
    }

    @After
    fun tearDown() {
        MpvNativeRuntime.resetForTesting()
    }

    @Test
    fun testZeroOptionProbe_SuccessWhenVersionReturned() {
        fakeClient.stringProperties["mpv-version"] = "mpv 0.38.0"
        val latch = CountDownLatch(1)
        var probeResult: FinalProbeResult? = null

        MpvDiagnosticProbes.runZeroOptionProbe(
            context = null,
            clientFactory = clientFactory,
            runGeneration = 1L
        ) { result ->
            probeResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(probeResult)
        assertTrue(probeResult!!.isPass)
        assertTrue(fakeClient.isCreated)
        assertTrue(fakeClient.isDestroyed)
    }

    @Test
    fun testZeroOptionProbe_SanityFailWhenVersionMissing() {
        fakeClient.stringProperties.remove("mpv-version")
        val latch = CountDownLatch(1)
        var probeResult: FinalProbeResult? = null

        MpvDiagnosticProbes.runZeroOptionProbe(
            context = null,
            clientFactory = clientFactory,
            runGeneration = 2L
        ) { result ->
            probeResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(probeResult)
        assertFalse(probeResult!!.isPass)
        assertTrue(probeResult!!.body.primary is ProbePrimaryResult.SanityFail)
        assertEquals(OwnerReleaseResult.Released, probeResult!!.ownerRelease)
        assertTrue(fakeClient.isDestroyed)
    }

    @Test
    fun testSinglePrefixProbe_AppliesOptionsAndVerifies() {
        fakeClient.stringProperties["mpv-version"] = "mpv 0.38.0"
        val options = listOf(
            MpvInitOption("vo", MpvSettingValue.StringValue("gpu")),
            MpvInitOption("hwdec", MpvSettingValue.StringValue("auto")),
            MpvInitOption("idle", MpvSettingValue.StringValue("yes"))
        )

        val latch = CountDownLatch(1)
        var probeResult: FinalProbeResult? = null

        MpvDiagnosticProbes.runSinglePrefixProbe(
            context = null,
            clientFactory = clientFactory,
            effectivePreInitOptions = options,
            prefixLength = 2,
            runGeneration = 3L
        ) { result ->
            probeResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(probeResult)
        assertTrue(probeResult!!.isPass)
        assertEquals("gpu", fakeClient.stringProperties["vo"])
        assertEquals("auto", fakeClient.stringProperties["hwdec"])
        assertFalse(fakeClient.stringProperties.containsKey("idle"))
        assertTrue(fakeClient.isDestroyed)
    }

    @Test
    fun testFullBootstrapProbe_ExecutesFullLifecycleWithObserver() {
        fakeClient.stringProperties["mpv-version"] = "mpv 0.38.0"
        val settings = listOf(
            ResolvedInitSetting.Apply(MpvInitOption("vo", MpvSettingValue.StringValue("gpu")), phase = BootstrapPhase.PRE_INIT),
            ResolvedInitSetting.Apply(MpvInitOption("volume", MpvSettingValue.IntValue(80), api = SettingApi.PROPERTY_INT), phase = BootstrapPhase.POST_INIT)
        )

        val latch = CountDownLatch(1)
        var probeResult: FinalProbeResult? = null

        MpvDiagnosticProbes.runFullBootstrapProbe(
            context = null,
            clientFactory = clientFactory,
            settings = settings,
            runGeneration = 4L
        ) { result ->
            probeResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(probeResult)
        assertTrue(probeResult!!.isPass)
        assertEquals("gpu", fakeClient.stringProperties["vo"])
        assertEquals(80, fakeClient.intProperties["volume"])
        assertTrue(fakeClient.isDestroyed)
        assertEquals(0, fakeClient.observers.size)
    }
}
