package ani.dantotsu.media.anime.player.upscaler

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Pluggable real-time upscaler architecture (Checkpoint 2 amendment).
 *
 * The playback pipeline only ever consumes a resolved list of mpv GLSL shader paths; it never
 * knows which provider produced them. CuNNy (or any future provider) plugs in by implementing
 * [UpscalerProvider] and registering a validated shader bundle — no further engine changes.
 *
 * v0.2.4-alpha ships with NO bundled upscaler: the registry is empty, `UpscalerConfig` defaults
 * to `off`, and any non-off selection resolves fail-closed to "no shaders".
 */
interface UpscalerProvider {
    /** Stable provider id used by settings/prefs (e.g. "cunny"). */
    val id: String

    /** Human-readable display name. */
    val displayName: String

    /**
     * True when a VALIDATED bundle for this provider is installed (manifest revision matches,
     * every file present with matching checksum). A missing/corrupt asset means UNAVAILABLE,
     * never a degraded "Applied" state.
     */
    fun isAvailable(): Boolean

    /** Profiles exposed by this provider (e.g. Fast / Balanced / Quality). */
    fun profiles(): List<UpscalerProfile>

    /**
     * Resolve `profileId` to an mpv `glsl-shaders` chain (colon-separated absolute file paths).
     * Returns null when the profile is unknown or the bundle fails validation — callers must
     * treat null as "no upscaling", never as an error that interrupts playback.
     */
    fun resolveShaderChain(profileId: String): String?
}

data class UpscalerProfile(
    val id: String,
    val displayName: String
)

/** Result of resolving the configured upscaler against available providers. */
data class ResolvedUpscaler(
    val active: Boolean,
    val providerId: String?,
    val profileId: String?,
    val glslShaderChain: String
) {
    companion object {
        val OFF = ResolvedUpscaler(active = false, providerId = null, profileId = null, glslShaderChain = "")
    }
}

@Serializable
data class UpscalerBundleManifest(
    /** Editotsu-owned revision; bumping it replaces previously installed bundles. */
    val bundleRevision: Int,
    /** Provider id this bundle belongs to (e.g. "cunny"). */
    val provider: String,
    /** Upstream source identifier recorded for attribution/compliance (not used for upgrades). */
    val upstreamRevision: String = "",
    /** sha256 of every required shader file, keyed by file name. */
    val files: Map<String, String> = emptyMap(),
    /** profile id → ordered shader file names forming the mpv glsl-shaders chain. */
    val profiles: Map<String, List<String>> = emptyMap()
)

/**
 * Ready-made provider backed by an installed+validated bundle directory.
 * A future CuNNy integration is a ~5-line subclass of this.
 */
open class InstalledBundleUpscalerProvider(
    final override val id: String,
    final override val displayName: String,
    private val profileDefs: List<UpscalerProfile>,
    private val bundleDirLookup: (providerId: String) -> File?
) : UpscalerProvider {

    protected fun bundleDir(): File? = bundleDirLookup(id)

    override fun isAvailable(): Boolean =
        bundleDir()?.let { UpscalerBundleInstaller.isInstalledValid(it, id) } == true

    override fun profiles(): List<UpscalerProfile> =
        if (isAvailable()) profileDefs else emptyList()

    override fun resolveShaderChain(profileId: String): String? {
        val dir = bundleDir() ?: return null
        if (!UpscalerBundleInstaller.isInstalledValid(dir, id)) return null
        val manifest = try {
            kotlinx.serialization.json.Json.decodeFromString(
                UpscalerBundleManifest.serializer(),
                File(dir, "manifest.json").readText()
            )
        } catch (_: Exception) {
            return null
        }
        val files = manifest.profiles[profileId] ?: return null
        val paths = files.mapNotNull { UpscalerBundleInstaller.installedShaderPath(dir, it) }
        if (paths.size != files.size) return null // fail closed on any missing file
        return paths.joinToString(":")
    }
}
