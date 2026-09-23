package ani.dantotsu.media.anime.player.upscaler

import android.content.Context
import java.io.File

/**
 * Android-side registry of installed upscaler providers.
 *
 * v0.2.4-alpha ships with NO bundled upscaler assets: APK assets contain no
 * `upscaler_bundles/` directory, so [availableProviders] is empty and the only valid
 * `UpscalerConfig` is `off`. A future CuNNy integration ships:
 *  1. APK assets `upscaler_bundles/cunny/manifest.json` + shader files,
 *  2. a provider implementation registered via [register],
 * and requires NO changes to the engine, resolver, or config model.
 *
 * Unit-testable: construct directly with temp [filesDir]/[cacheDir] and a fake [AssetReader].
 */
class UpscalerBundleManager internal constructor(
    filesDir: File,
    private val cacheDir: File,
    private val assets: AssetReader
) {
    /**
     * Minimal asset abstraction for unit tests. CONTRACT (mirrors AssetManager.list):
     * [list] returns CHILD NAMES ONLY — never prefixed paths.
     */
    interface AssetReader {
        fun list(dir: String): List<String>
        fun readBytes(path: String): ByteArray
        fun stageTo(tempDir: File, path: String): File
    }

    class AndroidAssetReader(private val context: Context) : AssetReader {
        override fun list(dir: String): List<String> =
            try { context.assets.list(dir)?.toList() ?: emptyList() }
            catch (_: Exception) { emptyList() }

        override fun readBytes(path: String): ByteArray =
            context.assets.open(path).use { it.readBytes() }

        override fun stageTo(tempDir: File, path: String): File {
            tempDir.mkdirs()
            val out = File(tempDir, path.substringAfterLast('/'))
            context.assets.open(path).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return out
        }
    }

    private val filesDir: File = filesDir
    private val bundleRootAssetDir = "upscaler_bundles"
    private val installRoot = File(filesDir, "upscaler")

    private val providers = mutableListOf<UpscalerProvider>()

    init {
        // Legacy Anime4K placeholder migration (CP2 amendment): never an active provider.
        try { UpscalerBundleInstaller.migrateLegacyAnime4KInstall(filesDir) } catch (_: Exception) {}

        // Discover any APK-shipped bundles. With none packaged (v0.2.4-alpha) this is a no-op and
        // the registry stays empty ⇒ shipped upscaler state is OFF.
        try { refreshFromAssets() } catch (_: Exception) {}
    }

    companion object {
        @Volatile private var instance: UpscalerBundleManager? = null

        fun getInstance(context: Context): UpscalerBundleManager =
            instance ?: synchronized(this) {
                instance ?: UpscalerBundleManager(
                    filesDir = context.applicationContext.filesDir,
                    cacheDir = context.applicationContext.cacheDir,
                    assets = AndroidAssetReader(context.applicationContext)
                ).also { instance = it }
            }
    }

    /**
     * Register a provider implementation. Canonical-id contract: only providers whose
     * [UpscalerProvider.id] is already in strict canonical form are accepted — non-canonical
     * registrations are ignored fail-closed so the registry never holds a non-canonical id
     * (none exist in v0.2.4-alpha).
     */
    fun register(provider: UpscalerProvider) {
        if (UpscalerBundleInstaller.canonicalProviderId(provider.id) == null) {
            android.util.Log.e("UpscalerBundles",
                "refusing registration of non-canonical provider id '${provider.id}'")
            return
        }
        providers.add(provider)
    }

    /**
     * Providers with a currently VALID installed bundle. Validation is fail-closed:
     * missing/corrupt files ⇒ provider absent ⇒ resolver yields "no shaders".
     */
    fun availableProviders(): List<UpscalerProvider> =
        providers.filter { it.isAvailable() }

    fun providerFor(id: String): UpscalerProvider? {
        // Canonical-id contract (CP2 re-review finding 03): fail closed on non-canonical ids.
        val want = UpscalerBundleInstaller.canonicalProviderId(id) ?: return null
        return availableProviders().firstOrNull { it.id == want }
    }

    /**
     * Discover bundles shipped in APK assets (`upscaler_bundles/<provider>/manifest.json` +
     * shader files), validate them, and install/replace into `filesDir/upscaler/<provider>`
     * applying the revision policy of [UpscalerBundleInstaller.install].
     *
     * Canonical-id contract (CP2 re-review finding 03): the asset directory child name must be a
     * canonical provider id AND must EXACTLY equal the manifest `provider` field; the install
     * directory is derived from that one canonical id. Any mismatch or non-canonical form is
     * rejected fail-closed. With no bundled assets this is a no-op returning an empty list.
     */
    fun refreshFromAssets(): List<String> {
        val installed = mutableListOf<String>()

        // AssetReader.list returns CHILD NAMES ONLY (AssetManager contract).
        for (childName in assets.list(bundleRootAssetDir)) {
            val providerId = UpscalerBundleInstaller.canonicalProviderId(childName)
            if (providerId == null) {
                android.util.Log.w("UpscalerBundles",
                    "skipping non-canonical asset dir '$childName'")
                continue
            }
            val providerDir = "$bundleRootAssetDir/$providerId"
            try {
                val manifestEntry = assets.list(providerDir)
                    .firstOrNull { it.equals("manifest.json", ignoreCase = true) } ?: continue
                val manifestPath = "$providerDir/$manifestEntry"
                val manifest = kotlinx.serialization.json.Json.decodeFromString(
                    UpscalerBundleManifest.serializer(),
                    assets.readBytes(manifestPath).decodeToString()
                )

                // Folder/manifest identity check BEFORE any install side effects.
                if (UpscalerBundleInstaller.canonicalProviderId(manifest.provider) != providerId) {
                    android.util.Log.e("UpscalerBundles",
                        "manifest provider '${manifest.provider}' does not match folder '$providerId'; rejecting bundle")
                    continue
                }

                val stagingRoot = File(cacheDir, "upscaler_staging")
                stagingRoot.deleteRecursively()
                val stagedFiles = manifest.files.keys.associateWith { name ->
                    assets.stageTo(stagingRoot, "$providerDir/$name").absolutePath
                }
                val staged = UpscalerBundleInstaller.StagedBundle(manifest, stagedFiles)

                val installDir = File(installRoot, providerId)
                if (UpscalerBundleInstaller.install(staged, installDir)) {
                    installed.add(providerId)
                }
            } catch (e: Exception) {
                android.util.Log.e("UpscalerBundles", "bundle install failed for $providerDir: ${e.message}")
            } finally {
                File(cacheDir, "upscaler_staging").deleteRecursively()
            }
        }
        return installed
    }

    /** Installed (validated) bundle dir for a CANONICAL provider id, or null. */
    fun installedBundleDir(providerId: String): File? {
        val id = UpscalerBundleInstaller.canonicalProviderId(providerId) ?: return null
        val dir = File(installRoot, id)
        return if (UpscalerBundleInstaller.isInstalledValid(dir, id)) dir else null
    }
}
