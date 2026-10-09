package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M7B: tracker-merge measurement. Upstream 28cbfba3 carries 10
 * default trackers; the local set must remain a strict superset after the
 * merge (25 + internetwarriors + openbts = 27), duplicate-free, all valid
 * announce URLs. Peer-discovery effect itself is device-only OPEN debt.
 */
class TorrentTrackersTest {

    private val upstreamTen = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.openbts.com:6969/announce"
    )

    @Test
    fun `local set is strict superset of upstream ten`() {
        val local = TORRENT_DEFAULT_TRACKERS.toSet()
        assertTrue(local.containsAll(upstreamTen))
        assertTrue(local.size > upstreamTen.size)
    }

    @Test
    fun `merged additions present`() {
        assertTrue(TORRENT_DEFAULT_TRACKERS.contains("udp://tracker.internetwarriors.net:1337/announce"))
        assertTrue(TORRENT_DEFAULT_TRACKERS.contains("udp://tracker.openbts.com:6969/announce"))
    }

    @Test
    fun `exact size and no duplicates`() {
        assertEquals(27, TORRENT_DEFAULT_TRACKERS.size)
        assertEquals(27, TORRENT_DEFAULT_TRACKERS.toSet().size)
    }

    @Test
    fun `all entries are valid announce urls`() {
        val pattern = Regex("^(udp|https?)://[^/\\s]+/announce$")
        TORRENT_DEFAULT_TRACKERS.forEach { tracker ->
            assertTrue("invalid tracker URL: $tracker", pattern.matches(tracker))
        }
    }
}
