package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scene-style names from text-only sources (Torrentio), 2026-11-02 phone check. */
class SceneNameTest {

    private fun group(text: String) = SourceRowClassifier.parseRelease(text).releaseGroup

    @Test
    fun scene_group_before_notes() {
        assertEquals(
            "Tsundere-Raws",
            group("The Apothecary Diaries S03E05 SUBFRENCH 1080p CR WEB-DL AAC2.0 x264-Tsundere-Raws (VOSTFR, Kusuriya no Hitorigoto 3rd Season)\n👤 378 💾 1.4 GB ⚙️ Nyaa"),
        )
        assertEquals(
            "VARYG",
            group("The Apothecary Diaries S03E01 Locusts 1080p NF WEB-DL AAC2.0 H.264-VARYG (Kusuriya no Hitorigoto 3rd Season, Multi-Subs)\n👤 94 💾 1.4 GB ⚙️ Nyaa"),
        )
        assertEquals("NTb", group("Show.S01E01.1080p.WEB.H.264-NTb.mkv\n👤 5 💾 1 GB ⚙️ Nyaa"))
    }

    @Test
    fun non_groups_stay_null() {
        assertNull(group("Show S01E01 1080p WEB-DL\n👤 5 💾 1 GB ⚙️ Nyaa"))
        assertNull(group("Show S01E01 1080p WEB x264-Multi-Subs\n👤 5 💾 1 GB ⚙️ Nyaa"))
    }

    @Test
    fun family_reads_the_same_group() {
        val meta = StableReleaseMetadataExtractor.extract(
            "The Apothecary Diaries S03E05 SUBFRENCH 1080p CR WEB-DL AAC2.0 x264-Tsundere-Raws (VOSTFR, Kusuriya no Hitorigoto 3rd Season)\n👤 378 💾 1.4 GB ⚙️ Nyaa",
        )
        assertEquals("Tsundere-Raws", meta.group)
    }

    @Test
    fun no_comma_left_inside_brackets() {
        assertEquals("The Apothecary Diaries S03E01 (Kusuriya no Hitorigoto)", ReleaseTitleNormalizer.normalize("The Apothecary Diaries S03E01 (Kusuriya no Hitorigoto, )"))
        assertEquals("Show", ReleaseTitleNormalizer.normalize("Show (, )"))
    }
}
