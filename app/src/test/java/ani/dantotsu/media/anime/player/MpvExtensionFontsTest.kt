package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MpvExtensionFontsTest {
    private fun tempDir(): File = Files.createTempDirectory("mpvfonts").toFile()

    @Test
    fun `finds aniyomi and cache font folders`() {
        val files = tempDir(); val cache = tempDir()
        File(cache, "reanime-fonts").mkdirs(); File(cache, "images").mkdirs()
        val dirs = MpvExtensionFonts.sourceDirs(files, cache).map { it.name }
        assertEquals(listOf("fonts", "reanime-fonts"), dirs)
    }

    @Test
    fun `copies fonts once with a prefixed name and skips other files`() {
        val src = File(tempDir(), "reanime-fonts").apply { mkdirs() }
        File(src, "Gandhi.ttf").writeBytes(ByteArray(10) { 1 })
        File(src, "notes.txt").writeText("x")
        File(src, "empty.otf").writeBytes(ByteArray(0))
        val target = tempDir()
        File(target, "Poppins.ttf").writeBytes(ByteArray(3))

        assertEquals(1, MpvExtensionFonts.stage(listOf(src), target))
        assertTrue(File(target, "ext_reanime-fonts_Gandhi.ttf").isFile)
        assertFalse(File(target, "ext_reanime-fonts_notes.txt").exists())
        assertEquals(3L, File(target, "Poppins.ttf").length())
        assertEquals(0, MpvExtensionFonts.stage(listOf(src), target))
    }

    @Test
    fun `a changed font is copied again and the target folder is never a source`() {
        val src = File(tempDir(), "fonts").apply { mkdirs() }
        File(src, "A.ttf").writeBytes(ByteArray(4))
        val target = tempDir()
        assertEquals(1, MpvExtensionFonts.stage(listOf(src, target), target))
        File(src, "A.ttf").writeBytes(ByteArray(8))
        assertEquals(1, MpvExtensionFonts.stage(listOf(src), target))
        assertEquals(8L, File(target, "ext_fonts_A.ttf").length())
    }
}
