package ani.dantotsu.media.anime.player.upscaler

import java.io.File

/**
 * CuNNy (https://github.com/funnyplanter/CuNNy, LGPL-3.0) realtime 2x upscaler.
 *
 * Ships the standard (non-dp4a) SOFT shaders from APK assets
 * `upscaler_bundles/cunny/`; see NOTICE.txt there for revision and files.
 * dp4a variants need vo=gpu-next + Vulkan and are not bundled.
 *
 * Profile ids are stable pref values; the mapping to shader files lives in
 * the bundle manifest. The shaders only run when the output is at least
 * 1.3x the video size (their own WHEN condition); mpv's scaler resizes the
 * 2x result to the screen.
 */
class CunnyUpscalerProvider(
    bundleDirLookup: (providerId: String) -> File?
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
    override fun resolveShaderChain(profileId: String): String? {
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
        val PROFILES = listOf(
            UpscalerProfile("fast", "Fast"),
            UpscalerProfile("balanced", "Balanced"),
            UpscalerProfile("quality", "Quality"),
        )
    }
}
