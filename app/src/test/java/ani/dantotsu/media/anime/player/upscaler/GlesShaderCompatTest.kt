package ani.dantotsu.media.anime.player.upscaler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * vo=gpu on GLES rejects `#extension` after non-preprocessor tokens; CuNNy declares the
 * optional float16 extension inside every pass body (seen on a device, 2026-11-01).
 */
class GlesShaderCompatTest {

    private val pass = """
        //!DESC CuNNy-veryfast-SOFT-in
        //!HOOK LUMA
        //!COMPUTE 16 8 8 8
        #extension GL_EXT_shader_explicit_arithmetic_types_float16 : enable
        #ifdef GL_EXT_shader_explicit_arithmetic_types_float16
        #	define F float16_t
        #else
        #	define F float
        #endif
        void hook() {}
    """.trimIndent()

    @Test
    fun `float16 directive is commented out and its ifdef forced to the fallback`() {
        val out = GlesShaderCompat.patch(pass)
        assertFalse(out.lines().any { it.trimStart().startsWith("#extension") })
        assertFalse(out.contains("#ifdef GL_EXT_shader_explicit_arithmetic_types_float16"))
        assertTrue(out.contains("#if 0 // editotsu-gles: replaces ifdef GL_EXT_shader_explicit_arithmetic_types_float16"))
        assertTrue(out.contains("#\tdefine F float\n"))
        assertEquals(pass.lines().size, out.lines().size)
    }

    @Test
    fun `unrelated extensions and ifdefs are left alone`() {
        val src = "#extension GL_OES_texture_3D : enable\n#ifdef FOO\n#endif\nvoid hook() {}"
        assertEquals(src, GlesShaderCompat.patch(src))
        assertFalse(GlesShaderCompat.needsPatch(src))
    }

    @Test
    fun `every shipped cunny shader is patched cleanly with the same line count`() {
        val dir = listOf(
            File("src/main/assets/upscaler_bundles/cunny"),
            File("app/src/main/assets/upscaler_bundles/cunny")
        ).first { it.isDirectory }
        val shaders = dir.listFiles { f -> f.name.endsWith(".glsl") }!!.sortedBy { it.name }
        assertEquals(3, shaders.size)
        for (shader in shaders) {
            val src = shader.readText()
            val out = GlesShaderCompat.patch(src)
            assertTrue("${shader.name} should need the patch", out != src)
            assertFalse(shader.name, out.lines().any { it.trimStart().startsWith("#extension") })
            assertFalse(shader.name, out.contains("#ifdef GL_EXT_shader_explicit_arithmetic_types_float16"))
            assertEquals(shader.name, src.lines().size, out.lines().size)
            // every pass keeps its directives
            assertEquals(shader.name, src.lines().count { it.startsWith("//!HOOK") },
                out.lines().count { it.startsWith("//!HOOK") })
        }
    }

    @Test
    fun `derived copy is written once outside the source dir and reused`() {
        val src = Files.createTempDirectory("gles_src").toFile()
        val out = Files.createTempDirectory("gles_out").toFile()
        val shader = File(src, "CuNNy-test.glsl").apply { writeText(pass) }
        val copy = GlesShaderCompat.derivedCopy(shader, out)
        assertNotNull(copy)
        assertEquals(File(out, shader.name), copy)
        assertEquals(GlesShaderCompat.patch(pass), copy!!.readText())
        assertEquals(pass, shader.readText())  // source untouched
        val stamp = copy.lastModified()
        assertEquals(copy, GlesShaderCompat.derivedCopy(shader, out))
        assertEquals(stamp, copy.lastModified())
        src.deleteRecursively(); out.deleteRecursively()
    }

    @Test
    fun `shader that needs no patch is returned as is`() {
        val src = Files.createTempDirectory("gles_plain").toFile()
        val shader = File(src, "plain.glsl").apply { writeText("//!HOOK MAIN\nvoid hook() {}") }
        assertSame(shader, GlesShaderCompat.derivedCopy(shader, File(src, "out")))
        src.deleteRecursively()
    }
}
