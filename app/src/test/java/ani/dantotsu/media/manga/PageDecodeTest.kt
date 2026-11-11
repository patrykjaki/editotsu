package ani.dantotsu.media.manga

import ani.dantotsu.media.manga.mangareader.findOutOfMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.ExecutionException

class PageDecodeTest {

    @Test
    fun `fallback decodes the same bytes when the primary decoder returns null`() {
        val bytes = byteArrayOf(1, 2, 3)
        var seen: ByteArray? = null
        val result = decodeWithFallback(bytes, primary = { null }, fallback = { seen = it; "libvips" })
        assertEquals("libvips", result)
        assertSame(bytes, seen)
    }

    @Test
    fun `fallback is not called when the primary decoder succeeds`() {
        var fallbackCalls = 0
        val result = decodeWithFallback(byteArrayOf(1), primary = { "bitmapfactory" }, fallback = { fallbackCalls++; "libvips" })
        assertEquals("bitmapfactory", result)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `both decoders failing gives null`() {
        assertNull(decodeWithFallback<String>(byteArrayOf(1), primary = { null }, fallback = { null }))
    }

    @Test
    fun `out of memory is found directly and when wrapped`() {
        val oom = OutOfMemoryError("page")
        assertSame(oom, findOutOfMemory(oom))
        assertSame(oom, findOutOfMemory(ExecutionException(RuntimeException(oom))))
    }

    @Test
    fun `other failures are not out of memory`() {
        assertNull(findOutOfMemory(ExecutionException(IllegalStateException("decode"))))
        assertNull(findOutOfMemory(RuntimeException()))
    }
}
