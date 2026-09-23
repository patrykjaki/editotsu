package ani.dantotsu.media.anime.player

import ani.dantotsu.media.anime.player.upscaler.ResolvedUpscaler
import ani.dantotsu.media.anime.player.upscaler.UpscalerBundleInstaller
import ani.dantotsu.media.anime.player.upscaler.UpscalerProfile
import ani.dantotsu.media.anime.player.upscaler.UpscalerProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoPipelineUnitTest {

    @Test
    fun testPureResolverScalarProperties() {
        val config = VideoPipelineConfig(
            colorFilter = ColorFilterConfig(
                brightness = 10,
                contrast = 15,
                saturation = -20,
                gamma = 5,
                hue = -5,
                sharpen = 0.5f
            )
        )
        val resolved = VideoPipelineResolver.resolve(config)

        assertEquals(10, resolved.scalarProperties["brightness"])
        assertEquals(15, resolved.scalarProperties["contrast"])
        assertEquals(-20, resolved.scalarProperties["saturation"])
        assertEquals(5, resolved.scalarProperties["gamma"])
        assertEquals(-5, resolved.scalarProperties["hue"])
        assertEquals(0.5, resolved.scalarProperties["sharpen"])
        assertFalse(resolved.wasClamped)
    }

    @Test
    fun testDebandCpuIsolatedNamespace() {
        val config = VideoPipelineConfig(
            deband = DebandConfig(mode = DebandMode.CPU)
        )
        val resolved = VideoPipelineResolver.resolve(config)

        assertEquals("no", resolved.debandOptions["deband"])
        assertNotNull(resolved.debandVfCommand)
        assertEquals("add", resolved.debandVfCommand?.first)
        assertTrue(
            "Deband filter must use isolated namespace",
            resolved.debandVfCommand?.second?.contains("@editotsu_deband:gradfun") == true
        )
    }

    @Test
    fun testDebandGpuOptionFormatting() {
        val config = VideoPipelineConfig(
            deband = DebandConfig(
                mode = DebandMode.GPU,
                iterations = 3,
                threshold = 48,
                range = 24,
                grain = 64
            )
        )
        val resolved = VideoPipelineResolver.resolve(config)

        assertEquals("yes", resolved.debandOptions["deband"])
        assertEquals("3", resolved.debandOptions["deband-iterations"])
        assertEquals("48", resolved.debandOptions["deband-threshold"])
        assertEquals("24", resolved.debandOptions["deband-range"])
        assertEquals("64", resolved.debandOptions["deband-grain"])
        assertEquals("remove", resolved.debandVfCommand?.first)
        assertEquals("@editotsu_deband", resolved.debandVfCommand?.second)
    }

    /**
     * CP2: Off (default shipped state) resolves to NO shaders even when a provider is present.
     */
    @Test
    fun testUpscalerOffProducesNoShaders() {
        val config = VideoPipelineConfig(upscaler = UpscalerConfig.off())
        val provider = FakeUpscalerProvider(available = true)
        val resolved = VideoPipelineResolver.resolve(config, provider)

        assertFalse(resolved.upscalerActive)
        assertEquals("", resolved.glslShaderChain)
    }

    /** CP2: selecting a provider that is missing/unavailable must fail CLOSED to no shaders. */
    @Test
    fun testUpscalerUnavailableProviderFailsClosed() {
        val config = VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        val unavailable = FakeUpscalerProvider(available = false)
        var resolveAttempts = 0
        val provider = object : UpscalerProvider by unavailable {
            override fun isAvailable(): Boolean {
                resolveAttempts++
                return false
            }
        }
        val resolved = VideoPipelineResolver.resolve(config, provider)

        assertTrue(resolveAttempts >= 1)
        assertFalse(resolved.upscalerActive)
        assertEquals("", resolved.glslShaderChain)

        // And with NO provider at all:
        val resolvedNoProvider = VideoPipelineResolver.resolve(config, null)
        assertFalse(resolvedNoProvider.upscalerActive)
        assertEquals("", resolvedNoProvider.glslShaderChain)
    }

    @Test
    fun testPresetApplicationTemplateSemantics() {
        val vivid = VideoFilterTheme.Vivid.toColorFilterConfig()
        assertEquals(5, vivid.brightness)
        assertEquals(15, vivid.contrast)
        assertEquals(20, vivid.saturation)
        assertEquals(0.2f, vivid.sharpen)

        val cinema = VideoFilterTheme.Cinema.toColorFilterConfig()
        assertEquals(-5, cinema.brightness)
        assertEquals(15, cinema.contrast)
        assertEquals(-10, cinema.saturation)

        val vintage = VideoFilterTheme.Vintage.toColorFilterConfig()
        assertEquals(-30, vintage.saturation)
        assertEquals(-10, vintage.gamma)
    }

    @Test
    fun testResolverClampsOutOfRangeInput() {
        val corruptedConfig = VideoPipelineConfig(
            colorFilter = ColorFilterConfig(
                brightness = 500,  // out of -100..100
                contrast = -999,   // out of -100..100
                sharpen = 8.5f     // out of -1.0..1.0
            ),
            deband = DebandConfig(
                mode = DebandMode.GPU,
                iterations = 10,   // out of 1..4
                threshold = 200    // out of 0..100
            ),
            sync = AudioSubtitleSyncConfig(
                audioDelayMs = 99999, // out of -10000..10000
                subtitleSpeed = 50.0f  // out of 0.1..10.0
            )
        )
        val resolved = VideoPipelineResolver.resolve(corruptedConfig)

        assertTrue("Resolver must flag out-of-range inputs as clamped", resolved.wasClamped)
        assertEquals(100, resolved.scalarProperties["brightness"])
        assertEquals(-100, resolved.scalarProperties["contrast"])
        assertEquals(1.0, resolved.scalarProperties["sharpen"])
        assertEquals("4", resolved.debandOptions["deband-iterations"])
        assertEquals("100", resolved.debandOptions["deband-threshold"])
        assertEquals("10.0", resolved.syncProperties["audio-delay"])
        assertEquals("10.0", resolved.syncProperties["sub-speed"])
    }
}
