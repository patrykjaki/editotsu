package ani.dantotsu.media.anime.player

import ani.dantotsu.media.anime.player.upscaler.InstalledBundleUpscalerProvider
import ani.dantotsu.media.anime.player.upscaler.ResolvedUpscaler
import ani.dantotsu.media.anime.player.upscaler.UpscalerBundleInstaller
import ani.dantotsu.media.anime.player.upscaler.UpscalerBundleManager
import ani.dantotsu.media.anime.player.upscaler.UpscalerBundleManifest
import ani.dantotsu.media.anime.player.upscaler.UpscalerProfile
import ani.dantotsu.media.anime.player.upscaler.UpscalerProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Deterministic provider fixture for resolver tests. */
open class FakeUpscalerProvider(
    private val available: Boolean,
    override val id: String = "cunny",
    private val chain: String = "/bundles/cunny/fast.glsl"
) : UpscalerProvider {
    override val displayName: String get() = id
    override fun isAvailable(): Boolean = available
    override fun profiles(): List<UpscalerProfile> =
        if (available) listOf(UpscalerProfile("fast", "Fast")) else emptyList()
    override fun resolveShaderChain(profileId: String): String? =
        if (available && profileId == "fast") chain else null
}

/**
 * CP2 amendment test suite — generic upscaler architecture, fail-closed bundle validation,
 * legacy Anime4K migration, and Off behavior.
 */
class UpscalerPipelineTest {

