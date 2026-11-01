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
    companion object {
        const val ID = "cunny"
        val PROFILES = listOf(
            UpscalerProfile("fast", "Fast"),
            UpscalerProfile("balanced", "Balanced"),
            UpscalerProfile("quality", "Quality"),
        )
    }
}
