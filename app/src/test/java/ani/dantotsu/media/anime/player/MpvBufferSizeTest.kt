package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvBufferSizeTest {

    private val mb = 1024L * 1024L

    @Test
    fun defaultKeepsTheOldSizes() {
        val http = MpvNetworkOptions.buildPerFilePerformanceOptions(PlaybackSourceClass.DIRECT_HTTP)
        assertTrue(http.contains("demuxer-max-bytes=67108864"))
        assertTrue(http.contains("demuxer-max-back-bytes=33554432"))
        val torrent = MpvNetworkOptions.buildPerFilePerformanceOptions(PlaybackSourceClass.TORRENT_LOCALHOST)
        assertTrue(torrent.contains("demuxer-max-bytes=33554432"))
        assertTrue(torrent.contains("demuxer-max-back-bytes=16777216"))
    }

    @Test
    fun largerChoicesGrowEachSource() {
        assertEquals(512 * mb, MpvNetworkOptions.bufferBytes(2, torrent = false))
        assertEquals(128 * mb, MpvNetworkOptions.bufferBytes(1, torrent = true))
        val hls = MpvNetworkOptions.buildPerFilePerformanceOptions(PlaybackSourceClass.HLS, bufferLevel = 1)
        assertTrue(hls.contains("demuxer-max-bytes=${256 * mb}"))
    }

    @Test
    fun ramCapLimitsButNeverGoesBelowDefault() {
        assertEquals(384 * mb, MpvNetworkOptions.bufferBytes(2, torrent = false, ramCapBytes = 384 * mb))
        assertEquals(64 * mb, MpvNetworkOptions.bufferBytes(2, torrent = false, ramCapBytes = 16 * mb))
        assertEquals(64 * mb, MpvNetworkOptions.bufferBytes(7, torrent = false))
    }
}
