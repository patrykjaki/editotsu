package ani.dantotsu.media.anime.player.upscaler

import ani.dantotsu.media.anime.player.UpscalerConfig
import ani.dantotsu.media.anime.player.VideoPipelineConfig
import ani.dantotsu.media.anime.player.VideoPipelineResolver
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real CuNNy bundle shipped in `app/src/main/assets/upscaler_bundles/cunny`
 * installs, validates and resolves through the production manager/provider.
 */
class CunnyBundleTest {
    private val assetRoot = listOf(
        File("src/main/assets"),
        File("app/src/main/assets")
    ).first { File(it, "upscaler_bundles/cunny/manifest.json").exists() }

    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    /** File-backed AssetReader over the real asset tree (child names only, like AssetManager). */
    private inner class DirAssetReader : UpscalerBundleManager.AssetReader {
        override fun list(dir: String): List<String> =
            File(assetRoot, dir).list()?.filterNot { it.startsWith(".") }?.sorted() ?: emptyList()

        override fun readBytes(path: String): ByteArray = File(assetRoot, path).readBytes()

        override fun stageTo(tempDir: File, path: String): File {
            tempDir.mkdirs()
            return File(assetRoot, path).copyTo(File(tempDir, path.substringAfterLast('/')), overwrite = true)
        }
    }

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("cunny_files").toFile()
        cacheDir = Files.createTempDirectory("cunny_cache").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun manager(): UpscalerBundleManager =
        UpscalerBundleManager(filesDir, cacheDir, DirAssetReader()).also {
            it.register(CunnyUpscalerProvider(it::installedBundleDir))
        }

    private fun manifest(): UpscalerBundleManifest = Json.decodeFromString(
        UpscalerBundleManifest.serializer(),
        File(assetRoot, "upscaler_bundles/cunny/manifest.json").readText()
    )

    @Test
    fun `shipped manifest is structurally valid and checksums match the asset bytes`() {
        val m = manifest()
        assertEquals(CunnyUpscalerProvider.ID, m.provider)
        assertTrue(UpscalerBundleInstaller.validateManifestStructure(m, CunnyUpscalerProvider.ID).isEmpty())
        for ((name, hash) in m.files) {
            val f = File(assetRoot, "upscaler_bundles/cunny/$name")
            assertTrue("missing $name", f.exists())
            assertEquals("checksum $name", hash, UpscalerBundleInstaller.sha256(f))
        }
        // Every provider profile is backed by the manifest and vice versa.
        assertEquals(CunnyUpscalerProvider.PROFILES.map { it.id }.toSet(), m.profiles.keys)
    }

    @Test
    fun `license and notice ship with the shaders`() {
        val names = manifest().files.keys
        assertTrue("LICENSE-LGPL-3.0.txt" in names)
        assertTrue("LICENSE-GPL-3.0.txt" in names)
        assertTrue("NOTICE.txt" in names)
        val notice = File(assetRoot, "upscaler_bundles/cunny/NOTICE.txt").readText()
        assertTrue(notice.contains(manifest().upstreamRevision))
        for (shader in names.filter { it.endsWith(".glsl") }) {
            val head = File(assetRoot, "upscaler_bundles/cunny/$shader").readLines().take(6).joinToString("\n")
            assertTrue("$shader keeps its LGPL header", head.contains("GNU Lesser General Public"))
        }
    }

    @Test
    fun `bundle installs and every profile resolves to its shader`() {
        val m = manager()
        val provider = m.providerFor(CunnyUpscalerProvider.ID)
        assertNotNull(provider)
        assertEquals(listOf("fast", "balanced", "quality"), provider!!.profiles().map { it.id })
        val expected = mapOf(
            "fast" to "CuNNy-veryfast-SOFT.glsl",
            "balanced" to "CuNNy-faster-SOFT.glsl",
            "quality" to "CuNNy-fast-SOFT.glsl",
        )
        for ((profile, file) in expected) {
            val chain = provider.resolveShaderChain(profile)
            assertEquals(File(filesDir, "upscaler/cunny/$file").absolutePath, chain)
        }
        assertNull(provider.resolveShaderChain("unknown"))
    }

    @Test
    fun `resolver applies the chain only for a cunny selection`() {
        val provider = manager().providerFor(CunnyUpscalerProvider.ID)
        val on = VideoPipelineResolver.resolve(
            VideoPipelineConfig(upscaler = UpscalerConfig(CunnyUpscalerProvider.ID, "balanced")), provider
        )
        assertTrue(on.upscalerActive)
        assertTrue(on.glslShaderChain.endsWith("CuNNy-faster-SOFT.glsl"))

        val off = VideoPipelineResolver.resolve(VideoPipelineConfig(upscaler = UpscalerConfig.off()), provider)
        assertFalse(off.upscalerActive)
        assertEquals("", off.glslShaderChain)
    }

    @Test
    fun `tampered installed shader makes the provider unavailable`() {
        manager()
        File(filesDir, "upscaler/cunny/CuNNy-fast-SOFT.glsl").writeText("broken")
        val provider = CunnyUpscalerProvider { File(filesDir, "upscaler/$it") }
        assertFalse(provider.isAvailable())
        assertNull(provider.resolveShaderChain("quality"))
        // A fresh manager repairs the install from assets (same revision, invalid install).
        assertNotNull(manager().providerFor(CunnyUpscalerProvider.ID))
    }

    @Test
    fun `settings choices list off plus profiles, unknown selection shows off`() {
        val provider = manager().providerFor(CunnyUpscalerProvider.ID)
        val choices = UpscalerChoices.choices(provider)
        assertEquals(listOf("off", "cunny", "cunny", "cunny"), choices.map { it.providerId })
        assertEquals(listOf("", "fast", "balanced", "quality"), choices.map { it.profileId })
        assertEquals(2, UpscalerChoices.selectedIndex(choices, "cunny", "balanced"))
        assertEquals(0, UpscalerChoices.selectedIndex(choices, "off", ""))
        assertEquals(0, UpscalerChoices.selectedIndex(choices, "cunny", "gone"))
        assertEquals(1, UpscalerChoices.choices(null).size)
    }
}
