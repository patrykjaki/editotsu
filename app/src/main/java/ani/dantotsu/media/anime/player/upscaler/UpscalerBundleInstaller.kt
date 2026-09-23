package ani.dantotsu.media.anime.player.upscaler

import java.io.File
import java.security.MessageDigest

/**
 * Pure (unit-testable) bundle installation/validation logic for upscaler shader bundles.
 *
 * Fail-closed rules:
 *  - every manifest file must exist in the staged source with a matching sha256, else the whole
 *    bundle is rejected;
 *  - an installed bundle whose revision is OLDER than the available manifest is replaced;
 *  - a legacy Anime4K placeholder installation (`filesDir/shaders` + version.json) never survives
 *    as an active provider — [migrateLegacyAnime4KInstall] removes it.
 */
object UpscalerBundleInstaller {

    const val LEGACY_ANIME4K_DIR_NAME = "shaders"
    const val LEGACY_VERSION_FILE = "version.json"

    /**
     * Canonical provider-id contract (CP2 re-review): lowercase ASCII `[a-z0-9._-]`, non-blank,
     * no path separators, no `..` segments. The asset directory child name, the manifest
     * `provider` field, and the registered [UpscalerProvider.id] MUST all be this exact canonical
     * string — mismatches and non-canonical forms are rejected fail-closed (never normalized).
     */
    private val CANONICAL_PROVIDER_ID = Regex("^[a-z0-9._-]+$")

    /**
     * STRICT canonical provider-id validation — NEVER normalized (CP2 re-review round 3):
     * the input must already be lowercase ASCII `[a-z0-9._-]`, non-blank, with no path
     * separators and no `.`/`..` segments. No trimming, no case folding; anything else is
     * rejected. The asset directory child name, the manifest `provider` field, and the
     * registered [UpscalerProvider.id] MUST all be this exact canonical string.
     */
    fun canonicalProviderId(raw: String?): String? {
        val id = raw ?: return null
        if (id.isEmpty()) return null
        if (id.contains('/') || id.contains('\\')) return null
        if (id == "." || id == "..") return null
        return if (CANONICAL_PROVIDER_ID.matches(id)) id else null
    }

    private val CANONICAL_SHADER_FILE = Regex("^[a-zA-Z0-9._-]+$")

    /**
     * Canonical shader-payload filename contract (CP2 re-review round 3): BARE file names only —
     * `[a-zA-Z0-9._-]+`, no path separators, no `.`/`..`, and never `manifest.json`. Manifest
     * `files` keys, profile shader references, and [installedShaderPath] lookups are all bound to
     * this form, which confines every payload to its provider bundle directory.
     */
    fun canonicalShaderFileName(raw: String?): String? {
        val name = raw ?: return null
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        if (name.equals("manifest.json", ignoreCase = true)) return null
        return if (CANONICAL_SHADER_FILE.matches(name)) name else null
    }

