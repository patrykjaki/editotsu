package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguageMatchTest {
    private fun pick(labels: List<String>, lang: String?) =
        SubtitleLanguageMatch.pick(labels, lang) { it }

    private fun sub(id: Int, lang: String?, name: String? = null, default: Boolean = false, forced: Boolean = false) =
        PlayerTrack(id = id, type = TrackType.SUBTITLE, name = name, language = lang, default = default, forced = forced)

    @Test
    fun `extension pick prefers the user's language over an earlier English`() {
        assertEquals("German", pick(listOf("English", "German"), "German"))
    }

    @Test
    fun `extension pick does not take French for Polish`() {
        assertEquals("Polish", pick(listOf("French", "Polish"), "Polish"))
    }

    @Test
    fun `no match falls back to English then the first`() {
        assertEquals("English", pick(listOf("French", "English"), "Polish"))
        assertEquals("French", pick(listOf("French", "Spanish"), "Polish"))
        assertNull(pick(emptyList(), "Polish"))
    }

    @Test
    fun `two letter codes are not substring matched`() {
        assertFalse(SubtitleLanguageMatch.matches(null, "French", "en"))
        assertFalse(SubtitleLanguageMatch.matches(null, "Slovenian", "English"))
        assertFalse(SubtitleLanguageMatch.matches(null, "Armenian", "en"))
        assertFalse(SubtitleLanguageMatch.matches(null, "Subtitles", "it"))
        assertFalse(SubtitleLanguageMatch.matches(null, "Bulgarian", "ar"))
        assertFalse(SubtitleLanguageMatch.matches("Slovenian", null, "English"))
    }

    @Test
    fun `Slovenian and Slovak stay apart`() {
        assertTrue(SubtitleLanguageMatch.matches("Slovenian", null, "Slovenian"))
        assertFalse(SubtitleLanguageMatch.matches("Slovenian", null, "Slovak"))
        assertTrue(SubtitleLanguageMatch.matches("slv", null, "Slovenian"))
        assertFalse(SubtitleLanguageMatch.matches("slk", null, "Slovenian"))
    }

    @Test
    fun `codes and names in both directions`() {
        assertTrue(SubtitleLanguageMatch.matches("pol", null, "Polish"))
        assertTrue(SubtitleLanguageMatch.matches("pl", null, "Polish"))
        assertTrue(SubtitleLanguageMatch.matches("Polish", null, "pol"))
        assertTrue(SubtitleLanguageMatch.matches("en-US", null, "English"))
        assertTrue(SubtitleLanguageMatch.matches("pt-BR", null, "Portuguese"))
        assertTrue(SubtitleLanguageMatch.matches("English [CC]", null, "English"))
        assertTrue(SubtitleLanguageMatch.matches("Portuguese (Brazil)", null, "Portuguese"))
        assertTrue(SubtitleLanguageMatch.matches(null, "Polski (napisy)", "Polish"))
        assertTrue(SubtitleLanguageMatch.matches("ger", null, "German"))
        assertFalse(SubtitleLanguageMatch.matches("eng", null, "German"))
    }

    @Test
    fun `the generic Subtitles title is no language`() {
        val tracks = listOf(sub(1, null, "Subtitles"), sub(2, "ita", "Italiano"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, null, "Italian")?.id)
        assertEquals(0, SubtitleLanguageMatch.tier(null, "Subtitles", "Italian"))
    }

    @Test
    fun `settings language index maps to names`() {
        assertEquals("Polish", SubtitleLanguageMatch.settingsLanguage(28))
        assertEquals("English", SubtitleLanguageMatch.settingsLanguage(9))
        assertEquals("English", SubtitleLanguageMatch.settingsLanguage(999))
        assertEquals("English", SubtitleLanguageMatch.settingsLanguage(null))
    }

    @Test
    fun `embedded pick uses the settings language on first watch`() {
        val tracks = listOf(sub(1, "eng", "Full", default = true), sub(2, "pol", "Polski"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, null, "Polish")?.id)
        assertEquals(1, SubtitleLanguageMatch.bestEmbedded(tracks, null, "German")?.id)
    }

    @Test
    fun `embedded pick ranks language before track kind`() {
        val tracks = listOf(sub(1, "eng", "Full"), sub(2, "fre", "French"), sub(3, "eng", "Signs & Songs"))
        assertEquals("French no longer ties English", 1, SubtitleLanguageMatch.bestEmbedded(tracks, null, "English")?.id)
        val polishSigns = listOf(sub(1, "eng", "Full"), sub(2, "pol", "Signs"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(polishSigns, null, "Polish")?.id)
    }

    @Test
    fun `embedded pick prefers full dialogue within a language`() {
        val tracks = listOf(sub(1, "eng", "Signs & Songs"), sub(2, "eng", "Full Subtitles"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, null, "English")?.id)
    }

    @Test
    fun `saved per-episode language wins over settings`() {
        val tracks = listOf(sub(1, "eng"), sub(2, "spa"), sub(3, "pol"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, "Spanish", "Polish")?.id)
    }

    @Test
    fun `saved Off picks nothing`() {
        assertNull(SubtitleLanguageMatch.bestEmbedded(listOf(sub(1, "eng")), "None", "English"))
    }

    @Test
    fun `local and online saved values fall back to settings`() {
        val tracks = listOf(sub(1, "eng"), sub(2, "pol"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, "[Local] ep1.ass", "Polish")?.id)
    }

    @Test
    fun `saved embedded key restores the same of two English tracks`() {
        val signs = sub(2, "eng", "Signs & Songs")
        val full = sub(3, "eng", "Full")
        val key = SubtitleLanguageMatch.embeddedKey(full.id, full.language, full.name)
        assertEquals(3, SubtitleLanguageMatch.bestEmbedded(listOf(signs, full), key, "English")?.id)
        assertTrue(SubtitleLanguageMatch.isSavedEmbedded(key, SubtitleLanguageMatch.EmbeddedRef(3, "eng", "Full")))
        assertFalse(SubtitleLanguageMatch.isSavedEmbedded(key, SubtitleLanguageMatch.EmbeddedRef(2, "eng", "Signs & Songs")))
        assertEquals("Full", SubtitleLanguageMatch.embeddedLabel(key))
    }

    @Test
    fun `saved embedded key follows the title when ids move`() {
        val key = SubtitleLanguageMatch.embeddedKey(3, "eng", "Full")
        val other = listOf(sub(4, "eng", "Signs & Songs"), sub(5, "eng", "Full"))
        assertEquals(5, SubtitleLanguageMatch.bestEmbedded(other, key, "Polish")?.id)
    }

    @Test
    fun `saved embedded key whose track is gone falls back to its language`() {
        val key = SubtitleLanguageMatch.embeddedKey(3, "spa", "Latino")
        val tracks = listOf(sub(1, "eng", "Full"), sub(2, "spa", "Castellano"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, key, "English")?.id)
    }

    @Test
    fun `old Embedded language values still restore`() {
        val tracks = listOf(sub(1, "jpn"), sub(2, "eng", "Signs"), sub(3, "eng", "Full"))
        assertEquals(2, SubtitleLanguageMatch.bestEmbedded(tracks, "Embedded:eng", "Polish")?.id)
        assertEquals(3, SubtitleLanguageMatch.bestEmbedded(tracks, "Embedded:3", "Polish")?.id)
        assertEquals("eng", SubtitleLanguageMatch.embeddedLabel("Embedded:eng"))
        assertFalse(
            "an old key marks no row by itself",
            SubtitleLanguageMatch.isSavedEmbedded("Embedded:eng", SubtitleLanguageMatch.EmbeddedRef(2, "eng", "Signs"))
        )
    }

    @Test
    fun `key parsing keeps a title with separators`() {
        val key = SubtitleLanguageMatch.parseEmbeddedKey(SubtitleLanguageMatch.embeddedKey(7, "eng", "Full | CR"))!!
        assertEquals(7, key.id)
        assertEquals("eng", key.language)
        assertEquals("Full | CR", key.title)
        assertNull(key.legacy)
    }

    @Test
    fun `drm auto pick uses the same matcher and settings fallback`() {
        fun ref(id: Int, lang: String?, name: String? = null) =
            DrmSubtitleIntentMachine.TrackRef(id, null, lang, name)
        val tracks = listOf(ref(0, "fre", "French"), ref(1, "eng"), ref(2, "pol"))
        assertEquals(2, resolveDrmAutoSubtitleId(tracks, null, "Polish"))
        assertEquals(1, resolveDrmAutoSubtitleId(tracks, null, null))
        assertEquals(9, resolveDrmAutoSubtitleId(tracks + ref(9, "pol"), SubtitleLanguageMatch.embeddedKey(9, "pol", null), null))
        assertNull("an explicit pick that is gone stays unmatched on DRM", resolveDrmAutoSubtitleId(tracks, SubtitleLanguageMatch.embeddedKey(9, "pol", null), null))
        assertEquals(1, resolveDrmAutoSubtitleId(listOf(ref(0, null, "French"), ref(1, "en")), "en"))
    }
}
