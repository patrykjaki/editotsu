package ani.dantotsu.media.anime.player

import kotlinx.serialization.Serializable

@Serializable
enum class DebandMode {
    None,
    CPU,
    GPU
}

/**
 * Upscaler selection (CP2 amendment). v0.2.4-alpha ships with NO bundled providers: `off` is the
 * only meaningful value. Provider ids (e.g. "cunny") become valid once a validated bundle is
 * shipped AND its provider implementation is registered — until then any non-off selection
 * resolves fail-closed to "no shaders" and never interrupts playback.
 */
@Serializable
data class UpscalerConfig(
    val providerId: String = OFF_PROVIDER_ID,
    val profileId: String = ""
) {
    companion object {
        const val OFF_PROVIDER_ID = "off"
        fun off() = UpscalerConfig()
    }

    val isOff: Boolean get() = providerId == OFF_PROVIDER_ID || providerId.isBlank()
}

@Serializable
data class ColorFilterConfig(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
    val hue: Int = 0,
    val sharpen: Float = 0.0f
)

@Serializable
data class DebandConfig(
    val mode: DebandMode = DebandMode.None,
    val iterations: Int = 1,
    val threshold: Int = 32,
    val range: Int = 16,
    val grain: Int = 48
)

@Serializable
data class AudioSubtitleSyncConfig(
    val audioDelayMs: Int = 0,
    val subtitleDelayMs: Int = 0,
    val subtitleSpeed: Float = 1.0f,
    val volumeBoostCap: Int = 30
)

@Serializable
data class VideoPipelineConfig(
    val colorFilter: ColorFilterConfig = ColorFilterConfig(),
    val deband: DebandConfig = DebandConfig(),
    val upscaler: UpscalerConfig = UpscalerConfig(),
    val sync: AudioSubtitleSyncConfig = AudioSubtitleSyncConfig(),
    val userHwdec: String = "auto"
)

enum class VideoFilterTheme(
    val brightness: Int,
    val contrast: Int,
    val saturation: Int,
    val gamma: Int,
    val hue: Int,
    val sharpen: Float
) {
    Default(0, 0, 0, 0, 0, 0.0f),
    Vivid(5, 15, 20, 0, 0, 0.2f),
    Cinema(-5, 15, -10, 0, 0, 0.0f),
    Vintage(0, 0, -30, -10, -5, 0.0f);

    fun toColorFilterConfig(): ColorFilterConfig {
        return ColorFilterConfig(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            gamma = gamma,
            hue = hue,
            sharpen = sharpen
        )
    }
}
