package ani.dantotsu.media.anime.player.upscaler

import java.io.File

/**
 * CuNNy (https://github.com/funnyplanter/CuNNy, LGPL-3.0) realtime 2x upscaler.
 *
 * Ships the standard SOFT shaders and their dp4a (8-bit dot product) variants from APK assets
 * `upscaler_bundles/cunny/`; see NOTICE.txt there for revision and files. dp4a needs
 * vo=gpu-next + Vulkan, so the manifest's `<profile>@vulkan` chains are used only when
 * [useVulkan] says the Vulkan libmpv is active.
 *
 * Profile ids are stable pref values; the mapping to shader files lives in
 * the bundle manifest. The shaders only run when the output is at least
 * 1.3x the video size (their own WHEN condition); mpv's scaler resizes the
 * 2x result to the screen.
 */
class CunnyUpscalerProvider(
    bundleDirLookup: (providerId: String) -> File?,
    private val useVulkan: () -> Boolean = { false },
) : InstalledBundleUpscalerProvider(
    id = ID,
    displayName = "CuNNy",
    profileDefs = PROFILES,
    bundleDirLookup = bundleDirLookup
) {
    /**
     * Same chain as the base provider, but every shader is passed through [GlesShaderCompat]:
     * mpv gets a derived copy (in a sibling `cunny-gles-compat` dir, outside the checksummed
     * bundle) with the mid-body float16 `#extension` neutralised so `vo=gpu` on GLES compiles
     * it. Fails closed (null = no shaders) if a copy can't be written.
     */
    /** The larger models (dp4a only) are listed only while the Vulkan libmpv is in use. */
    override fun profiles(): List<UpscalerProfile> {
        val base = super.profiles()
        return if (base.isNotEmpty() && useVulkan()) base + VULKAN_PROFILES else base
    }

    override fun resolveShaderChain(profileId: String): String? {
        // Vulkan (libplacebo) runs the upstream files as they are: fp16 + dp4a, no GLES copy.
        if (useVulkan()) return super.resolveShaderChain("$profileId$VULKAN_SUFFIX")
        val chain = super.resolveShaderChain(profileId) ?: return null
        val dir = bundleDir() ?: return null
        val compatDir = File(dir.parentFile, "$ID-gles-compat")
        val paths = chain.split(":").map { path ->
            GlesShaderCompat.derivedCopy(File(path), compatDir)?.absolutePath ?: return null
        }
        return paths.joinToString(":")
    }

    companion object {
        const val ID = "cunny"
        const val VULKAN_SUFFIX = "@vulkan"
        val PROFILES = listOf(
            UpscalerProfile("fast", "Fast"),
            UpscalerProfile("balanced", "Balanced"),
            UpscalerProfile("quality", "Quality"),
        )

        /**
         * Vulkan only, in quality order (CuNNy README: 4x24 > 4x16 > 4x12 > 3x12 > 2x12 > fast).
         * Plain ids are SOFT; `-ds` is the denoise-and-sharpen variant. Until the phone
         * phone measurement picks the presets, every bundled model is listed.
         */
        val VULKAN_PROFILES = listOf("2x12", "3x12", "4x12", "4x16", "4x24").flatMap { model ->
            listOf(
                UpscalerProfile(model, model),
                UpscalerProfile("$model-ds", "$model Sharpen"),
            )
        }
    }
}
