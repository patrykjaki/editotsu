package ani.dantotsu.parsers.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LnReaderSafePluginDirTest {

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("lnreader_plugin_test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validPluginIds_resolveInsideRoot() = withTempRoot { root ->
        val simple = resolveSafePluginDir(root, "sample-plugin")
        assertEquals(File(root, "sample-plugin").canonicalFile, simple.canonicalFile)
        assertEquals(root.canonicalFile, simple.canonicalFile.parentFile)

        val dotted = resolveSafePluginDir(root, "en.plugin-1_2")
        assertEquals(File(root, "en.plugin-1_2").canonicalFile, dotted.canonicalFile)
        assertEquals(root.canonicalFile, dotted.canonicalFile.parentFile)
    }

    @Test
    fun dotAndDotDotAndBlank_areRejected() = withTempRoot { root ->
        val invalidIds = listOf(".", "..", "", "   ", "\t\n")
        for (badId in invalidIds) {
            try {
                resolveSafePluginDir(root, badId)
                fail("Expected IllegalArgumentException for pluginId='$badId'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun traversalSeparators_areSanitizedToStrictChild() = withTempRoot { root ->
        val resolved = resolveSafePluginDir(root, "../escape")
        assertEquals(root.canonicalFile, resolved.canonicalFile.parentFile)
        assertTrue(resolved.canonicalPath != root.canonicalPath)
    }
}
