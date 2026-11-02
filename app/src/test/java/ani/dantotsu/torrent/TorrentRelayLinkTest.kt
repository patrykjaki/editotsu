package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentRelayLinkTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun parsesRelayLink() {
        assertEquals(
            TorrentRelayLink(hash.uppercase(), 3),
            TorrentRelayLink.parse("http://127.0.0.1:8090/stream?hash=$hash&index=3"),
        )
    }

    @Test
    fun otherUrlsAreNotRelayLinks() {
        assertNull(TorrentRelayLink.parse("magnet:?xt=urn:btih:$hash&index=3"))
        assertNull(TorrentRelayLink.parse("https://example.org/stream?hash=$hash&index=3"))
        assertNull(TorrentRelayLink.parse("http://127.0.0.1:8090/stream?hash=abc&index=3"))
        assertNull(TorrentRelayLink.parse("http://127.0.0.1:8090/stream?hash=$hash"))
    }
}
