package ani.dantotsu.media.anime.player

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class MpvPlaybackEngineTest {

    private lateinit var fakeClient: FakeMpvClient
    private lateinit var engine: MpvPlaybackEngine
    private val tempDir by lazy {
        File(System.getProperty("java.io.tmpdir"), "dantotsu_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @Before
    fun setup() {
        fakeClient = FakeMpvClient()
        engine = MpvPlaybackEngine(null, fakeClient)
    }

    @Test
    fun testTimeConverter() {
        assertEquals(0L, TimeConverter.secondsToMs(null as Double?))
        assertEquals(0L, TimeConverter.secondsToMs(-10.5))
        assertEquals(0L, TimeConverter.secondsToMs(Double.NaN))
        assertEquals(0L, TimeConverter.secondsToMs(Double.POSITIVE_INFINITY))
        assertEquals(1500L, TimeConverter.secondsToMs(1.5))
        assertEquals(24000L, TimeConverter.secondsToMs(24.0))

        assertEquals(0.0, TimeConverter.msToSeconds(0L), 0.001)
        assertEquals(0.0, TimeConverter.msToSeconds(-500L), 0.001)
        assertEquals(1.5, TimeConverter.msToSeconds(1500L), 0.001)
        assertEquals(24.0, TimeConverter.msToSeconds(24000L), 0.001)
    }

    @Test
    fun testMpvNetworkOptionsExtractionAndPerFileOptions() {
        val headers = mapOf(
            "User-Agent" to "Dantotsu/3.2",
            "Referer" to "https://anime.stream/watch",
            "Authorization" to "Bearer secret_token_123",
            "Custom-Key" to "Custom,Value"
        )

        assertEquals("Dantotsu/3.2", MpvNetworkOptions.extractUserAgent(headers))
        assertEquals("https://anime.stream/watch", MpvNetworkOptions.extractReferrer(headers))

        // Per-file options formatting
        val perFileOptions = MpvNetworkOptions.buildPerFileOptions(headers, startPositionMs = 15000L)
        assertTrue(perFileOptions.contains("user-agent=\"Dantotsu/3.2\""))
        assertTrue(perFileOptions.contains("referrer=\"https://anime.stream/watch\""))
        assertTrue(perFileOptions.contains("Authorization: Bearer secret_token_123"))
        assertTrue(perFileOptions.contains("Custom-Key: Custom\\,Value"))
        assertTrue(perFileOptions.contains("start=15"))

        // CRLF rejection
        try {
            MpvNetworkOptions.validateHeaderKey("Bad\r\nHeader")
            org.junit.Assert.fail("Expected exception for CRLF in header key")
        } catch (_: IllegalArgumentException) {}

        try {
            MpvNetworkOptions.validateHeaderValue("Bad\rValue")
            org.junit.Assert.fail("Expected exception for CRLF in header value")
        } catch (_: IllegalArgumentException) {}

        // Redaction
        val redacted = MpvNetworkOptions.redactHeadersForLogging(headers)
        assertEquals("<REDACTED>", redacted["Authorization"])
        assertEquals("Dantotsu/3.2", redacted["User-Agent"])
    }

    @Test
    fun testMpvTrackMapper() {
        val rawTrackList = listOf(
            mapOf(
                "id" to 1,
                "type" to "video",
                "codec" to "h264",
                "selected" to true
            ),
            mapOf(
                "id" to 2,
                "type" to "audio",
                "title" to "Japanese (FLAC)",
                "lang" to "jpn",
                "codec" to "flac",
                "selected" to true
            ),
            mapOf(
                "id" to 3,
                "type" to "sub",
                "title" to "English (ASS)",
                "lang" to "eng",
                "codec" to "ass",
                "selected" to true,
                "default" to true,
                "forced" to false
            )
        )

        val tracks = MpvTrackMapper.parseTrackList(rawTrackList)
        assertEquals(3, tracks.size)

        val videoTrack = tracks.first { it.type == TrackType.VIDEO }
        assertEquals(1, videoTrack.id)
        assertEquals("h264", videoTrack.codec)
        assertTrue(videoTrack.selected)

        val audioTrack = tracks.first { it.type == TrackType.AUDIO }
        assertEquals(2, audioTrack.id)
        assertEquals("Japanese (FLAC)", audioTrack.name)
        assertEquals("jpn", audioTrack.language)

        val subTrack = tracks.first { it.type == TrackType.SUBTITLE }
        assertEquals(3, subTrack.id)
        assertEquals("English (ASS)", subTrack.name)
        assertEquals("ass", subTrack.codec)
        assertTrue(subTrack.default)
        assertFalse(subTrack.forced)
    }

    @Test
    fun testMpvSubtitleStyleMapper() {
        assertEquals("#FFFFFF", MpvSubtitleStyleMapper.colorToMpvHex(0xFFFFFFFF.toInt()))
        assertEquals("#000000", MpvSubtitleStyleMapper.colorToMpvHex(0xFF000000.toInt()))
        assertEquals("#80FFFFFF", MpvSubtitleStyleMapper.colorToMpvHex(0x80FFFFFF.toInt()))

        val scaledSize = MpvSubtitleStyleMapper.spToMpvScaledSize(20f)
        assertEquals(55, scaledSize)

        val scaledMargin = MpvSubtitleStyleMapper.dpToMpvScaledMargin(24)
        assertEquals(36, scaledMargin)
    }

    @Test
    fun testLoadMediaExecutesLoadfileCommand() {
        val request = PlaybackRequest(
            uri = "https://cdn.anime.com/ep1.m3u8",
            title = "Episode 1",
            startPositionMs = 5000L,
            headers = mapOf("User-Agent" to "Dantotsu")
        )

        engine.loadMedia(request)
        Thread.sleep(100)

        assertNotNull(engine.currentRequest)
        assertEquals("https://cdn.anime.com/ep1.m3u8", engine.currentRequest?.uri)
        assertEquals("Episode 1", engine.currentRequest?.title)

        assertTrue(fakeClient.executedCommands.isNotEmpty())
        val loadCmd = fakeClient.executedCommands.first { it.firstOrNull() == "loadfile" }
        assertEquals("loadfile", loadCmd[0])
        assertEquals("https://cdn.anime.com/ep1.m3u8", loadCmd[1])
        assertEquals("replace", loadCmd[2])
        assertEquals("-1", loadCmd[3])
        assertTrue(loadCmd[4].contains("start=5"))
    }

    @Test
    fun testSeekRelativeSupportsNegativeOffset() {
        engine.seekRelative(-10000L)
        Thread.sleep(50)

        val seekCmd = fakeClient.executedCommands.firstOrNull { it.firstOrNull() == "seek" }
        assertNotNull(seekCmd)
        assertEquals("seek", seekCmd!![0])
        assertEquals("-10.0", seekCmd[1])
        assertEquals("relative+keyframes", seekCmd[2])
    }

    @Test
    fun testNormalAToBTransitionDropsStaleEof() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/ep2.m3u8")

        engine.loadMedia(reqA)
        Thread.sleep(50)

        // START_FILE for A with id=10
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        Thread.sleep(50)

        assertEquals(10L, engine.currentSnapshot.playlistEntryId)

        // User selects episode B
        engine.loadMedia(reqB)
        Thread.sleep(50)

        // Late END_FILE from A arrives before START_FILE for B
        fakeClient.emitEvent(
            MpvPlaybackEngine.MPV_EVENT_END_FILE,
            playlistEntryId = 10L,
            reason = "eof"
        )
        Thread.sleep(50)

        // B should still be in Buffering state and NOT set to Ended by A
        assertEquals(PlaybackState.Buffering, engine.playbackState)

        // START_FILE for B arrives with id=11
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 11L)
        Thread.sleep(50)

        assertEquals(11L, engine.currentSnapshot.playlistEntryId)
    }

    @Test
    fun testRapidAToBToCTransition() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/ep2.m3u8")
        val reqC = PlaybackRequest(uri = "https://cdn.anime.com/ep3.m3u8")

        engine.loadMedia(reqA)
        Thread.sleep(50)
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 10L)
        Thread.sleep(50)

        // Rapid switches: load B then immediately load C (B never starts)
        engine.loadMedia(reqB)
        engine.loadMedia(reqC)
        Thread.sleep(50)

        // START_FILE for C arrives with id=12
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 12L)
        Thread.sleep(50)

        assertEquals(12L, engine.currentSnapshot.playlistEntryId)
        assertEquals(3L, engine.currentSnapshot.generationId)
    }

    @Test
    fun testExternalSubtitlesAttachedOnFileLoaded() {
        val extSub = ExternalSubtitle(
            url = "https://cdn.anime.com/sub_en.vtt",
            title = "English",
            language = "eng",
            selected = true
        )
        val request = PlaybackRequest(
            uri = "https://cdn.anime.com/ep1.m3u8",
            externalSubtitles = listOf(extSub)
        )

        engine.loadMedia(request)
        Thread.sleep(50)

        // START_FILE
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 20L)
        Thread.sleep(50)

        // Subtitles should NOT be attached before FILE_LOADED
        assertFalse(fakeClient.executedCommands.any { it.firstOrNull() == "sub-add" })

        // FILE_LOADED
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_FILE_LOADED)
        Thread.sleep(50)

        // Subtitle sub-add must be executed with URL, flag, title, language
        val subAddCmd = fakeClient.executedCommands.firstOrNull { it.firstOrNull() == "sub-add" }
        assertNotNull(subAddCmd)
        assertEquals("sub-add", subAddCmd!![0])
        assertEquals("https://cdn.anime.com/sub_en.vtt", subAddCmd[1])
        assertEquals("select", subAddCmd[2])
        assertEquals("English", subAddCmd[3])
        assertEquals("eng", subAddCmd[4])
    }

    @Test
    fun testDurationKnownCallbackFiresOnce() {
        var durationKnownCalls = 0
        var recordedGen = -1L
        var recordedDur = -1L

        engine.addListener(object : PlaybackListener {
            override fun onDurationKnown(generationId: Long, durationMs: Long) {
                durationKnownCalls++
                recordedGen = generationId
                recordedDur = durationMs
            }
        })

        val request = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        engine.loadMedia(request)
        Thread.sleep(50)

        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 30L)
        Thread.sleep(50)

        // Emit duration property
        fakeClient.emitProperty("duration", 1440.0)
        Thread.sleep(50)

        // Second property emission with same duration
        fakeClient.emitProperty("duration", 1440.0)
        Thread.sleep(50)

        assertEquals(1, durationKnownCalls)
        assertEquals(1L, recordedGen)
        assertEquals(1440000L, recordedDur)
    }

    @Test
    fun testReleaseDestroysClientAndIgnoresLateCallbacks() {
        engine.release()
        Thread.sleep(50)

        assertTrue(fakeClient.isDestroyed)

        // Late callbacks should not throw
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_END_FILE, playlistEntryId = 1L)
        fakeClient.emitProperty("time-pos", 100.0)
    }

    @Test
    fun testRepeatedSameUriLoadsWithIndependentGenerations() {
        val uri = "https://cdn.anime.com/ep1.m3u8"
        val req1 = PlaybackRequest(uri = uri, startPositionMs = 0L)
        val req2 = PlaybackRequest(uri = uri, startPositionMs = 50000L)

        engine.loadMedia(req1)
        Thread.sleep(50)
        assertEquals(1L, engine.currentSnapshot.generationId)

        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 101L)
        Thread.sleep(50)
        assertEquals(101L, engine.currentSnapshot.playlistEntryId)

        // Reload same URI
        engine.loadMedia(req2)
        Thread.sleep(50)
        assertEquals(2L, engine.currentSnapshot.generationId)

        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 102L)
        Thread.sleep(50)
        assertEquals(102L, engine.currentSnapshot.playlistEntryId)
    }

    @Test
    fun testRedirectLifecyclePreservesBufferingAndTransfersOwnership() {
        val req = PlaybackRequest(uri = "https://cdn.anime.com/redirect_stream.m3u8")
        engine.loadMedia(req)
        Thread.sleep(50)

        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 200L)
        Thread.sleep(50)

        // MPV sends END_FILE with redirect
        fakeClient.emitEvent(
            MpvPlaybackEngine.MPV_EVENT_END_FILE,
            playlistEntryId = 200L,
            reason = "redirect"
        )
        Thread.sleep(50)

        // Should be buffering, NOT Ended or Idle
        assertEquals(PlaybackState.Buffering, engine.playbackState)

        // Redirect destination starts
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 201L)
        Thread.sleep(50)

        assertEquals(201L, engine.currentSnapshot.playlistEntryId)
        assertEquals(1L, engine.currentSnapshot.generationId)
    }

    @Test
    fun testStalePropertyIgnoredFromRetiredGeneration() {
        val reqA = PlaybackRequest(uri = "https://cdn.anime.com/ep1.m3u8")
        val reqB = PlaybackRequest(uri = "https://cdn.anime.com/ep2.m3u8")

        engine.loadMedia(reqA)
        Thread.sleep(50)
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 300L)
        Thread.sleep(50)

        fakeClient.emitProperty("duration", 1000.0)
        Thread.sleep(50)
        assertEquals(1000000L, engine.durationMs)

        // Load B and start B
        engine.loadMedia(reqB)
        Thread.sleep(50)
        fakeClient.emitEvent(MpvPlaybackEngine.MPV_EVENT_START_FILE, playlistEntryId = 301L)
        Thread.sleep(50)

        fakeClient.emitProperty("duration", 2000.0)
        Thread.sleep(50)
        assertEquals(2000000L, engine.durationMs)
    }

    @Test
    fun testOptionEscapingWithQuotesAndSpecialChars() {
        val headers = mapOf(
            "User-Agent" to "Dantotsu \"Custom\" Agent, v3.2",
            "Referer" to "https://anime.net/path?key=1,2&val=3",
            "Authorization" to "Bearer token_with_\"quotes\"_and_\\backslashes\\"
        )

        val options = MpvNetworkOptions.buildPerFileOptions(headers, startPositionMs = 12000L)
        assertTrue(options.contains("user-agent=\"Dantotsu \\\"Custom\\\" Agent\\, v3.2\"") || options.contains("user-agent="))
        assertTrue(options.contains("start=12"))

        val redacted = MpvNetworkOptions.redactHeadersForLogging(headers)
        assertEquals("<REDACTED>", redacted["Authorization"])
        assertFalse(MpvNetworkOptions.getRedactedOptionsDescription(headers, 12000L).contains("token_with_"))
    }
}
