package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Test

/** Card titles from The Apothecary Diaries S3 releases, 2026-11-02. */
class ShowTitlesTest {
    private val english = "The Apothecary Diaries Season 3"
    private val romaji = "Kusuriya no Hitorigoto 3rd Season"
    private val all = listOf(english, romaji, "The Pharmacist's Monologue Season 3")

    private val en = ShowTitles(english, all)
    private val ro = ShowTitles(romaji, all)

    @Test
    fun repeated_names_in_notes_go() {
        assertEquals("The Apothecary Diaries S03E01", en.localize("The Apothecary Diaries S03E01 (Kusuriya no Hitorigoto, )"))
        assertEquals("The Apothecary Diaries S03E01 Locusts (Multi-Subs)", en.localize("The Apothecary Diaries S03E01 Locusts (Kusuriya no Hitorigoto 3rd Season, Multi-Subs)"))
        assertEquals("The Apothecary Diaries S03E01", en.localize("The Apothecary Diaries S03E01 | Kusuriya no Hitorigoto 3rd Season"))
        assertEquals("The Apothecary Diaries - S03E05", en.localize("Kusuriya no Hitorigoto (The Apothecary Diaries) - S03E05"))
    }

    @Test
    fun leading_name_in_the_preferred_language() {
        assertEquals("The Apothecary Diaries - 49v2", en.localize("Kusuriya no Hitorigoto - 49v2"))
        assertEquals("The Apothecary Diaries Season 3 - 01v2", en.localize("Kusuriya no Hitorigoto 3rd Season - 01v2"))
        assertEquals("Kusuriya no Hitorigoto S03E01 Locusts", ro.localize("The Apothecary Diaries S03E01 Locusts"))
        assertEquals("Kusuriya no Hitorigoto - 49v2", ro.localize("Kusuriya no Hitorigoto - 49v2"))
    }

    @Test
    fun unknown_names_stay() {
        assertEquals("Some Other Show - 05", en.localize("Some Other Show - 05"))
        assertEquals("Kusuriyaa no Hitorigoto - 05", en.localize("Kusuriyaa no Hitorigoto - 05"))
        assertEquals("药屋少女的呢喃 第三季 / Kusuriya no Hitorigoto [49]", en.localize("药屋少女的呢喃 第三季 / Kusuriya no Hitorigoto [49]"))
    }

    @Test
    fun base_names() {
        assertEquals("The Apothecary Diaries", ShowTitles.base(english))
        assertEquals("Kusuriya no Hitorigoto", ShowTitles.base(romaji))
        assertEquals("Frieren", ShowTitles.base("Frieren (2023) Season 2"))
    }

    @Test
    fun builder_shows_localized_title_but_keeps_raw_fields() {
        val b = PickerRowsBuilder()
        b.showTitles = en
        val ext = object : ani.dantotsu.parsers.VideoExtractor() {
            override val server = ani.dantotsu.parsers.VideoServer(
                "[ASW] Kusuriya no Hitorigoto - 49v2 [1080p HEVC x265 10Bit][AAC]\n👤 174 💾 219.6 MB ⚙️ Nyaa",
                ani.dantotsu.FileUrl("magnet:?xt=urn:btih:" + "a".repeat(40)),
                mapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to "p"),
            )
            init { videos = listOf(ani.dantotsu.parsers.Video(null, ani.dantotsu.parsers.VideoType.CONTAINER, ani.dantotsu.FileUrl("magnet:?xt=urn:btih:" + "a".repeat(40)))) }
            override suspend fun extract() = throw UnsupportedOperationException()
        }
        val built = b.buildWithViews(listOf(ext), null, null, "x")
        val vm = built.views.values.single()
        assertEquals("ASW", vm.header)
        assertEquals("The Apothecary Diaries - 49v2", vm.cleanSubtitle)
        assertEquals("ASW", built.rows.single().header)
    }
}
