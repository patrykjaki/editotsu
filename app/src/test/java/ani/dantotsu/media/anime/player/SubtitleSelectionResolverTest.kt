package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionResolverTest {

    @Test
    fun testParsePreference() {
        assertEquals(SubtitleSelection.Off, SubtitleSelectionResolver.parsePreference("None"))
        assertEquals(SubtitleSelection.Off, SubtitleSelectionResolver.parsePreference("none"))
        assertEquals(SubtitleSelection.Off, SubtitleSelectionResolver.parsePreference("Off"))

        val embedded = SubtitleSelectionResolver.parsePreference("Embedded:eng")
        assertTrue(embedded is SubtitleSelection.Embedded)
        assertEquals("eng", (embedded as SubtitleSelection.Embedded).stableTrackKey)

        val external = SubtitleSelectionResolver.parsePreference("External:English [Full]")
        assertTrue(external is SubtitleSelection.External)
        assertEquals("English [Full]", (external as SubtitleSelection.External).stableSubtitleKey)

        val auto = SubtitleSelectionResolver.parsePreference("English")
        assertTrue(auto is SubtitleSelection.Auto)
        assertEquals("English", (auto as SubtitleSelection.Auto).preferredLanguage)

        val autoNull = SubtitleSelectionResolver.parsePreference(null)
        assertTrue(autoNull is SubtitleSelection.Auto)
        assertNull((autoNull as SubtitleSelection.Auto).preferredLanguage)
    }

    @Test
    fun testResolveSubtitleTracks() {
        val tracks = listOf(
            PlayerTrack(id = 1, name = "Japanese", type = TrackType.AUDIO, language = "jpn"),
            PlayerTrack(id = 2, name = "English Signs", type = TrackType.SUBTITLE, language = "eng", default = false),
            PlayerTrack(id = 3, name = "English Full", type = TrackType.SUBTITLE, language = "eng", default = true),
            PlayerTrack(id = 4, name = "Spanish", type = TrackType.SUBTITLE, language = "spa", default = false)
        )

        // Off should resolve to null (sid=no)
        assertNull(SubtitleSelectionResolver.resolve(SubtitleSelection.Off, tracks))

        // Embedded exact key
        assertEquals(2, SubtitleSelectionResolver.resolve(SubtitleSelection.Embedded("English Signs"), tracks))
        assertEquals(3, SubtitleSelectionResolver.resolve(SubtitleSelection.Embedded("English Full"), tracks))

        // External track matching
        assertEquals(4, SubtitleSelectionResolver.resolve(SubtitleSelection.External("Spanish"), tracks))

        // Auto with preferred language
        assertEquals(3, SubtitleSelectionResolver.resolve(SubtitleSelection.Auto("eng"), tracks))
        assertEquals(4, SubtitleSelectionResolver.resolve(SubtitleSelection.Auto("spa"), tracks))

        // Auto fallback to default
        assertEquals(3, SubtitleSelectionResolver.resolve(SubtitleSelection.Auto("fre"), tracks))
    }

    @Test
    fun testResolveSubtitleUri() {
        val embedUrl = "https://server.anime.net/embed/ep123"
        val mediaUrl = "https://cdn.anime.net/hls/ep123/master.m3u8"

        // Absolute URLs
        assertEquals("https://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("https://cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))
        assertEquals("http://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("http://cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))
        assertEquals("file:///sdcard/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("file:///sdcard/subs/en.vtt", embedUrl, mediaUrl))

        // Protocol-relative
        assertEquals("https://cdn.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("//cdn.anime.net/subs/en.vtt", embedUrl, mediaUrl))

        // Root-relative
        assertEquals("https://server.anime.net/subs/en.vtt", SubtitleSelectionResolver.resolveSubtitleUri("/subs/en.vtt", embedUrl, mediaUrl))

        // Relative paths
        assertEquals("https://server.anime.net/embed/sub.ass", SubtitleSelectionResolver.resolveSubtitleUri("sub.ass", embedUrl, mediaUrl))
        assertEquals("https://server.anime.net/sub.ass", SubtitleSelectionResolver.resolveSubtitleUri("../sub.ass", embedUrl, mediaUrl))
    }
}
