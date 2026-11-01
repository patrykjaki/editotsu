package ani.dantotsu.media.anime.player

import ani.dantotsu.media.anime.player.upscaler.UpscalerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * CP2-06: ENGINE/INTEGRATION-level proof for the upscaler pipeline — the config must reach the
 * playback engine's mpv client (not merely resolve correctly), Off must send an EMPTY
 * `glsl-shaders` value, and ActiveVideoPipeline attach/apply/detach routing works.
 */
class VideoPipelineEngineIntegrationTest {

    private fun newEngine(provider: UpscalerProvider?): Pair<FakeMpvClient, MpvPlaybackEngine> {
        val fake = FakeMpvClient()
        val engine = MpvPlaybackEngine(
            null,
            object : MpvClientFactory {
                override fun createFresh(): MpvClient = fake
            },
            upscalerProviderLookup = { id: String ->
                if (provider != null && provider.id.equals(id, ignoreCase = true)) provider else null
            }
        )
        return fake to engine
    }

    /** CP2-06 #1: valid provider/config sends the resolved chain to mpv. */
    @Test
    fun testActiveUpscalerReachesMpvClient() = runBlocking(Dispatchers.Unconfined) {
        val (fake, engine) = newEngine(FakeUpscalerProvider(available = true))
        engine.applyVideoPipeline(
            VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        )

        assertEquals(
            "/bundles/cunny/fast.glsl",
            fake.stringProperties["glsl-shaders"]
        )
    }

    /** CP2-06 #2: subsequent Off config sends glsl-shaders="" to mpv (clears active shaders). */
    @Test
    fun testOffConfigClearsShadersAtEngine() = runBlocking(Dispatchers.Unconfined) {
        val (fake, engine) = newEngine(FakeUpscalerProvider(available = true))

        // Live: cunny/fast.
        engine.applyVideoPipeline(
            VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        )
        assertEquals("/bundles/cunny/fast.glsl", fake.stringProperties["glsl-shaders"])

        // Switch Off: engine MUST receive empty value, not merely resolve it.
        engine.applyVideoPipeline(
            VideoPipelineConfig(upscaler = UpscalerConfig.off())
        )
        assertEquals("", fake.stringProperties["glsl-shaders"])
    }

    /**
     * CP2-06 #3/#4: ActiveVideoPipeline routes to the ATTACHED engine and stops after detach.
     * (PlayerSettingsActivity's mutation call sites invoke refreshFromPrefs() — proven by the
     * unfiltered diff; this test proves the routing primitive those call sites rely on.)
     */
    @Test
    fun testActiveVideoPipelineAttachApplyDetachRouting() = runBlocking(Dispatchers.Unconfined) {
        val (fakeA, engineA) = newEngine(FakeUpscalerProvider(available = true))
        val (fakeB, engineB) = newEngine(null)

        ActiveVideoPipeline.attach(engineA)
        assertTrue(
            ActiveVideoPipeline.apply(
                VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
            )
        )
        assertEquals("/bundles/cunny/fast.glsl", fakeA.stringProperties["glsl-shaders"])

        // Re-attach to a different engine ⇒ routing follows the newest attachment.
        ActiveVideoPipeline.attach(engineB)
        assertTrue(ActiveVideoPipeline.apply(VideoPipelineConfig(upscaler = UpscalerConfig.off())))
        assertEquals("", fakeB.stringProperties["glsl-shaders"])

        // After detach: apply is a no-op returning false; neither engine receives anything more.
        ActiveVideoPipeline.detach(engineB)
        fakeA.stringProperties.clear()
        fakeB.stringProperties.clear()
        assertFalse(ActiveVideoPipeline.apply(VideoPipelineConfig(upscaler = UpscalerConfig.off())))
        assertFalse(fakeA.stringProperties.containsKey("glsl-shaders"))
        assertFalse(fakeB.stringProperties.containsKey("glsl-shaders"))
    }

    /** Audio night mode: on adds the labelled af filter once; off only removes it. */
    @Test
    fun testAudioNightModeTogglesLabelledAudioFilter() = runBlocking(Dispatchers.Unconfined) {
        val (fake, engine) = newEngine(null)
        val remove = listOf("af", "remove", VideoPipelineResolver.NIGHT_MODE_FILTER_NAME)
        val add = listOf("af", "add", VideoPipelineResolver.NIGHT_MODE_FILTER)

        engine.applyVideoPipeline(VideoPipelineConfig(audioNightMode = true))
        engine.applyVideoPipeline(VideoPipelineConfig(audioNightMode = true))
        // Re-applying never stacks: every add is preceded by a remove.
        assertEquals(listOf(remove, add, remove, add), fake.executedCommands.filter { it.firstOrNull() == "af" })

        fake.executedCommands.clear()
        engine.applyVideoPipeline(VideoPipelineConfig(audioNightMode = false))
        assertEquals(listOf(remove), fake.executedCommands.filter { it.firstOrNull() == "af" })
    }

    @Test
    fun testNightModePrefMappingDefaultsOff() {
        val off = VideoPipelinePrefsMapper.fromValues(
            debandMode = "None", brightness = 0, contrast = 0, saturation = 0, gamma = 0, hue = 0,
            sharpen = 0f, audioDelayMs = 0, subtitleDelayMs = 0, subtitleSpeed = 1f,
            volumeBoostCap = 30, upscalerProviderId = "off", upscalerProfileId = ""
        )
        assertFalse(off.audioNightMode)
        assertTrue(off.copy(audioNightMode = true).let { VideoPipelineResolver.resolve(it, null).nightModeAudioFilter != null })
        assertEquals(null, VideoPipelineResolver.resolve(off, null).nightModeAudioFilter)
    }
}
