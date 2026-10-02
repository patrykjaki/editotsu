package ani.dantotsu.media.anime.player

import ani.dantotsu.parsers.DrmParseResult
import ani.dantotsu.parsers.parseDrmMetadata
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD3 D1/D2/D3): DRM transport plan pinned on
 * the JVM. Factories/sources stay device-validated.
 */
class DrmTransportPlanTest {

    @Test
    fun `media source headers preserved exactly`() {
        val headers = mapOf(
            "Authorization" to "Bearer x",
            "Cookie" to "a=b",
            "Referer" to "https://src.example/",
            "User-Agent" to "Ext/1.0"
        )
        assertEquals(headers, DrmTransportPlan.resolveMediaRequestProperties(headers))
    }

    @Test
    fun `extension user agent wins and persists`() {
        val ext = mapOf("User-Agent" to "Ext/1.0", "Cookie" to "a=b")
        val resolved = DrmTransportPlan.ensureUserAgent(ext, "Default/2.0")
        assertEquals("Ext/1.0", resolved["User-Agent"])
        assertEquals("a=b", resolved["Cookie"])
        // Case-insensitive key still counts as present (never duplicated).
        val lower = mapOf("user-agent" to "Ext/1.0")
        val resolvedLower = DrmTransportPlan.ensureUserAgent(lower, "Default/2.0")
        assertEquals(1, resolvedLower.size)
        assertEquals("Ext/1.0", resolvedLower["user-agent"])
        // Absent UA gains the default without touching the rest.
        val bare = mapOf("Cookie" to "a=b")
        val withDefault = DrmTransportPlan.ensureUserAgent(bare, "Default/2.0")
        assertEquals("Default/2.0", withDefault["User-Agent"])
        assertEquals("a=b", withDefault["Cookie"])
    }

    @Test
    fun `media headers and license headers remain separate`() {
        val media = mapOf("Authorization" to "Bearer media", "User-Agent" to "Ext/1.0")
        val license = mapOf("Authorization" to "Bearer license")
        val mediaPlan = DrmTransportPlan.resolveMediaRequestProperties(media)
        // The license map is built only from DrmInfo, never merged in.
        assertEquals(media, mediaPlan)
        assertTrue(!mediaPlan.values.contains("Bearer license"))
        assertEquals("Bearer media", mediaPlan["Authorization"])
    }

    @Test
    fun `no external audio means primary only`() {
        val plan = DrmTransportPlan.planAudioMerge("https://cdn.example/v.mpd", emptyList())
        assertEquals("https://cdn.example/v.mpd", plan.primaryUri)
        assertTrue(!plan.hasSides)
    }

    @Test
    fun `external audio becomes a merge plan with headers`() {
        val plan = DrmTransportPlan.planAudioMerge(
            "https://cdn.example/v.mpd",
            listOf(
                ExternalAudioTrack(
                    url = "https://cdn.example/a-eng.m4a",
                    language = "en",
                    title = "English",
                    headers = mapOf("Referer" to "https://src.example/")
                ),
                ExternalAudioTrack(
                    url = "https://cdn.example/a-jpn.mp3",
                    language = "ja",
                    headers = mapOf("Referer" to "https://src.example/")
                )
            )
        )
        assertTrue(plan.hasSides)
        assertEquals(2, plan.sides.size)
        assertEquals("audio/mp4", plan.sides[0].mime)
        assertEquals("audio/mpeg", plan.sides[1].mime)
        assertEquals(mapOf("Referer" to "https://src.example/"), plan.sides[0].headers)
        assertEquals("en", plan.sides[0].language)
    }

    @Test
    fun `blank and duplicate primary urls are skipped`() {
        val plan = DrmTransportPlan.planAudioMerge(
            "https://cdn.example/v.mpd",
            listOf(
                ExternalAudioTrack(url = "  "),
                ExternalAudioTrack(url = "https://cdn.example/v.mpd"),
                ExternalAudioTrack(url = "https://cdn.example/a.m4a")
            )
        )
        assertEquals(listOf("https://cdn.example/a.m4a"), plan.sides.map { it.url })
    }

