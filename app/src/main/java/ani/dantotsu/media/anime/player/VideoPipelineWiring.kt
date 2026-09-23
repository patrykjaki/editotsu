package ani.dantotsu.media.anime.player

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Single authoritative mapping Player-preferences → [VideoPipelineConfig] (CP2 amendment).
 * Called at player initialization and on every live settings change.
 *
 * v0.2.4-alpha ships with the upscaler OFF and no providers registered; the mapping already
 * carries provider/profile ids so a future CuNNy bundle needs no wiring changes here beyond
 * exposing its selection UI.
 */
object VideoPipelinePrefsMapper {

    /**
     * `userHwdec` intentionally remains a fixed engine default ("auto"): the fork has no persisted
     * hardware-decoder preference yet; adding one is a settings-UI change, not a pipeline change.
     */
    fun fromPrefs(): VideoPipelineConfig = fromValues(
        debandMode = PrefManager.getVal<String>(PrefName.VideoDebandMode),
        debandIterations = PrefManager.getVal<Int>(PrefName.VideoDebandIterations),
        debandThreshold = PrefManager.getVal<Int>(PrefName.VideoDebandThreshold),
        debandRange = PrefManager.getVal<Int>(PrefName.VideoDebandRange),
        debandGrain = PrefManager.getVal<Int>(PrefName.VideoDebandGrain),
        brightness = PrefManager.getVal<Int>(PrefName.VideoBrightness),
        contrast = PrefManager.getVal<Int>(PrefName.VideoContrast),
        saturation = PrefManager.getVal<Int>(PrefName.VideoSaturation),
        gamma = PrefManager.getVal<Int>(PrefName.VideoGamma),
        hue = PrefManager.getVal<Int>(PrefName.VideoHue),
        sharpen = PrefManager.getVal<Float>(PrefName.VideoSharpen),
        audioDelayMs = PrefManager.getVal<Int>(PrefName.AudioDelayMs),
        subtitleDelayMs = PrefManager.getVal<Int>(PrefName.SubtitleDelayMs),
        subtitleSpeed = PrefManager.getVal<Float>(PrefName.SubtitleSpeed),
        volumeBoostCap = PrefManager.getVal<Int>(PrefName.VolumeBoostCap),
        upscalerProviderId = PrefManager.getVal<String>(PrefName.UpscalerProviderId),
        upscalerProfileId = PrefManager.getVal<String>(PrefName.UpscalerProfileId)
    )

    /** Pure mapping — unit-testable without Android preferences (CP2 amendment tests). */
    fun fromValues(
        debandMode: String,
        debandIterations: Int = DebandConfig().iterations,
        debandThreshold: Int = DebandConfig().threshold,
        debandRange: Int = DebandConfig().range,
        debandGrain: Int = DebandConfig().grain,
        brightness: Int,
        contrast: Int,
        saturation: Int,
        gamma: Int,
        hue: Int,
        sharpen: Float,
        audioDelayMs: Int,
        subtitleDelayMs: Int,
        subtitleSpeed: Float,
        volumeBoostCap: Int,
        upscalerProviderId: String,
        upscalerProfileId: String
    ): VideoPipelineConfig = VideoPipelineConfig(
        colorFilter = ColorFilterConfig(brightness, contrast, saturation, gamma, hue, sharpen),
        deband = DebandConfig(
            mode = when (debandMode) {
                "CPU" -> DebandMode.CPU
                "GPU" -> DebandMode.GPU
                else -> DebandMode.None
            },
            iterations = debandIterations,
            threshold = debandThreshold,
            range = debandRange,
            grain = debandGrain
        ),
        upscaler = UpscalerConfig(upscalerProviderId, upscalerProfileId),
        sync = AudioSubtitleSyncConfig(audioDelayMs, subtitleDelayMs, subtitleSpeed, volumeBoostCap)
    )
}

/**
 * Holds a WeakReference to the ACTIVE playback engine so settings screens can re-apply the
 * pipeline live without owning the player lifecycle (CP2 amendment: live re-application).
 */
object ActiveVideoPipeline {
    @Volatile private var engineRef: java.lang.ref.WeakReference<PlaybackEngine>? = null

    fun attach(engine: PlaybackEngine) {
        engineRef = java.lang.ref.WeakReference(engine)
    }

    fun detach(engine: PlaybackEngine) {
        if (engineRef?.get() === engine) engineRef = null
    }

    /** Re-reads preferences and pushes them to the live engine (no-op when detached/off). */
    fun refreshFromPrefs() {
        val engine = engineRef?.get() ?: return
        engine.applyVideoPipeline(VideoPipelinePrefsMapper.fromPrefs())
    }

    /** Test seam: apply an explicit config to whatever engine is attached. */
    fun apply(config: VideoPipelineConfig): Boolean {
        val engine = engineRef?.get() ?: return false
        engine.applyVideoPipeline(config)
        return true
    }
}
