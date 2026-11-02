package ani.dantotsu.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentMetadataCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hash = "6E901AC2055019A3905E80F44383C7F39443AC15"

    @Test
    fun `save then load by hash, case-insensitive`() {
        val cache = TorrentMetadataCache(tmp.newFolder("meta"))
        val bytes = "d4:infod4:name1:aee".toByteArray()
        assertTrue(cache.save(hash.lowercase(), bytes))
        assertArrayEquals(bytes, cache.load(hash))
    }

    @Test
    fun `bad hashes and empty data are refused`() {
        val cache = TorrentMetadataCache(tmp.newFolder("meta"))
        assertFalse(cache.save("../../etc", "x".toByteArray()))
        assertFalse(cache.save(hash, ByteArray(0)))
        assertNull(cache.load("nothex"))
        assertNull(cache.load(hash))
    }

    @Test
    fun `prune drops the oldest past the limit and discard removes an entry`() {
        val dir = tmp.newFolder("meta")
        val cache = TorrentMetadataCache(dir, maxEntries = 2)
        val (a, b, c) = listOf("A", "B", "C").map { it.repeat(40) }
        assertTrue(cache.save(a, "d1:xi1ee".toByteArray()))
        java.io.File(dir, "$a.torrent").setLastModified(1_000_000L)
        assertTrue(cache.save(b, "d1:xi2ee".toByteArray()))
        java.io.File(dir, "$b.torrent").setLastModified(2_000_000L)
        assertTrue(cache.save(c, "d1:xi3ee".toByteArray())) // third entry: prune drops a
        assertFalse(java.io.File(dir, "$a.torrent").exists())
        assertTrue(java.io.File(dir, "$b.torrent").exists())
        cache.discard(c)
        assertNull(cache.load(c))
    }

    @Test
    fun `info section is wrapped into a minimal torrent`() {
        val info = "d6:lengthi5e4:name1:ae".toByteArray()
        assertEquals("d4:infod6:lengthi5e4:name1:aee", String(TorrentMetadataCache.wrapInfoSection(info)!!))
        assertNull(TorrentMetadataCache.wrapInfoSection("i5e".toByteArray()))
    }

    @Test
    fun `magnet trackers are decoded`() {
        val m = "magnet:?xt=urn:btih:$hash&dn=x&tr=http%3A%2F%2Fnyaa.tracker.wf%3A7777%2Fannounce&tr=udp%3A%2F%2Fa.b%3A1%2Fannounce"
        assertEquals(listOf("http://nyaa.tracker.wf:7777/announce", "udp://a.b:1/announce"), TorrentMetadataCache.magnetTrackers(m))
        assertTrue(TorrentMetadataCache.magnetTrackers("http://x/y.torrent").isEmpty())
    }
}
