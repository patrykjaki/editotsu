package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EXT-COMPAT-1/2/4 (engine half): extension auxiliary tracks reach mpv with
 * their source transport context, and extension mpv options reach the file load.
 *
 * Synthetic AniZone-like request: HLS video + separate audio/subtitle URLs that
 * require the source Referer/Cookie, plus demuxer-lavf-o=force_mpegts=1.
 */
class AuxTrackTransportTest {

    private lateinit var fakeClient: FakeMpvClient
    private lateinit var engine: MpvPlaybackEngine

    private val sourceHeaders = mapOf(
        "Referer" to "https://site.example/",
        "Cookie" to "session=abc123",
        "User-Agent" to "AniZoneAgent/1.0"
    )

    @Before
    fun setup() {
        fakeClient = FakeMpvClient()
        engine = MpvPlaybackEngine(null, fakeClient)
        Thread.sleep(200)
    }

    private fun anizoneLikeRequest(): PlaybackRequest {
        return PlaybackRequest(
            uri = "https://cdn.example/master.m3u8",
            headers = sourceHeaders,
            sourceClass = PlaybackSourceClass.HLS,
            externalSubtitles = listOf(
                ExternalSubtitle(
                    url = "https://cdn.example/subs/en.vtt",
                    title = "English",
                    language = "English",
                    selected = true,
                    headers = sourceHeaders
                )
            ),
            externalAudioTracks = listOf(
                ExternalAudioTrack(
                    url = "https://cdn.example/audio/en.m3u8",
                    language = "English",
                    title = "English",
                    headers = sourceHeaders
                )
            ),
            preferredSubLang = "English",
            extraMpvOptions = "demuxer-lavf-o=\"force_mpegts=1\""
        )
    }

    private fun loadAndBind(request: PlaybackRequest) {
        engine.loadMedia(request)
        Thread.sleep(150)
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        Thread.sleep(150)
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED, playlistEntryId = 10L)
        Thread.sleep(250)
    }

    @Test
    fun `extension mpv option reaches file load`() {
        loadAndBind(anizoneLikeRequest())

        val loadCmd = fakeClient.executedCommands.first { it.firstOrNull() == "loadfile" }
        val options = if (loadCmd.size > 4) loadCmd[4] else ""
        assertTrue(
            "loadfile options must carry extension demuxer option, was: $options",
            options.contains("demuxer-lavf-o=\"force_mpegts=1\"")
        )
    }

    @Test
    fun `aux subtitle fetched with source transport context`() {
        loadAndBind(anizoneLikeRequest())

        val subAdds = fakeClient.executedCommands.filter { it.firstOrNull() == "sub-add" }
        assertEquals(1, subAdds.size)
        assertEquals("https://cdn.example/subs/en.vtt", subAdds[0][1])
        assertEquals("select", subAdds[0][2])

        val headerFields = fakeClient.stringProperties["http-header-fields"] ?: ""
        assertTrue(
            "http-header-fields must carry source cookie for sub fetch, was: $headerFields",
            headerFields.contains("session=abc123")
        )
        assertEquals("https://site.example/", fakeClient.stringProperties["referrer"])
        assertEquals("AniZoneAgent/1.0", fakeClient.stringProperties["user-agent"])
    }

    @Test
    fun `separate audio attached with source transport context`() {
        loadAndBind(anizoneLikeRequest())

        val audioAdds = fakeClient.executedCommands.filter { it.firstOrNull() == "audio-add" }
        assertEquals(1, audioAdds.size)
        assertEquals("https://cdn.example/audio/en.m3u8", audioAdds[0][1])
        // Beta04: first external audio must be explicitly selected — mpv never
        // auto-selects tracks added with "auto", leaving video-only HLS silent.
        assertEquals("select", audioAdds[0][2])

        // Transport context applied (same dispatcher-serialized path as sub-add).
        val headerFields = fakeClient.stringProperties["http-header-fields"] ?: ""
        assertTrue(headerFields.contains("session=abc123"))
    }

    @Test
    fun `only first of several external audios is selected`() {
        val request = anizoneLikeRequest().copy(
            externalAudioTracks = listOf(
                ExternalAudioTrack("https://cdn.example/audio/en.m3u8", "English", "English", emptyMap()),
                ExternalAudioTrack("https://cdn.example/audio/ja.m3u8", "Japanese", "Japanese", emptyMap())
            )
        )
        loadAndBind(request)

        val audioAdds = fakeClient.executedCommands.filter { it.firstOrNull() == "audio-add" }
        assertEquals(2, audioAdds.size)
        assertEquals("select", audioAdds[0][2])
        assertEquals("auto", audioAdds[1][2])
    }

    @Test
    fun `plain video without aux tracks issues no transport options`() {
        val plain = PlaybackRequest(
            uri = "https://cdn.example/ep1.mp4",
            sourceClass = PlaybackSourceClass.DIRECT_HTTP
        )
        loadAndBind(plain)

        assertTrue(fakeClient.executedCommands.none { it.firstOrNull() == "sub-add" })
        assertTrue(fakeClient.executedCommands.none { it.firstOrNull() == "audio-add" })
        // No per-track transport was set by aux handling (main-file options only).
        assertFalse((fakeClient.stringProperties["http-header-fields"] ?: "").contains("session=abc123"))
    }

    @Test
    fun `aux attach log line never leaks header values`() {
        val line = MpvNetworkOptions.auxAttachLogLine(
            kind = "sub-add",
            url = "https://cdn.example/subs/en.vtt",
            headers = sourceHeaders
        )
        assertTrue(line.contains("sub-add"))
        assertFalse(line.contains("abc123"))
    }
}