    data class StagedBundle(
        val manifest: UpscalerBundleManifest,
        /** name → absolute path of the freshly staged file. */
        val files: Map<String, String>
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256OfBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Shared manifest-STRUCTURE validator (CP2 re-review round 4) — the single source of truth
     * for the bundle trust boundary, used identically at install time ([validate]) and at RUNTIME
     * ([isInstalledValid]). Verifies:
     *  1. canonical provider id;
     *  2. provider equality when [expectedProviderId] is given;
     *  3. positive bundleRevision;
     *  4. non-empty `files`;
     *  5. every file key is a canonical bare shader filename;
     *  6. every profile chain non-empty;
     *  7. every profile ref canonical AND a checksum-listed member of `files`.
     */
    fun validateManifestStructure(
        manifest: UpscalerBundleManifest,
        expectedProviderId: String? = null
    ): List<String> {
        val problems = mutableListOf<String>()
        if (canonicalProviderId(manifest.provider) == null) {
            problems.add("provider id is not canonical: '${manifest.provider}'")
        }
        if (expectedProviderId != null && manifest.provider != expectedProviderId) {
            problems.add("manifest provider '${manifest.provider}' != expected '$expectedProviderId'")
        }
        if (manifest.bundleRevision <= 0) problems.add("bundleRevision must be > 0")
        if (manifest.files.isEmpty()) problems.add("manifest lists no files")
        // Shader-payload sandbox: bare bundle-local file names only.
        for ((name, _) in manifest.files) {
            if (canonicalShaderFileName(name) == null) {
                problems.add("non-canonical shader file name: '$name'")
            }
        }
        // Every profile must be a non-empty chain of files that are checksum-listed members
        // of this bundle — no ghost references, no cross-provider escapes.
        for ((profileId, refs) in manifest.profiles) {
            if (refs.isEmpty()) problems.add("profile '$profileId' lists no files")
            for (ref in refs) {
                if (canonicalShaderFileName(ref) == null) {
                    problems.add("profile '$profileId' references non-canonical name: '$ref'")
                } else if (ref !in manifest.files) {
                    problems.add("profile '$profileId' references unlisted file: '$ref'")
                }
            }
        }
        return problems
    }

    /**
     * Validate a staged bundle: structural invariants first (shared validator), then the staged
     * file set must EXACTLY equal the checksum-listed manifest file set (CP2v4-02 — nothing may
     * enter the installed bundle that the manifest does not name and checksum), then per-file
     * existence/checksum verification. Returns list of problems (empty=ok).
     */
    fun validate(staged: StagedBundle): List<String> {
        val problems = validateManifestStructure(staged.manifest).toMutableList()
        if (staged.files.keys != staged.manifest.files.keys) {
            problems.add("staged file set does not exactly match manifest")
        }
        for ((name, expectedHash) in staged.manifest.files) {
            val path = staged.files[name]
                ?: run { problems.add("missing staged file: $name"); continue }
            val f = File(path)
            if (!f.exists() || f.length() == 0L) {
                problems.add("staged file empty/absent: $name")
                continue
            }
            val actual = sha256(f)
            if (!actual.equals(expectedHash, ignoreCase = true)) {
                problems.add("checksum mismatch for $name")
            }
        }
        return problems
    }

    /** Injectable move operation for deterministic swap-failure testing (CP2-05). */
    @Volatile
    internal var moveOperationForTesting: ((File, File) -> Boolean)? = null

    private fun move(src: File, dst: File): Boolean =
        moveOperationForTesting?.invoke(src, dst) ?: src.renameTo(dst)

    /**
     * Install a staged bundle applying the EDITOTSU REVISION POLICY (CP2-04):
     *
     *  1. no installed bundle              → install;
     *  2. installed manifest invalid       → repair/reinstall;
     *  3. bundled revision > installed     → replace;
     *  4. equal revision + valid install   → keep existing (no rewrite);
     *  5. equal revision + invalid install → repair/reinstall;
     *  6. bundled revision < installed     → refuse downgrade: true ONLY if the retained newer
     *     install is VALID, false otherwise (never a silent downgrade, never success on an
     *     invalid retained bundle — CP2 re-review finding 02).
     *
     * Replacement itself is failure-recoverable (CP2-05): the previous bundle is moved to a
     * backup before the new one is committed; any commit failure restores the backup, so a valid
     * installed provider is never destroyed by a failed upgrade.
     *
     * Returns true when, AFTER the call, `installDir` holds a valid bundle satisfying the policy
     * (either newly installed or intentionally kept).
     */
    fun install(staged: StagedBundle, installDir: File): Boolean {
        if (canonicalProviderId(staged.manifest.provider) == null) {
            android.util.Log.e("UpscalerBundles",
                "non-canonical provider id rejected: '${staged.manifest.provider}'")
            return false
        }
        val problems = validate(staged)
        if (problems.isNotEmpty()) {
            problems.forEach { android.util.Log.e("UpscalerBundles", "validation: $it") }
            return false
        }

        // ---- Revision policy against the currently installed bundle ----
        val installedRevision = installedRevision(installDir)
        val bundled = staged.manifest.bundleRevision
        val mustReinstallBecauseInvalid = installedRevision != null && installedRevision == -1 ||
                !isInstalledValid(installDir, staged.manifest.provider)
        when {
            installedRevision == null -> { /* fresh install */ }
            installedRevision == -1 -> { /* corrupt manifest → repair */ }
            bundled > installedRevision -> { /* upgrade */ }
            bundled == installedRevision -> {
                if (!mustReinstallBecauseInvalid) {
                    android.util.Log.i("UpscalerBundles",
                        "revision $bundled already installed; keeping existing bundle")
                    return true // satisfied without rewrite
                }
                /* equal revision but invalid → repair */
            }
            else -> {
                // Downgrade refusal (CP2 re-review finding 02): never silently downgrade, and
                // only report success when the retained newer bundle is actually VALID.
                val retainedValid = isInstalledValid(installDir, staged.manifest.provider)
                if (retainedValid) {
                    android.util.Log.i("UpscalerBundles",
                        "refusing downgrade $installedRevision -> $bundled; keeping valid newer bundle")
                    return true
                }
                android.util.Log.e("UpscalerBundles",
                    "refusing downgrade $installedRevision -> $bundled; retained install is INVALID")
                return false
            }
        }

        // ---- Stage validated copies ----
        val parent = installDir.parentFile ?: return false
        val stagingDir = File(parent, "${installDir.name}.staging-${System.nanoTime()}")

        try {
            stagingDir.mkdirs()
            // Copy by the MANIFEST set (CP2v4-02): the manifest is authoritative — validate()
            // already guarantees staged.files.keys == manifest.files.keys.
            for (name in staged.manifest.files.keys) {
                val srcPath = staged.files[name] ?: return false
                val ok = File(srcPath).copyTo(File(stagingDir, name), overwrite = true).length() > 0
                if (!ok) return false
            }
            val manifestJson = kotlinx.serialization.json.Json.encodeToString(
                UpscalerBundleManifest.serializer(),
                staged.manifest
            )
            File(stagingDir, "manifest.json").writeText(manifestJson)

            // ---- Recoverable swap: old -> backup, staging -> install, restore on failure ----
            var backupDir: File? = null
            if (installDir.exists()) {
                backupDir = File(parent, "${installDir.name}.backup-${System.nanoTime()}")
                if (!move(installDir, backupDir)) {
                    android.util.Log.e("UpscalerBundles", "could not back up old bundle")
                    stagingDir.deleteRecursively()
                    return false
                }
            }
            if (move(stagingDir, installDir)) {
                backupDir?.deleteRecursively()
                return true
            }
            // Commit failed → restore previous bundle.
            if (backupDir != null && move(backupDir, installDir)) {
                android.util.Log.e("UpscalerBundles", "swap failed; previous bundle restored")
            } else {
                android.util.Log.e("UpscalerBundles", "swap failed AND restore failed for ${installDir.name}")
            }
            stagingDir.deleteRecursively()
            return false
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    /**
     * True when `installDir` holds a VALID installed bundle for the given provider id.
     * Runtime trust boundary (CP2v4-01): enforces the SAME structural invariants as install-time
     * [validate] via the shared [validateManifestStructure] (canonical provider, provider
     * equality, canonical file keys, non-empty checksum-listed profiles with listed refs) BEFORE
     * verifying file existence/checksums. A tampered manifest can never make unlisted files
     * reachable through profile chains.
     */
    fun isInstalledValid(installDir: File, providerId: String): Boolean {
        val manifestFile = File(installDir, "manifest.json")
        if (!manifestFile.exists()) return false
        val manifest = try {
            kotlinx.serialization.json.Json.decodeFromString(
                UpscalerBundleManifest.serializer(),
                manifestFile.readText()
            )
        } catch (_: Exception) {
            return false
        }
        if (validateManifestStructure(manifest, expectedProviderId = providerId).isNotEmpty()) {
            return false
        }
        for ((name, expectedHash) in manifest.files) {
            val f = File(installDir, name)
            if (!f.exists() || f.length() == 0L) return false
            if (!sha256(f).equals(expectedHash, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * Currently installed manifest revision, or:
     *  - null  ⇒ nothing installed;
     *  - -1    ⇒ installed dir exists but its manifest is missing/corrupt (repair candidate).
     */
    fun installedRevision(installDir: File): Int? {
        val manifestFile = File(installDir, "manifest.json")
        if (!manifestFile.exists()) return null
        return try {
            kotlinx.serialization.json.Json
                .decodeFromString(UpscalerBundleManifest.serializer(), manifestFile.readText())
                .bundleRevision
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Absolute path of an installed shader file (for mpv glsl-shaders chains).
     * Shader-payload sandbox: only bare canonical bundle-local file names resolve —
     * path-like or unlisted-form names return null.
     */
    fun installedShaderPath(installDir: File, fileName: String): String? {
        if (canonicalShaderFileName(fileName) == null) return null
        val f = File(installDir, fileName)
        return if (f.exists() && f.length() > 0L) f.absolutePath else null
    }

    /**
     * Legacy migration (CP2 amendment): the pre-checkpoint Anime4K feature installed placeholder
     * stubs under `filesDir/shaders` keyed by an upstream-style version.json. That installation
     * must NEVER surface as an active provider — remove it wholesale.
     *
     * @return bytes removed (0 when nothing legacy existed).
     */
    fun migrateLegacyAnime4KInstall(filesDir: File): Long {
        val legacy = File(filesDir, LEGACY_ANIME4K_DIR_NAME)
        if (!legacy.exists()) return 0L
        var bytes = 0L
        legacy.walkTopDown().filter { it.isFile }.forEach { bytes += it.length() }
        val removed = legacy.deleteRecursively()
        File(filesDir, LEGACY_VERSION_FILE).delete()
        return if (removed) bytes else 0L
    }
}
