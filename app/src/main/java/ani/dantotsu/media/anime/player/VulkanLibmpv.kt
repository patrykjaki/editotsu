package ani.dantotsu.media.anime.player

/**
 * Whether this process may ask libmpv for gpu-api=vulkan.
 *
 * Only with the local Vulkan libmpv (`-Peditotsu.libmpvAar`, BuildConfig.LIBMPV_VULKAN). That aar
 * replaces only the arm64-v8a library; other ABIs keep the stock GLES-only one. mpv's Vulkan
 * context needs Vulkan 1.1 entry points that Android's loader exports only from API 28 (weak
 * references in that lib), so below 28 it stays on OpenGL.
 *
 * If the Vulkan output fails to start once ([markFailed]), the rest of this process stays on
 * OpenGL without the upscaler; the next app start tries Vulkan again.
 */
object VulkanLibmpv {
    /** This process runs the Vulkan-capable libmpv (whether or not Vulkan started). */
    val supported: Boolean by lazy {
        ani.dantotsu.BuildConfig.LIBMPV_VULKAN &&
            android.os.Build.VERSION.SDK_INT >= 28 &&
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    }

    @Volatile
    var failed: Boolean = false
        private set

    val available: Boolean get() = supported && !failed

    fun markFailed() {
        failed = true
    }

    /** mpv's MPV_LOG_LEVEL_ERROR; fatal (10) is lower. */
    private const val LOG_LEVEL_ERROR = 20

    private val VO_INIT_FAILURES = listOf(
        "Failed initializing any suitable GPU context",
        "Error opening/initializing the selected video_out",
    )

    /** True for the mpv log lines that mean the selected video output could not start. */
    fun isVoInitFailure(level: Int, text: String): Boolean =
        level <= LOG_LEVEL_ERROR && VO_INIT_FAILURES.any { text.contains(it) }
}
