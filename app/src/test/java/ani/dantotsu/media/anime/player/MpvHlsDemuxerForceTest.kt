package ani.dantotsu.media.anime.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beta10: the beta09 HLS `demuxer-lavf-format=mpegts` force is REVERTED.
 *
 * Field result: the force applies at EVERY demux_open including the
 * top-level playlist open, so variant playlists stop resolving as HLS
 * (working HD-2/Vidstream-2 regressed to "loading failed"; HD-1 showed
 * decoder garbage). Desktop mpv confirms forced formats hang/fail
 * playlist opens that play fine unforced.
 *
 * These tests pin the revert: NO source class may carry a forced lavf
 * demuxer format in per-file options. The PNG-wrapped-TS problem needs
 * segment-level demuxer control, which mpv does not expose via loadfile
 * options — deliberately left unsolved rather than regressing servers.
 */
class MpvHlsDemuxerForceTest {

    private val headers = mapOf(
        "User-Agent" to "Dantotsu/3.2",
        "Referer" to "https://megaplay.buzz/"
    )

    @Test
    fun noProfileForcesLavfDemuxerFormat() {
        val all = listOf(
            PlaybackSourceClass.HLS,
            PlaybackSourceClass.DIRECT_HTTP,
            PlaybackSourceClass.TORRENT_LOCALHOST,
            PlaybackSourceClass.LOCAL_FILE,
            PlaybackSourceClass.CONTENT_FD
        )
        for (sourceClass in all) {
            val options = MpvNetworkOptions.buildPerFilePerformanceOptions(
                sourceClass = sourceClass,
                headers = headers
            )
            assertFalse(
                "$sourceClass must not force a lavf demuxer format (beta09 regression), got: $options",
                options.contains("demuxer-lavf-format=")
            )
        }
    }

    @Test
    fun hlsProfileKeepsBaselinePerformanceOptions() {
        val options = MpvNetworkOptions.buildPerFilePerformanceOptions(
            sourceClass = PlaybackSourceClass.HLS,
            headers = headers
        )
        assertTrue(options.contains("demuxer-max-bytes=67108864"))
        assertTrue(options.contains("network-timeout=20"))
    }
}
