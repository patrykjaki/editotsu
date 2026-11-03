package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentLinksTest {
    @Test
    fun readsTheIndexAndStopsAtTheNextParameter() {
        assertEquals(3, TorrentLinks.fileIndex("magnet:?xt=urn:btih:abc&index=3&tr=udp://t"))
        assertEquals(7, TorrentLinks.fileIndex("http://127.0.0.1:8090/stream?hash=ab&index=7"))
    }

    @Test
    fun noIndexIsFileZero() {
        assertEquals(0, TorrentLinks.fileIndex("magnet:?xt=urn:btih:abc&tr=udp://t"))
        assertEquals(0, TorrentLinks.fileIndex("https://x/file.torrent"))
    }

    @Test
    fun ignoresIndexInsideAnotherParameterName() {
        assertEquals(0, TorrentLinks.fileIndex("magnet:?xt=urn:btih:abc&reindex=4"))
    }
}
