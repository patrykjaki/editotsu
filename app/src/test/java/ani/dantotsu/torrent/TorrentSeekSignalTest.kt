package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentSeekSignalTest {
    private val ranges = listOf(100.0 to 130.0)

    @Test
    fun seekInsideTheCacheKeepsTheRead() {
        assertFalse(TorrentSeekSignal.needsNewRead(110.0, ranges))
        assertFalse(TorrentSeekSignal.needsNewRead(130.5, ranges))
        assertFalse(TorrentSeekSignal.needsNewRead(99.5, ranges))
    }

    @Test
    fun seekOutsideTheCacheNeedsANewRead() {
        assertTrue(TorrentSeekSignal.needsNewRead(140.0, ranges))
        assertTrue(TorrentSeekSignal.needsNewRead(50.0, ranges))
        assertTrue(TorrentSeekSignal.needsNewRead(10.0, emptyList()))
    }

    @Test
    fun onlyRelayLinksReachTheRelay() {
        var got: Pair<String, Int>? = null
        TorrentSeekSignal.listener = { h, i -> got = h to i }
        try {
            TorrentSeekSignal.uncachedSeek("https://example.org/video.mp4")
            assertNull(got)
            val hash = "0123456789ABCDEF0123456789ABCDEF01234567"
            TorrentSeekSignal.uncachedSeek("http://127.0.0.1:8090/stream?hash=$hash&index=2")
            assertEquals(hash to 2, got)
        } finally {
            TorrentSeekSignal.listener = null
        }
    }
}
