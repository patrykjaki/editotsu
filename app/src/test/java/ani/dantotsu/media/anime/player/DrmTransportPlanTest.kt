package ani.dantotsu.media.anime.player

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
        val plan = DrmTransportPlan.planRequestHeaders(
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
                    selected = true
                )
            ),
            audioSides = emptyList(),
            defaultUa = "Default/2.0"
        )
        val sub = plan["https://different.example/en.srt"]!!
        assertTrue(!sub.values.contains("media-secret"))
        assertTrue(!sub.values.contains("media-cookie"))
        assertEquals("subtitle-ref", sub["Referer"])
        // Primary keeps everything.
        val primary = plan["https://media.example/v.mpd"]!!
        assertEquals("media-secret", primary["Authorization"])
        assertEquals("media-cookie", primary["Cookie"])
    }

    @Test
    fun `same-context subtitle retains explicit headers`() {
        val plan = DrmTransportPlan.planRequestHeaders(
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
                    selected = false
                )
            ),
            audioSides = emptyList(),
            defaultUa = "Default/2.0"
        )
        val sub = plan["https://media.example/en.srt"]!!
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
}
