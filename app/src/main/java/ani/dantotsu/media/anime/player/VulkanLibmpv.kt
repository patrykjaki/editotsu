package ani.dantotsu.media.anime.player

/**
 * Whether this process may ask libmpv for gpu-api=vulkan.
 *
 * Only with the local Vulkan libmpv (`-Peditotsu.libmpvAar`, BuildConfig.LIBMPV_VULKAN). That aar
 * replaces only the arm64-v8a library; other ABIs keep the stock GLES-only one. mpv's Vulkan
 * context needs Vulkan 1.1 entry points that Android's loader exports only from API 28 (weak
 * references in that lib), so below 28 it stays on OpenGL.
 */
object VulkanLibmpv {
    val available: Boolean by lazy {
        ani.dantotsu.BuildConfig.LIBMPV_VULKAN &&
            android.os.Build.VERSION.SDK_INT >= 28 &&
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    }
}
