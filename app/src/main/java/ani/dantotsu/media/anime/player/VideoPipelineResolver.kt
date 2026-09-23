package ani.dantotsu.media.anime.player

import ani.dantotsu.media.anime.player.upscaler.ResolvedUpscaler
import ani.dantotsu.media.anime.player.upscaler.UpscalerProvider

data class ResolvedVideoPipeline(
    val scalarProperties: Map<String, Any>,
    val debandOptions: Map<String, String>,
    val debandVfCommand: Pair<String, String>?,
    /** mpv glsl-shaders value: colon-separated file paths, or "" for none. */
    val glslShaderChain: String,
    val syncProperties: Map<String, String>,
    val recommendedHwdec: String,
    val wasClamped: Boolean,
    /** True when a non-off upscaler was actually applied (valid provider + validated bundle). */
    val upscalerActive: Boolean
)

object VideoPipelineResolver {
    const val DEBAND_FILTER_NAME = "@editotsu_deband"

    /**
     * Resolve the full pipeline. Upscaler handling is fail-closed (CP2 amendment):
     *  - `off`/blank selection → no shaders;
     *  - selected provider missing/unavailable/corrupt → NO shaders (never an error state).
     * The playback engine only ever receives the resolved chain string.
     */
    fun resolve(
        config: VideoPipelineConfig,
        upscalerProvider: UpscalerProvider? = null
    ): ResolvedVideoPipeline {
        var clamped = false

        fun clamp(value: Int, range: IntRange): Int =
            value.coerceIn(range.first, range.last).also { if (it != value) clamped = true }

        fun clampF(value: Float, min: Float, max: Float): Float =
            value.coerceIn(min, max).also { if (it != value) clamped = true }

        val b = clamp(config.colorFilter.brightness, -100..100)
        val c = clamp(config.colorFilter.contrast, -100..100)
        val sat = clamp(config.colorFilter.saturation, -100..100)
        val g = clamp(config.colorFilter.gamma, -100..100)
        val h = clamp(config.colorFilter.hue, -100..100)
        val sh = clampF(config.colorFilter.sharpen, -1.0f, 1.0f)

        val scalarProps = mapOf<String, Any>(
            "brightness" to b,
            "contrast" to c,
            "saturation" to sat,
            "gamma" to g,
            "hue" to h,
            "sharpen" to sh.toDouble()
        )

        // Deband resolution
        val debandOpts = mutableMapOf<String, String>()
        var debandVf: Pair<String, String>? = null

        when (config.deband.mode) {
            DebandMode.None -> {
                debandOpts["deband"] = "no"
                debandVf = Pair("remove", DEBAND_FILTER_NAME)
            }
            DebandMode.CPU -> {
                debandOpts["deband"] = "no"
                debandVf = Pair("add", "$DEBAND_FILTER_NAME:gradfun=radius=12")
            }
            DebandMode.GPU -> {
                val iter = clamp(config.deband.iterations, 1..4)
                val thresh = clamp(config.deband.threshold, 0..100)
                val rng = clamp(config.deband.range, 0..100)
                val grn = clamp(config.deband.grain, 0..100)

                debandOpts["deband"] = "yes"
                debandOpts["deband-iterations"] = iter.toString()
                debandOpts["deband-threshold"] = thresh.toString()
                debandOpts["deband-range"] = rng.toString()
                debandOpts["deband-grain"] = grn.toString()
                debandVf = Pair("remove", DEBAND_FILTER_NAME)
            }
        }

        // Upscaler resolution — fail-closed by contract of UpscalerProvider.resolveShaderChain.
        val resolvedUpscaler: ResolvedUpscaler = when {
            config.upscaler.isOff -> ResolvedUpscaler.OFF
            upscalerProvider == null || !upscalerProvider.isAvailable() -> ResolvedUpscaler.OFF
            else -> {
                val chain = runCatching {
                    upscalerProvider.resolveShaderChain(config.upscaler.profileId)
                }.getOrNull()
                if (chain.isNullOrBlank()) ResolvedUpscaler.OFF
                else ResolvedUpscaler(
                    active = true,
                    providerId = upscalerProvider.id,
                    profileId = config.upscaler.profileId,
                    glslShaderChain = chain
                )
            }
        }

        // Sync and volume
        val audioDelay = clamp(config.sync.audioDelayMs, -10000..10000)
        val subDelay = clamp(config.sync.subtitleDelayMs, -10000..10000)
        val subSpeed = clampF(config.sync.subtitleSpeed, 0.1f, 10.0f)
        val boost = clamp(config.sync.volumeBoostCap, 0..100)

        val syncProps = mapOf(
            "audio-delay" to (audioDelay / 1000.0).toString(),
            "sub-delay" to (subDelay / 1000.0).toString(),
            "sub-speed" to subSpeed.toString(),
            "volume-max" to (100 + boost).toString()
        )

        return ResolvedVideoPipeline(
            scalarProperties = scalarProps,
            debandOptions = debandOpts,
            debandVfCommand = debandVf,
            glslShaderChain = resolvedUpscaler.glslShaderChain,
            syncProperties = syncProps,
            recommendedHwdec = config.userHwdec,
            wasClamped = clamped,
            upscalerActive = resolvedUpscaler.active
        )
    }
}