    private lateinit var tempDir: File
    private lateinit var filesRoot: File

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("upscaler_test").toFile()
        filesRoot = Files.createTempDirectory("upscaler_filesroot").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        filesRoot.deleteRecursively()
    }

    // ---------- helpers ----------

    private val shaderBytesA = "GLSL_A_CONTENT".toByteArray()
    private val shaderBytesB = "GLSL_B_CONTENT".toByteArray()

    private fun stagedManifestJson(revision: Int, hashA: String, hashB: String): String {
        return "{\"bundleRevision\":$revision," +
            "\"provider\":\"cunny\"," +
            "\"upstreamRevision\":\"deadbeef\"," +
            "\"files\":{\"fast.glsl\":\"$hashA\",\"fast_b.glsl\":\"$hashB\"}," +
            "\"profiles\":{\"fast\":[\"fast.glsl\",\"fast_b.glsl\"]}}"
    }

    /** Stage shader files + manifest into a source dir; returns manifest + staged file paths. */
    private fun stageBundle(
        revision: Int,
        corruptA: Boolean = false,
        contentOverrideA: String? = null
    ): Pair<String, Map<String, String>> {
        val srcDir = Files.createTempDirectory("staged").toFile()

        // Write shader A (optionally overridden AFTER corruption decision), then hash from bytes.
        val fileA = File(srcDir, "fast.glsl")
        fileA.writeBytes(if (corruptA) "CORRUPT_A".toByteArray() else shaderBytesA)
        if (!corruptA && contentOverrideA != null) {
            fileA.writeText(contentOverrideA)
        }
        File(srcDir, "fast_b.glsl").writeBytes(shaderBytesB)

        val hashA = if (corruptA) "deadbeef" else UpscalerBundleInstaller.sha256(fileA)
        val hashB = UpscalerBundleInstaller.sha256(File(srcDir, "fast_b.glsl"))
        val manifestPath = File(srcDir, "manifest.json").apply {
            writeText(stagedManifestJson(revision, hashA, hashB))
        }.absolutePath

        return Pair(manifestPath, mapOf(
            "fast.glsl" to fileA.absolutePath,
            "fast_b.glsl" to File(srcDir, "fast_b.glsl").absolutePath
        ))
    }

    private fun parseManifest(path: String) =
        kotlinx.serialization.json.Json.decodeFromString(
            UpscalerBundleManifest.serializer(),
            File(path).readText()
        )

    private fun installValidBundle(revision: Int): File {
        val (manifestPath, files) = stageBundle(revision)
        val installDir = File(tempDir, "upscaler/cunny")
        assertTrue(
            UpscalerBundleInstaller.install(
                UpscalerBundleInstaller.StagedBundle(parseManifest(manifestPath), files),
                installDir
            )
        )
        return installDir
    }

    private fun cunnyProvider(): UpscalerProvider =
        InstalledBundleUpscalerProvider(
            id = "cunny",
            displayName = "CuNNy",
            profileDefs = listOf(UpscalerProfile("fast", "Fast")),
            bundleDirLookup = { File(tempDir, "upscaler/$it") }
        )

    // ---------- installer validation ----------

    @Test
    fun testInstallValidBundleSucceedsAndValidates() {
        val installDir = installValidBundle(revision = 1)
        assertTrue(UpscalerBundleInstaller.isInstalledValid(installDir, "cunny"))
        assertNotNull(UpscalerBundleInstaller.installedShaderPath(installDir, "fast.glsl"))
    }

    @Test
    fun testInstallRejectsCorruptChecksum() {
        val (manifestPath, files) = stageBundle(revision = 1, corruptA = true)
        val installDir = File(tempDir, "upscaler/cunny")
        assertFalse(
            UpscalerBundleInstaller.install(
                UpscalerBundleInstaller.StagedBundle(parseManifest(manifestPath), files),
                installDir
            )
        )
        assertFalse(installDir.exists())
    }

    @Test
    fun testCorruptInstalledFileFailsClosed() {
        val installDir = installValidBundle(revision = 1)

        // Corrupt an installed file post-install.
        File(installDir, "fast.glsl").writeText("CORRUPTED")
        assertFalse(UpscalerBundleInstaller.isInstalledValid(installDir, "cunny"))

        val provider = cunnyProvider()
        assertFalse(provider.isAvailable())
        assertNull(provider.resolveShaderChain("fast"))
        assertNull(provider.resolveShaderChain("unknown-profile"))
    }

    /**
     * CP2-04: the EDITOTSU REVISION POLICY matrix, exercised against a real install dir.
     * Sentinel content distinguishes "kept existing" from "replaced".
     */
    @Test
    fun testCpV402RevisionPolicyMatrix() {
        // 1) no install + rev1 → installs
        val dir = File(tempDir, "upscaler/cunny")
        val (mf1, f1) = stageBundle(revision = 1)
        assertTrue(install(mf1, f1, dir))
        val rev1Content = File(dir, "fast.glsl").readText()
        assertTrue(rev1Content.isNotEmpty())

        // 2) valid rev1 + bundled rev1 → keep existing (no rewrite; sentinel survives)
        File(dir, "sentinel.txt").writeText("keep-me")
        val (mf1b, f1b) = stageBundle(revision = 1, contentOverrideA = "SHOULD_NOT_APPEAR")
        assertTrue(install(mf1b, f1b, dir))
        assertEquals("Existing valid same-revision bundle must be kept",
            rev1Content, File(dir, "fast.glsl").readText())
        assertEquals("Sentinel must survive no-rewrite path", "keep-me", File(dir, "sentinel.txt").readText())

        // 3) rev1 + bundled rev2 → replaces
        val (mf2, f2) = stageBundle(revision = 2, contentOverrideA = "REV2_FAST")
        assertTrue(install(mf2, f2, dir))
        assertEquals("REV2_FAST", File(dir, "fast.glsl").readText())
        assertFalse(File(dir, "sentinel.txt").exists()) // rewrite happened

        // 4) rev2 + bundled rev1 → refuses downgrade, keeps newer
        val (mf1c, f1c) = stageBundle(revision = 1, contentOverrideA = "REV1_FAST")
        assertTrue(install(mf1c, f1c, dir))
        assertEquals("Downgrade must be refused", "REV2_FAST", File(dir, "fast.glsl").readText())

        // 5) corrupt rev2 + bundled rev2 → repairs/reinstalls
        File(dir, "fast.glsl").writeText("CORRUPTED")
        val (mf2b, f2b) = stageBundle(revision = 2, contentOverrideA = "REV2_FAST")
        assertTrue(install(mf2b, f2b, dir))
        assertEquals("REV2_FAST", File(dir, "fast.glsl").readText())
    }

    private fun install(manifestPath: String, files: Map<String,String>, dir: File): Boolean =
        UpscalerBundleInstaller.install(
            UpscalerBundleInstaller.StagedBundle(parseManifest(manifestPath), files), dir
        )

    /**
     * CP2-05: failed commit during replacement RESTORES the previous valid bundle.
     */
    @Test
    fun testSwapFailureRestoresPreviousBundle() {
        val dir = installValidBundle(revision = 1)
        val oldContent = File(dir, "fast.glsl").readText()

        // CP2 re-review finding 01: the hook DELEGATES real moves and fails ONLY the final
        // staging→install commit, so the test genuinely executes backup → failed commit → restore.
        var sawBackup = false
        var sawCommitFailure = false
        var sawRestore = false
        UpscalerBundleInstaller.moveOperationForTesting = { src, dst ->
            when {
                src == dir -> {
                    sawBackup = true
                    src.renameTo(dst)
                }
                src.name.startsWith("cunny.staging") && dst == dir -> {
                    sawCommitFailure = true
                    false
                }
                src.name.startsWith("cunny.backup") && dst == dir -> {
                    sawRestore = true
                    src.renameTo(dst)
                }
                else -> src.renameTo(dst)
            }
        }
        try {
            val (mf2, files2) = stageBundle(revision = 2, contentOverrideA = "REV2_FAST")
            assertFalse(
                "Commit failure must be reported",
                install(mf2, files2, dir)
            )
            assertTrue("backup move must have run", sawBackup)
            assertTrue("commit move must have been attempted and failed", sawCommitFailure)
            assertTrue("restore move must have run", sawRestore)

            assertEquals("Previous valid bundle must be restored verbatim",
                oldContent, File(dir, "fast.glsl").readText())
            assertTrue(UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))

            // No staging dir survives (cleaned in finally), no backup remains after restore.
            val parent = dir.parentFile!!
            assertTrue(parent.listFiles()!!.none { it.name.startsWith("cunny.staging") })
            assertTrue(parent.listFiles()!!.none { it.name.startsWith("cunny.backup") })
        } finally {
            UpscalerBundleInstaller.moveOperationForTesting = null
        }
    }

    private fun String.nameContains(needle: String) = this.contains(needle)

    /**
     * CP2 amendment test #5: a legacy Anime4K placeholder installation must NOT survive as an
     * active provider — migration removes it wholesale.
     */
    @Test
    fun testLegacyAnime4KPlaceholderIsRemovedByMigration() {
        val legacyShaders = File(filesRoot, "shaders")
        legacyShaders.mkdirs()

        // Exactly what the pre-CP2 build installed: stub shaders + upstream version.json.
        File(legacyShaders, "Anime4K_Clamp_Highlights.glsl")
            .writeText("//!HOOK MAIN\n//!BIND HOOKED\nvec4 hook(){return HOOKED_tex(HOOKED_pos);}")
        File(legacyShaders, "version.json").writeText("{\"version\":\"4.0.1\",\"shaders\":[]}")
        val bytesBefore = legacyShaders.walkTopDown().filter { it.isFile }
            .map { it.length() }.sum()

        val removed = UpscalerBundleInstaller.migrateLegacyAnime4KInstall(filesRoot)

        assertTrue(bytesBefore > 0L)
        assertEquals(bytesBefore, removed)
        assertFalse("Legacy placeholder dir must be wiped", legacyShaders.exists())
        assertFalse(File(filesRoot, "version.json").exists())
    }

    // ---------- resolver integration with providers ----------

    /** CP2 amendment test #1/#8: Off produces NO shaders even when a provider is present. */
    @Test
    fun testResolverOffProducesNoShadersDespiteAvailableProvider() {
        val configOff = VideoPipelineConfig(upscaler = UpscalerConfig.off())
        val resolved = VideoPipelineResolver.resolve(configOff, FakeUpscalerProvider(available = true))

        assertEquals(ResolvedUpscaler.OFF.glslShaderChain, resolved.glslShaderChain)
        assertFalse(resolved.upscalerActive)
    }

    /**
     * CP2 amendment test #2: a selected VALID provider resolves the expected validated chain.
     * Uses a real installed bundle through [InstalledBundleUpscalerProvider].
     */
    @Test
    fun testResolverWithValidBundleResolvesExpectedChain() {
        val dir = installValidBundle(revision = 1)
        val provider = cunnyProvider()
        assertTrue(provider.isAvailable())

        val config = VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        val resolved = VideoPipelineResolver.resolve(config, provider)

        assertTrue(resolved.upscalerActive)
        assertEquals(dir.resolve("fast.glsl").absolutePath, resolved.glslShaderChain.split(":")[0])
        assertEquals(dir.resolve("fast_b.glsl").absolutePath, resolved.glslShaderChain.split(":")[1])
    }

    /** CP2 amendment tests #3/#9: missing bundle / unknown profile resolve fail-closed. */
    @Test
    fun testResolverUnknownProfileAndMissingBundleFailClosed() {
        // Unknown profile against an available provider.
        installValidBundle(revision = 1)
        val provider = cunnyProvider()
        val cfgUnknown = VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "does-not-exist"))
        val r1 = VideoPipelineResolver.resolve(cfgUnknown, provider)
        assertFalse(r1.upscalerActive)
        assertEquals("", r1.glslShaderChain)

        // No installed bundle at all.
        val cfgMissing = VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        val missingManagerLookup: (String) -> File? = { null }
        val missingProvider = InstalledBundleUpscalerProvider(
            id = "cunny", displayName = "CuNNy",
            profileDefs = listOf(UpscalerProfile("fast", "Fast")),
            bundleDirLookup = missingManagerLookup
        )
        val r2 = VideoPipelineResolver.resolve(cfgMissing, missingProvider)
        assertFalse(r2.upscalerActive)
        assertEquals("", r2.glslShaderChain)
    }

    // ---------- prefs mapping ----------

    /** CP2 amendment test #6: preference values → VideoPipelineConfig mapping is faithful. */
    @Test
    fun testPrefsMappingProducesFaithfulConfig() {
        val config = VideoPipelinePrefsMapper.fromValues(
            debandMode = "CPU",
            brightness = 10,
            contrast = -5,
            saturation = 30,
            gamma = 7,
            hue = -3,
            sharpen = 0.25f,
            audioDelayMs = 120,
            subtitleDelayMs = -80,
            subtitleSpeed = 1.25f,
            volumeBoostCap = 15,
            upscalerProviderId = UpscalerConfig.OFF_PROVIDER_ID,
            upscalerProfileId = ""
        )

        assertEquals(DebandMode.CPU, config.deband.mode)
        assertEquals(10, config.colorFilter.brightness)
        assertEquals(-5, config.colorFilter.contrast)
        assertEquals(30, config.colorFilter.saturation)
        assertEquals(7, config.colorFilter.gamma)
        assertEquals(-3, config.colorFilter.hue)
        assertEquals(0.25f, config.colorFilter.sharpen)
        assertEquals(120, config.sync.audioDelayMs)
        assertEquals(-80, config.sync.subtitleDelayMs)
        assertEquals(1.25f, config.sync.subtitleSpeed)
        assertEquals(15, config.sync.volumeBoostCap)
        assertTrue("Shipped state: upscaler OFF", config.upscaler.isOff)
    }

    /**
     * CP1v5-03-style regression retained for CP2: switching Off after a live upscaler must CLEAR
     * the shader chain (engine receives empty glsl-shaders ⇒ previous shaders never outlive Off).
     */
    @Test
    fun testSwitchingToOffClearsPreviouslyResolvedShaders() {
        val hash = th("offswitch")
        val dir = installValidBundle(revision = 1)

        // Live state: cunny/fast active.
        val liveConfig = VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast"))
        val live = VideoPipelineResolver.resolve(liveConfig, cunnyProvider())
        assertTrue(live.upscalerActive)
        assertTrue(File(dir, "fast.glsl").exists())
        assertTrue(hash.isNotEmpty()) // deterministic fixture guard

        // Switch to Off.
        val offConfig = VideoPipelineConfig(upscaler = UpscalerConfig.off())
        val off = VideoPipelineResolver.resolve(offConfig, cunnyProvider())
        assertFalse(off.upscalerActive)
        assertEquals("", off.glslShaderChain)
    }

    /** Deterministic valid 40-hex info-hash fixture helper (shared convention from CP1). */
    private fun th(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.take(20).joinToString("") { "%02X".format(it) }
    }

    // ---------- CP2 review fixes ----------

    /** Minimal fake AssetReader mirroring AssetManager.list() child-names-only semantics. */
    private class FakeAssetReader(
        private val tree: Map<String, List<String>>,
        private val fileBytes: Map<String, ByteArray>
    ) : UpscalerBundleManager.AssetReader {
        override fun list(dir: String): List<String> = tree[dir] ?: emptyList()
        override fun readBytes(path: String): ByteArray = fileBytes.getValue(path)
        override fun stageTo(tempDir: File, path: String): File {
            tempDir.mkdirs()
            val out = File(tempDir, path.substringAfterLast('/'))
            out.writeBytes(fileBytes.getValue(path))
            return out
        }
    }

    /**
     * CP2-02: production asset-discovery path works end-to-end against child-names-only listing:
     * manifest read from correct path, shaders staged from correct paths, valid bundle installed,
     * malformed bundle fails closed, registered provider resolves full chain.
     */
    @Test
    fun testCpV601ManagerAssetDiscoveryEndToEnd() {
        val filesRoot = Files.createTempDirectory("cp2601_files").toFile()
        val cacheRoot = Files.createTempDirectory("cp2601_cache").toFile()

        val bytesFast = "CUNNY_FAST".toByteArray()
        val bytesAux = "CUNNY_AUX".toByteArray()
        val hashFast = UpscalerBundleInstaller.sha256OfBytes(bytesFast)
        val hashAux = UpscalerBundleInstaller.sha256OfBytes(bytesAux)

        val manifestJson = """
            {"bundleRevision":1,"provider":"cunny","upstreamRevision":"abc",
             "files":{"fast.glsl":"$hashFast","aux.glsl":"$hashAux"},
             "profiles":{"fast":["fast.glsl","aux.glsl"]}}
        """.trimIndent()

        val tree = mapOf(
            "upscaler_bundles" to listOf("cunny", "broken"),
            "upscaler_bundles/cunny" to listOf("manifest.json", "fast.glsl", "aux.glsl"),
            "upscaler_bundles/broken" to listOf("manifest.json")
        )
        val fileBytes = mapOf(
            "upscaler_bundles/cunny/manifest.json" to manifestJson.toByteArray(),
            "upscaler_bundles/cunny/fast.glsl" to bytesFast,
            "upscaler_bundles/cunny/aux.glsl" to bytesAux,
            "upscaler_bundles/broken/manifest.json" to
                """{"bundleRevision":1,"provider":"broken","files":{"missing.glsl":"00"},"profiles":{}}"""
                    .toByteArray()
        )
        val reader = FakeAssetReader(tree, fileBytes)

        val manager = UpscalerBundleManager(filesRoot, cacheRoot, reader)

        assertEquals(listOf("cunny"), manager.refreshFromAssets())

        val installedDir = File(filesRoot, "upscaler/cunny")
        assertTrue(installedDir.isDirectory)
        assertTrue(UpscalerBundleInstaller.isInstalledValid(installedDir, "cunny"))
        assertEquals("CUNNY_FAST", File(installedDir, "fast.glsl").readText())
        assertFalse(File(filesRoot, "upscaler/broken").exists())

        val provider = InstalledBundleUpscalerProvider(
            id = "cunny", displayName = "CuNNy",
            profileDefs = listOf(UpscalerProfile("fast", "Fast")),
            bundleDirLookup = { manager.installedBundleDir(it) }
        )
        manager.register(provider)
        assertTrue(provider.isAvailable())
        val chain = provider.resolveShaderChain("fast")!!
        assertEquals(
            listOf(
                File(installedDir, "fast.glsl").absolutePath,
                File(installedDir, "aux.glsl").absolutePath
            ),
            chain.split(":")
        )
    }

    /** CP2-03: mapper passes NON-DEFAULT deband params AND generic provider/profile through. */
    @Test
    fun testPrefsMappingDebandParamsAndGenericUpscalerPassThrough() {
        val config = VideoPipelinePrefsMapper.fromValues(
            debandMode = "GPU",
            debandIterations = 3,
            debandThreshold = 55,
            debandRange = 21,
            debandGrain = 9,
            brightness = -7,
            contrast = 12,
            saturation = 4,
            gamma = -2,
            hue = 6,
            sharpen = -0.5f,
            audioDelayMs = 250,
            subtitleDelayMs = -125,
            subtitleSpeed = 0.75f,
            volumeBoostCap = 50,
            upscalerProviderId = "cunny",
            upscalerProfileId = "quality"
        )

        assertEquals(DebandMode.GPU, config.deband.mode)
        assertEquals(3, config.deband.iterations)
        assertEquals(55, config.deband.threshold)
        assertEquals(21, config.deband.range)
        assertEquals(9, config.deband.grain)
        assertEquals(-7, config.colorFilter.brightness)
        assertEquals("cunny", config.upscaler.providerId)
        assertEquals("quality", config.upscaler.profileId)
        assertFalse(config.upscaler.isOff)
    }

    /**
     * CP2 re-review finding 02 regression: downgrade is never performed, and when the retained
     * newer install is INVALID the installer must report FALSE (no silent success on an invalid
     * retained bundle).
     */
    @Test
    fun testCpV202DowngradeRefusalTruthfulOnInvalidRetainedBundle() {
        val dir = installValidBundle(revision = 2)

        // Corrupt the retained rev2 payload (content no longer matches checksums).
        File(dir, "fast.glsl").writeText("CORRUPTED_REV2")
        assertFalse(UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))

        // Attempt to install an OLDER rev1: policy refuses the downgrade…
        val (mf1, files1) = stageBundle(revision = 1)
        assertFalse(
            "Downgrade onto an invalid newer install must NOT report success",
            install(mf1, files1, dir)
        )
        // …the corrupt rev2 payload is left untouched (still invalid, still not downgraded).
        assertEquals("CORRUPTED_REV2", File(dir, "fast.glsl").readText())
        assertFalse(UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))
    }

    /** CP2 re-review finding 03: canonical provider-id contract — accepted/rejected forms. */
    @Test
    fun testCpV203CanonicalProviderIdAcceptedAndRejectedForms() {
        // Canonical ids are accepted (trimmed).
        assertEquals("cunny", UpscalerBundleInstaller.canonicalProviderId("cunny"))
        assertEquals("ok.id-1", UpscalerBundleInstaller.canonicalProviderId("ok.id-1"))
        // Rejected: null/blank, mixed case, path separators, dot segments.
        // STRICT / never normalized (CP2v3-01): whitespace is NOT trimmed — rejected as-is.
        listOf(null, "", "   ", " ok.id-1 ", "ok.id-1\t", "CuNNy", "a/b", "a\\b", ".", "..").forEach {
            assertNull("must reject: $it", UpscalerBundleInstaller.canonicalProviderId(it))
        }
    }

    /**
     * CP2 re-review finding 03: manager enforces folder/manifest identity + canonical ids
     * end-to-end — mismatches and non-canonical names fail closed with NO install side effects;
     * lookups for non-canonical ids return null.
     */
    @Test
    fun testCpV203ManagerRejectsIdentityMismatchAndNonCanonicalNames() {
        val filesRoot = Files.createTempDirectory("cp203_files").toFile()
        val cacheRoot = Files.createTempDirectory("cp203_cache").toFile()

        val bytes = "SHADER".toByteArray()
        val hash = UpscalerBundleInstaller.sha256OfBytes(bytes)

        fun manifestFor(provider: String) =
            """{"bundleRevision":1,"provider":"$provider",
                "files":{"s.glsl":"$hash"},"profiles":{"fast":["s.glsl"]}}""".trimIndent()

        val tree = mapOf(
            // Mixed-case asset dir name → non-canonical → skipped before anything is read.
            "upscaler_bundles" to listOf("CuNNy", "cunny"),
            "upscaler_bundles/CuNNy" to listOf("manifest.json"),
            "upscaler_bundles/cunny" to listOf("manifest.json")
        )
        val fileBytes = mapOf(
            // Canonical folder whose manifest claims a DIFFERENT provider id → rejected.
            "upscaler_bundles/cunny/manifest.json" to manifestFor("other").toByteArray(),
            "upscaler_bundles/CuNNy/manifest.json" to manifestFor("cunny").toByteArray()
        )
        val reader = FakeAssetReader(tree, fileBytes)
        val manager = UpscalerBundleManager(filesRoot, cacheRoot, reader)

        // Nothing installs; neither 'other' nor 'cunny' surfaces as installed.
        assertTrue(manager.refreshFromAssets().isEmpty())
        assertFalse(File(filesRoot, "upscaler/cunny").exists())
        assertFalse(File(filesRoot, "upscaler/other").exists())
        assertNull(manager.installedBundleDir("cunny"))

        // Non-canonical LOOKUP ids fail closed too.
        assertNull(manager.installedBundleDir("CuNNy"))
        assertNull(manager.installedBundleDir("../evil"))

        // Sanity: a matching canonical bundle DOES install through the same path.
        val goodTree = mapOf(
            "upscaler_bundles" to listOf("CuNNy", "cunny", "good"),
            "upscaler_bundles/CuNNy" to listOf("manifest.json"),
            "upscaler_bundles/cunny" to listOf("manifest.json"),
            "upscaler_bundles/good" to listOf("manifest.json")
        )
        val goodBytes = fileBytes +
            ("upscaler_bundles/good/manifest.json" to manifestFor("good").toByteArray()) +
            ("upscaler_bundles/good/s.glsl" to bytes)
        val goodReader = FakeAssetReader(goodTree, goodBytes)
        val goodManager = UpscalerBundleManager(
            Files.createTempDirectory("cp203_files2").toFile(), cacheRoot, goodReader
        )
        assertEquals(listOf("good"), goodManager.refreshFromAssets())

        filesRoot.deleteRecursively(); cacheRoot.deleteRecursively()
    }

    /**
     * CP2v3-01 regression: manifest.provider with surrounding whitespace must NOT pass the
     * folder/manifest identity check via trimming — no install side effects, lookup null.
     */
    @Test
    fun testCpV301WhitespaceProviderIdIsNeverNormalized() {
        val filesRoot = Files.createTempDirectory("cp301_files").toFile()
        val cacheRoot = Files.createTempDirectory("cp301_cache").toFile()
        val bytes = "SHADER".toByteArray()
        val hash = UpscalerBundleInstaller.sha256OfBytes(bytes)
        // Folder "good" but manifest declares " good ".
        val manifestJson = """
            {"bundleRevision":1,"provider":" good ",
             "files":{"s.glsl":"$hash"},"profiles":{"fast":["s.glsl"]}}
        """.trimIndent()
        val reader = FakeAssetReader(
            mapOf("upscaler_bundles" to listOf("good"),
                  "upscaler_bundles/good" to listOf("manifest.json")),
            mapOf("upscaler_bundles/good/manifest.json" to manifestJson.toByteArray(),
                  "upscaler_bundles/good/s.glsl" to bytes)
        )
        val manager = UpscalerBundleManager(filesRoot, cacheRoot, reader)

        assertTrue(manager.refreshFromAssets().isEmpty())
        assertFalse("no install side effect may occur",
            File(filesRoot, "upscaler/good").exists())
        assertNull(manager.installedBundleDir("good"))

        filesRoot.deleteRecursively(); cacheRoot.deleteRecursively()
    }

    /** Helper: build a StagedBundle from raw parts for installer-level sandbox tests. */
    private fun customStaged(
        provider: String,
        files: Map<String, String>,          // manifest key -> sha256
        stagedPaths: Map<String, String>,    // manifest key -> real file path
        profiles: Map<String, List<String>>,
        revision: Int = 1
    ): UpscalerBundleInstaller.StagedBundle = UpscalerBundleInstaller.StagedBundle(
        UpscalerBundleManifest(
            bundleRevision = revision, provider = provider, upstreamRevision = "",
            files = LinkedHashMap(files), profiles = profiles
        ),
        stagedPaths
    )

    private fun writeShader(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }

    /**
     * CP2v3-02: path-like manifest file keys are rejected BEFORE any filesystem work —
     * nothing escapes the provider staging/install directory.
     */
    @Test
    fun testCpV202ShaderFileNameSandbox() {
        val shader = writeShader("real.glsl", "CONTENT")
        val hash = UpscalerBundleInstaller.sha256(shader)
        val installDir = File(tempDir, "upscaler/cunny")

        // "../escape.glsl" → rejected, and NO sibling escape file is ever written.
        var staged = customStaged(
            provider = "cunny",
            files = mapOf("fast.glsl" to hash, "../escape.glsl" to hash),
            stagedPaths = mapOf("fast.glsl" to shader.absolutePath,
                                "../escape.glsl" to shader.absolutePath),
            profiles = mapOf("fast" to listOf("fast.glsl"))
        )
        assertFalse(UpscalerBundleInstaller.install(staged, installDir))
        assertFalse(File(tempDir, "upscaler/escape.glsl").exists())

        // "sub/file.glsl" → rejected under the bare-filename policy.
        staged = customStaged(
            provider = "cunny",
            files = mapOf("sub/file.glsl" to hash),
            stagedPaths = mapOf("sub/file.glsl" to shader.absolutePath),
            profiles = mapOf("fast" to listOf("sub/file.glsl"))
        )
        assertFalse(UpscalerBundleInstaller.install(staged, installDir))
        assertFalse(File(tempDir, "upscaler/cunny/sub").exists())

        // Profile referencing an unlisted ("ghost") file → rejected.
        staged = customStaged(
            provider = "cunny",
            files = mapOf("fast.glsl" to hash),
            stagedPaths = mapOf("fast.glsl" to shader.absolutePath),
            profiles = mapOf("fast" to listOf("ghost.glsl"))
        )
        assertFalse(UpscalerBundleInstaller.install(staged, installDir))

        // Profile referencing a path outside the bundle → rejected.
        staged = customStaged(
            provider = "cunny",
            files = mapOf("fast.glsl" to hash),
            stagedPaths = mapOf("fast.glsl" to shader.absolutePath),
            profiles = mapOf("fast" to listOf("../other/x.glsl"))
        )
        assertFalse(UpscalerBundleInstaller.install(staged, installDir))
        assertFalse(File(tempDir, "upscaler/other").exists())
    }

    /**
     * CP2v3-02: a malformed bundle cannot touch ANOTHER provider's directory under
     * files/upscaler/ — cross-provider containment holds.
     */
    @Test
    fun testCpV202MalformedBundleCannotModifySiblingProviderDir() {
        val sentinel = writeShader("sentinel.glsl", "SENTINEL_B")
        val sibling = File(tempDir, "upscaler/b")
        sibling.mkdirs()
        File(sibling, "sentinel.glsl").writeText("SENTINEL_B")
        val bytesBefore = File(sibling, "sentinel.glsl").readText()

        val hash = UpscalerBundleInstaller.sha256(sentinel)
        val staged = customStaged(
            provider = "a",
            files = mapOf("safe.glsl" to hash, "../b/sentinel.glsl" to hash),
            stagedPaths = mapOf("safe.glsl" to sentinel.absolutePath,
                                "../b/sentinel.glsl" to sentinel.absolutePath),
            profiles = mapOf("fast" to listOf("../b/sentinel.glsl"))
        )
        assertFalse(UpscalerBundleInstaller.install(staged, File(tempDir, "upscaler/a")))
        assertEquals("sibling provider dir untouched",
            bytesBefore, File(sibling, "sentinel.glsl").readText())
        assertTrue(sibling.listFiles()!!.size == 1)
    }

    /** CP2v3-02: canonical bare filenames + listed profile refs still install AND resolve. */
    @Test
    fun testCpV202CanonicalBareFilenamesStillInstallAndResolve() {
        val fast = writeShader("fast.glsl", "FAST")
        val aux = writeShader("aux.glsl", "AUX")
        val staged = customStaged(
            provider = "cunny",
            files = mapOf(
                "fast.glsl" to UpscalerBundleInstaller.sha256(fast),
                "aux.glsl" to UpscalerBundleInstaller.sha256(aux)
            ),
            stagedPaths = mapOf(
                "fast.glsl" to fast.absolutePath,
                "aux.glsl" to aux.absolutePath
            ),
            profiles = mapOf("quality" to listOf("aux.glsl", "fast.glsl"))
        )
        val installDir = File(tempDir, "upscaler/cunny")
        assertTrue(UpscalerBundleInstaller.install(staged, installDir))
        assertTrue(UpscalerBundleInstaller.isInstalledValid(installDir, "cunny"))
        assertEquals(
            "${File(installDir, "aux.glsl").absolutePath}:${File(installDir, "fast.glsl").absolutePath}",
            UpscalerBundleInstaller.installedShaderPath(installDir, "aux.glsl") + ":" +
                UpscalerBundleInstaller.installedShaderPath(installDir, "fast.glsl")
        )
    }

    /** CP2v3-01: register() refuses non-canonical provider ids at the registry boundary. */
    @Test
    fun testRegisterRefusesNonCanonicalProviderId() {
        val manager = UpscalerBundleManager(
            Files.createTempDirectory("cpreg_files").toFile(),
            Files.createTempDirectory("cpreg_cache").toFile(),
            FakeAssetReader(emptyMap(), emptyMap())
        )
        manager.register(FakeUpscalerProvider(available = true, id = "Bad Id"))
        manager.register(FakeUpscalerProvider(available = true, id = "CuNNy"))
        assertTrue(manager.availableProviders().isEmpty())
        assertNull(manager.providerFor("bad id"))
        assertNull(manager.providerFor("cunny"))

        // Canonical registration still works.
        manager.register(FakeUpscalerProvider(available = true, id = "cunny"))
        assertEquals("cunny", manager.providerFor("cunny")?.id)
    }

    /**
     * CP2v4-01 regression: RUNTIME tamper — an installed manifest whose profile chain references
     * an unchecksummed "ghost" file must fail isInstalledValid, make the provider unavailable,
     * yield null resolution, and fall back to the Off/empty chain at the resolver.
     */
    @Test
    fun testCpV401RuntimeTamperedProfileRefFailsClosed() {
        val dir = installValidBundle(revision = 1)
        val provider = cunnyProvider()
        assertTrue(UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))
        assertTrue(provider.isAvailable())

        // Plant ghost.glsl (never checksum-listed) and rewrite ONLY the installed manifest so
        // profile "fast" references it; manifest.files still lists the original valid shaders.
        File(dir, "ghost.glsl").writeText("UNTRUSTED")
        val manifest = UpscalerBundleInstaller.let {
            kotlinx.serialization.json.Json.decodeFromString(
                UpscalerBundleManifest.serializer(), File(dir, "manifest.json").readText())
        }
        val tampered = manifest.copy(profiles = mapOf("fast" to listOf("ghost.glsl")))
        File(dir, "manifest.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                UpscalerBundleManifest.serializer(), tampered)
        )

        assertFalse("ghost ref must invalidate runtime trust",
            UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))
        assertFalse(provider.isAvailable())
        assertNull(provider.resolveShaderChain("fast"))

        val resolved = VideoPipelineResolver.resolve(
            VideoPipelineConfig(upscaler = UpscalerConfig("cunny", "fast")), provider)
        assertFalse(resolved.upscalerActive)
        assertEquals("", resolved.glslShaderChain)
    }

    /** CP2v4-01: path-like profile refs in an INSTALLED manifest also fail runtime validation. */
    @Test
    fun testCpV401RuntimeTamperedPathLikeProfileRefFailsClosed() {
        val dir = installValidBundle(revision = 1)
        val manifest = kotlinx.serialization.json.Json.decodeFromString(
            UpscalerBundleManifest.serializer(), File(dir, "manifest.json").readText())
        val tampered = manifest.copy(profiles = mapOf("fast" to listOf("../escape/x.glsl")))
        File(dir, "manifest.json").writeText(
            kotlinx.serialization.json.Json.encodeToString(
                UpscalerBundleManifest.serializer(), tampered)
        )
        assertFalse(UpscalerBundleInstaller.isInstalledValid(dir, "cunny"))
        assertFalse(cunnyProvider().isAvailable())
    }

    /**
     * CP2v4-02 regressions: the staged file set must EXACTLY equal the checksum-listed manifest
     * file set, and installation copies only manifest-listed files.
     */
    @Test
    fun testCpV402StagedFileSetMustExactlyMatchManifest() {
        val shader = writeShader("safe.glsl", "SAFE_CONTENT")
        val hash = UpscalerBundleInstaller.sha256(shader)
        val installDir = File(tempDir, "upscaler/cunny")

        fun baseStaged(extra: Map<String, String>, drop: Boolean) =
            customStaged(
                provider = "cunny",
                files = mapOf("safe.glsl" to hash),
                stagedPaths = buildMap {
                    if (!drop) put("safe.glsl", shader.absolutePath) // drop ⇒ manifest entry MISSING from staged map
                    putAll(extra)
                },
                profiles = mapOf("fast" to listOf("safe.glsl"))
            )

        // 1) valid manifest + extra benign staged entry → rejected.
        assertFalse(UpscalerBundleInstaller.install(
            baseStaged(mapOf("extra.glsl" to shader.absolutePath), drop = false), installDir))

        // 2) extra path-like staged entry → rejected AND sibling provider dir untouched.
        val sibling = File(tempDir, "upscaler/b")
        sibling.mkdirs()
        val sentinelBefore = "SENTINEL_B"
        File(sibling, "sentinel.glsl").writeText(sentinelBefore)
        assertFalse(UpscalerBundleInstaller.install(
            baseStaged(mapOf("../b/sentinel.glsl" to shader.absolutePath), drop = false), installDir))
        assertEquals(sentinelBefore, File(sibling, "sentinel.glsl").readText())

        // 3) staged map MISSING a manifest entry → rejected.
        assertFalse(UpscalerBundleInstaller.install(
            baseStaged(emptyMap(), drop = true), installDir))

        // 4) exact matching key sets → installs successfully.
        assertTrue(UpscalerBundleInstaller.install(
            baseStaged(emptyMap(), drop = false), installDir))
        assertTrue(UpscalerBundleInstaller.isInstalledValid(installDir, "cunny"))
        assertFalse(File(sibling, "extra.glsl").exists())
    }
}