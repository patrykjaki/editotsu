package ani.dantotsu.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStateStoreTest {
    private fun store(): Pair<SessionStateStore, File> {
        val dir = Files.createTempDirectory("sst").toFile()
        val f = File(dir, "torrent/session.state")
        return SessionStateStore(f) to f
    }

    @Test
    fun `save then load round-trips and leaves no temp file`() {
        val (s, f) = store()
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertTrue(s.save(bytes))
        assertArrayEquals(bytes, s.load())
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
        assertTrue(s.save(byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), s.load())
    }

    @Test
    fun `missing, empty and discarded state load as null, empty saves are refused`() {
        val (s, f) = store()
        assertNull(s.load())
        assertFalse(s.save(ByteArray(0)))
        assertFalse(s.save(null))
        f.parentFile.mkdirs(); f.writeBytes(ByteArray(0))
        assertNull(s.load())
        s.save(byteArrayOf(5)); s.discard()
        assertNull(s.load())
    }
}