    @Test
    fun `audio mime inference deterministic`() {
        assertEquals("audio/mpeg", DrmTransportPlan.audioMimeFor("https://x/a.mp3"))
        assertEquals("audio/mp4", DrmTransportPlan.audioMimeFor("https://x/a.m4a?tok=1"))
        assertEquals("audio/aac", DrmTransportPlan.audioMimeFor("https://x/a.AAC"))
        assertEquals("audio/ogg", DrmTransportPlan.audioMimeFor("https://x/a.ogg"))
        assertEquals("audio/opus", DrmTransportPlan.audioMimeFor("https://x/a.opus"))
        assertEquals("audio/wav", DrmTransportPlan.audioMimeFor("https://x/a.wav"))
        assertEquals("audio/flac", DrmTransportPlan.audioMimeFor("https://x/a.flac"))
        assertNull(DrmTransportPlan.audioMimeFor("https://x/a.unknown"))
    }

    @Test
    fun `subtitle mime covers vtt srt ass`() {
        assertEquals(MimeTypes.TEXT_VTT, DrmTransportPlan.subtitleMimeFor("https://x/s.vtt"))
        assertEquals(MimeTypes.TEXT_VTT, DrmTransportPlan.subtitleMimeFor("https://x/s.webvtt"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, DrmTransportPlan.subtitleMimeFor("https://x/s.srt"))
        assertEquals(MimeTypes.TEXT_SSA, DrmTransportPlan.subtitleMimeFor("https://x/s.ass"))
        assertEquals(MimeTypes.TEXT_SSA, DrmTransportPlan.subtitleMimeFor("https://x/s.ssa"))
        assertEquals(MimeTypes.TEXT_VTT, DrmTransportPlan.subtitleMimeFor("https://x/s.txt"))
    }

    @Test
    fun `distinct-host subtitle does not inherit primary credentials`() {
        val plan = DrmTransportPlan.planSourceHeaders(
            primaryUri = "https://media.example/v.mpd",
            requestHeaders = mapOf(
                "Authorization" to "media-secret",
                "Cookie" to "media-cookie",
                "User-Agent" to "Ext/1.0"
            ),
            subtitles = listOf(
                DrmTransportPlan.SubAttachment(
                    url = "https://different.example/en.srt",
                    mime = MimeTypes.APPLICATION_SUBRIP,
                    headers = mapOf("Referer" to "subtitle-ref"),
                    language = "en",
                    title = "English",
                    selected = true,
                    local = false,
                    id = "https://different.example/en.srt"
                )
            ),
            audioSides = emptyList(),
            defaultUa = "Default/2.0"
        )
        val sub = plan.subtitles["https://different.example/en.srt"]!!
        assertTrue(!sub.values.contains("media-secret"))
        assertTrue(!sub.values.contains("media-cookie"))
        assertEquals("subtitle-ref", sub["Referer"])
        // Primary keeps everything.
        assertEquals("media-secret", plan.primary["Authorization"])
        assertEquals("media-cookie", plan.primary["Cookie"])
    }

    @Test
    fun `same-context subtitle retains explicit headers`() {
        val plan = DrmTransportPlan.planSourceHeaders(
            primaryUri = "https://media.example/v.mpd",
            requestHeaders = mapOf("Authorization" to "media-secret"),
            subtitles = listOf(
                DrmTransportPlan.SubAttachment(
                    url = "https://media.example/en.srt",
                    mime = MimeTypes.APPLICATION_SUBRIP,
                    headers = mapOf(
                        "Authorization" to "media-secret",
                        "Referer" to "https://media.example/"
                    ),
                    language = "en",
                    title = "English",
                    selected = false,
                    local = false,
                    id = "https://media.example/en.srt"
                )
            ),
            audioSides = emptyList(),
            defaultUa = "Default/2.0"
        )
        val sub = plan.subtitles["https://media.example/en.srt"]!!
        assertEquals("media-secret", sub["Authorization"])
        assertEquals("https://media.example/", sub["Referer"])
    }

    @Test
    fun `hls external audio gets adaptive plan`() {
        val plan = DrmTransportPlan.planAudioMerge(
            "https://cdn.example/v.mpd",
            listOf(ExternalAudioTrack(url = "https://cdn.example/a-eng.m3u8", language = "en"))
        )
        assertTrue(plan.hasSides)
        assertEquals(DrmTransportPlan.AudioSourceKind.HLS, plan.sides[0].kind)
    }

    @Test
    fun `dash external audio gets adaptive plan`() {
        val plan = DrmTransportPlan.planAudioMerge(
            "https://cdn.example/v.mpd",
            listOf(ExternalAudioTrack(url = "https://cdn.example/a-jpn.mpd", language = "ja"))
        )
        assertTrue(plan.hasSides)
        assertEquals(DrmTransportPlan.AudioSourceKind.DASH, plan.sides[0].kind)
    }

    @Test
    fun `progressive audio stays progressive`() {
        assertEquals(
            DrmTransportPlan.AudioSourceKind.PROGRESSIVE,
            DrmTransportPlan.audioSourceKind("https://cdn.example/a.mp3")
        )
        assertEquals(
            DrmTransportPlan.AudioSourceKind.HLS,
            DrmTransportPlan.audioSourceKind("https://cdn.example/a.M3U8?tok=1")
        )
    }

    @Test
    fun `caption plan honors source versus custom modes`() {
        val source = SubtitleStyle(mode = SubtitleStyleMode.SOURCE)
        val sourcePlan = DrmTransportPlan.planCaptionStyle(source)
        assertTrue(sourcePlan.applyEmbeddedStyles)
        assertTrue(sourcePlan.applyEmbeddedFontSizes)
        assertTrue(!sourcePlan.useCustomStyle)
        val custom = SubtitleStyle(
            mode = SubtitleStyleMode.CUSTOM,
            textColor = 0xFF112233.toInt(),
            fontSizeSp = 99f,
            bottomMarginDp = -4
        )
        val customPlan = DrmTransportPlan.planCaptionStyle(custom)
        assertTrue(!customPlan.applyEmbeddedStyles)
        assertTrue(!customPlan.applyEmbeddedFontSizes)
        assertTrue(customPlan.useCustomStyle)
        assertEquals(0xFF112233.toInt(), customPlan.fgColor)
        assertEquals(48f, customPlan.fontSizeSp, 0f)
        assertEquals(0, customPlan.bottomMarginDp)
    }

    @Test
    fun `subtitle attachments keep header context`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(
                    url = "https://src.example/en.srt",
                    title = "English",
                    language = "en",
                    selected = true,
                    headers = mapOf("Referer" to "https://src.example/")
                ),
                ExternalSubtitle(url = "  ", title = null, language = null, selected = false)
            )
        )
        assertEquals(1, plan.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, plan[0].mime)
        assertEquals(mapOf("Referer" to "https://src.example/"), plan[0].headers)
        assertEquals(true, plan[0].selected)
    }

    @Test
    fun `local and content subtitles take the multi-scheme path`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(url = "/sdcard/subs/en.srt"),
                ExternalSubtitle(url = "file:///sdcard/subs/jp.ass"),
                ExternalSubtitle(url = "content://media/subs/es.vtt"),
                ExternalSubtitle(url = "https://src.example/de.vtt")
            )
        )
        assertEquals(listOf(true, true, true, false), plan.map { it.local })
    }

    @Test
    fun `live-added remote subtitle joins the plan with headers`() {
        val base = listOf(
            ExternalSubtitle(url = "https://src.example/en.srt", language = "en")
        )
        val live = ExternalSubtitle(
            url = "https://other.example/fr.srt",
            language = "fr",
            selected = true,
            headers = mapOf("Referer" to "https://other.example/")
        )
        val merged = DrmTransportPlan.withLiveSubtitle(base, live)
        assertEquals(2, merged.size)
        val plan = DrmTransportPlan.planSourceHeaders(
            primaryUri = "https://media.example/v.mpd",
            requestHeaders = mapOf("Authorization" to "media-secret"),
            subtitles = DrmTransportPlan.planSubtitleAttachments(merged),
            audioSides = emptyList(),
            defaultUa = "Default/2.0"
        )
        val added = plan.subtitles["https://other.example/fr.srt"]!!
        assertEquals(mapOf("Referer" to "https://other.example/"), added.filterKeys { it != "User-Agent" })
        assertTrue(!added.values.contains("media-secret"))
    }

    @Test
    fun `live-added local subtitle is supported`() {
        val merged = DrmTransportPlan.withLiveSubtitle(
            emptyList(),
            ExternalSubtitle(url = "file:///sdcard/subs/en.srt", language = "en", selected = true)
        )
        val attachments = DrmTransportPlan.planSubtitleAttachments(merged)
        assertEquals(1, attachments.size)
        assertTrue(attachments[0].local)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, attachments[0].mime)
    }

    @Test
    fun `live attachment preserves request identity and start position`() {
        val req = PlaybackRequest(
            uri = "https://media.example/v.mpd",
            startPositionMs = 12345L,
            headers = mapOf("Authorization" to "media-secret")
        )
        val merged = DrmTransportPlan.attachLiveSubtitle(
            req,
            ExternalSubtitle(url = "https://other.example/fr.srt", language = "fr")
        )
        assertEquals("https://media.example/v.mpd", merged.uri)
        assertEquals(12345L, merged.startPositionMs)
        assertEquals(mapOf("Authorization" to "media-secret"), merged.headers)
        assertEquals(1, merged.externalSubtitles.size)
        assertEquals("https://other.example/fr.srt", merged.externalSubtitles[0].url)
    }

    @Test
    fun `custom fontfile wins over unresolved family fallback`() {
        val real = java.io.File.createTempFile("font", ".ttf")
        try {
            val won = DrmTransportPlan.resolveCustomTypefaceSource("MaybeMissing", real)
            assertTrue(won is DrmTransportPlan.CustomTypefaceSource.FontFile)
            val fallback = DrmTransportPlan.resolveCustomTypefaceSource(
                "MaybeMissing",
                java.io.File("/nonexistent/font.ttf")
            )
            assertTrue(fallback is DrmTransportPlan.CustomTypefaceSource.Family)
            assertTrue(
                DrmTransportPlan.resolveCustomTypefaceSource("  ", null) is
                    DrmTransportPlan.CustomTypefaceSource.None
            )
        } finally {
            real.delete()
        }
    }

    @Test
    fun `dash plus opaque url resolves mpd`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            DrmTransportPlan.primaryMimeForFormat(null, ani.dantotsu.parsers.VideoType.DASH)
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            DrmTransportPlan.primaryMimeForFormat("", ani.dantotsu.parsers.VideoType.DASH)
        )
    }

    @Test
    fun `m3u8 plus opaque url resolves m3u8`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            DrmTransportPlan.primaryMimeForFormat(null, ani.dantotsu.parsers.VideoType.M3U8)
        )
    }

    @Test
    fun `explicit mimetype still wins`() {
        assertEquals(
            "video/mp4",
            DrmTransportPlan.primaryMimeForFormat("video/mp4", ani.dantotsu.parsers.VideoType.DASH)
        )
    }

    @Test
    fun `container remains unchanged`() {
        assertNull(
            DrmTransportPlan.primaryMimeForFormat(null, ani.dantotsu.parsers.VideoType.CONTAINER)
        )
    }

    @Test
    fun `explicit ASS MIME plus opaque url stays SSA`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(
                    url = "https://provider.example/subtitle?id=123",
                    language = "en",
                    mimeType = MimeTypes.TEXT_SSA
                )
            )
        )
        assertEquals(1, plan.size)
        assertEquals(MimeTypes.TEXT_SSA, plan[0].mime)
    }

    @Test
    fun `explicit SRT MIME plus query url stays subrip`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(
                    url = "https://provider.example/subtitle?file=en.srt",
                    language = "en",
                    mimeType = MimeTypes.APPLICATION_SUBRIP
                )
            )
        )
        assertEquals(MimeTypes.APPLICATION_SUBRIP, plan[0].mime)
    }

    @Test
    fun `explicit VTT MIME stays VTT`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(
                    url = "https://provider.example/subtitle?id=9",
                    mimeType = MimeTypes.TEXT_VTT
                )
            )
        )
        assertEquals(MimeTypes.TEXT_VTT, plan[0].mime)
    }

    @Test
    fun `explicit MIME beats URL inference`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(
                    url = "https://provider.example/en.vtt",
                    mimeType = MimeTypes.TEXT_SSA
                )
            )
        )
        assertEquals(MimeTypes.TEXT_SSA, plan[0].mime)
    }

    @Test
    fun `no explicit MIME still uses URL inference`() {
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(
                ExternalSubtitle(url = "https://provider.example/en.ass"),
                ExternalSubtitle(url = "https://provider.example/en.srt"),
                ExternalSubtitle(url = "https://provider.example/en.vtt"),
                ExternalSubtitle(url = "https://provider.example/en", mimeType = "  ")
            )
        )
        assertEquals(
            listOf(
                MimeTypes.TEXT_SSA,
                MimeTypes.APPLICATION_SUBRIP,
                MimeTypes.TEXT_VTT,
                MimeTypes.TEXT_VTT
            ),
            plan.map { it.mime }
        )
    }

    @Test
    fun `ttml cache subtitle keeps native TTML`() {
        assertEquals(
            MimeTypes.APPLICATION_TTML,
            DrmTransportPlan.subtitleMimeFor("file:///cache/subs/cached.ttml")
        )
        val plan = DrmTransportPlan.planSubtitleAttachments(
            listOf(ExternalSubtitle(url = "/cache/subs/cached.ttml"))
        )
        assertEquals(MimeTypes.APPLICATION_TTML, plan[0].mime)
        assertTrue(plan[0].local)
    }

    @Test
    fun `authoritative subtitle type mapping is deterministic`() {
        assertEquals(
            MimeTypes.TEXT_VTT,
            DrmTransportPlan.subtitleMimeForType(ani.dantotsu.parsers.SubtitleType.VTT)
        )
        assertEquals(
            MimeTypes.TEXT_SSA,
            DrmTransportPlan.subtitleMimeForType(ani.dantotsu.parsers.SubtitleType.ASS)
        )
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            DrmTransportPlan.subtitleMimeForType(ani.dantotsu.parsers.SubtitleType.SRT)
        )
        assertNull(DrmTransportPlan.subtitleMimeForType(ani.dantotsu.parsers.SubtitleType.UNKNOWN))
        assertNull(DrmTransportPlan.subtitleMimeForType(null))
        // Same input, same output, every time.
        repeat(3) {
            assertEquals(
                MimeTypes.TEXT_SSA,
                DrmTransportPlan.subtitleMimeForType(ani.dantotsu.parsers.SubtitleType.ASS)
            )
        }
    }

    @Test
    fun `blank primary explicit MIME falls back to VideoType`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            DrmTransportPlan.primaryMimeForFormat("", ani.dantotsu.parsers.VideoType.DASH)
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            DrmTransportPlan.primaryMimeForFormat("   ", ani.dantotsu.parsers.VideoType.M3U8)
        )
        assertNull(
            DrmTransportPlan.primaryMimeForFormat("", ani.dantotsu.parsers.VideoType.CONTAINER)
        )
    }

    @Test
    fun `MPV clear-content selection remains unchanged`() {
        // No DRM signal anywhere: parser clear, policy clear, no MIME needed.
        assertTrue(
            parseDrmMetadata(mapOf("unrelated" to "x")) is DrmParseResult.Clear
        )
        assertEquals(
            DrmBackendPolicy.BackendDecision.ClearMpv,
            DrmBackendPolicy.decideForVideo(null, null) { true }
        )
    }
}
