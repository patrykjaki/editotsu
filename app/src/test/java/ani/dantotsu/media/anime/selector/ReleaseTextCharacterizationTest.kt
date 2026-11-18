package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.ExtensionRepoMetaHelper
import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins what the picker parsers return today for one row of each text shape they
 * handle, so a rewrite of how a line is recognised cannot change the result
 * unnoticed. Indexer and group names are placeholders with the same shape
 * (letters, digits, hyphens, length) as the names sources really send.
 */
class ReleaseTextCharacterizationTest {

    private val rows: List<Pair<String, String>> = listOf(
        "hoster-quality line, multi subs, flag language line" to
            "IdxOne - 1080p\n" +
            "[GroupA] Show Title S01E01 1080p ADN WEB-DL AAC2.0 H.264 (Multi-Subs)\n" +
            "👤 954 💾 584.52 MB ⚙️ IdxOne\n" +
            "Multi Subs / 🇬🇧 / 🇫🇷",
        "4k hoster line, bracket tags, flags only" to
            "IdxTwo - 4k\n" +
            "[Grp Subs] Show Title - 01 [2160p][HEVC 10bit AAC][UNCENSORED][PT-BR][ES][IT]\n" +
            "👤 15 💾 1.21 GB ⚙️ IdxTwo\n" +
            "🇵🇹 / 🇲🇽",
        "dual audio with dubbed language line" to
            "IdxOne - 1080p\n" +
            "[GroupA] Show Title S01E01 1080p WEB-DL DUAL AAC2.0 H.264 (Dual-Audio Multi-Subs)\n" +
            "👤 58 💾 608.38 MB ⚙️ IdxOne\n" +
            "Dubbed / Multi Audio / 🇬🇧 / 🇯🇵",
        "pack with file-name line" to
            "IdxOne - 1080p\n" +
            "[GroupA] Show Title (Season 1) [1080p][Batch]\n" +
            "[GroupA] Show Title - 03 (1080p) [ABCD1234].mkv\n" +
            "👤 120 💾 4.2 GB ⚙️ IdxOne",
        "scene name with suffix group, digit indexer" to
            "2448x - 1080p\n" +
            "Show.Title.S02E01.1080p.WEB.H264-GRPX\n" +
            "👤 30 💾 1.1 GB ⚙️ 2448x",
        "folder marker plus cog group" to
            "📂 IdxOne\n" +
            "[GroupA] Show Title - 05 (1080p) [ABCD1234].mkv\n" +
            "👤 300 💾 1.4 GB ⚙️ GroupB",
        "indexer word before the group bracket" to
            "IdxOne [GroupA] Show Title - 05 (1080p)\n" +
            "👤 12 💾 700 MB ⚙️ IdxOne",
        "group release with no language words" to
            "[GroupA] Show Title - 05 (1080p) [ABCD1234].mkv\n" +
            "👤 800 💾 1.4 GB ⚙️ IdxOne",
        "vostfr release with hyphenated group" to
            "[Grp-Raws] Show Title - 05 VOSTFR (1080p).mkv\n" +
            "👤 8 💾 400 MB ⚙️ IdxTwo",
        "subbed language line, english dub in title" to
            "IdxTwo - 720p\n" +
            "[GroupA] Show Title - 07 (720p) English Dub.mkv\n" +
            "👤 41 💾 350.2 MB ⚙️ IdxTwo\n" +
            "Subbed / 🇬🇧",
        "single quality before the group bracket" to
            "1080p [GroupA] Show Title - 05 [HEVC]\n" +
            "👤 5 💾 300 MB ⚙️ IdxOne",
        "plain stream label" to "1080p",
        "server label with a hyphen" to "Vidstream-Main",
    )

    private fun render(): String = buildString {
        for ((name, text) in rows) {
            append("### ").append(name).append('\n')
            append("release: ").append(SourceRowClassifier.parseRelease(text)).append('\n')
            append("name: ").append(SourceRowClassifier.extractReleaseName(text)).append('\n')
            append("file: ").append(SourceRowClassifier.fileNameLine(text)).append('\n')
            append("langLine: ").append(SourceRowClassifier.languageLineText(text)).append('\n')
            append("card: ").append(
                SourceRowSummarizer.build(text, text, VideoType.CONTAINER, "magnet:?xt=urn:btih:00")
            ).append('\n')
            append("stable: ").append(StableReleaseMetadataExtractor.extract(text)).append('\n')
        }
        append("### group keys\n")
        for (g in listOf("GroupA", "Grp-Raws", "IdxOne", "2448x", "1080p", "AMZN", "HEVC", "WEB-DL", "AAC2.0", " ")) {
            append(g).append(" -> ").append(StableGroupCanonicalizer.canonicalKey(g)).append('\n')
        }
        append("### repo badges\n")
        for (url in listOf(
            "https://raw.githubusercontent.com/someowner/extensions/repo/index.min.json",
            "https://raw.githubusercontent.com/someowner/anime-extensions/repo/index.min.json",
            "https://raw.githubusercontent.com/someowner/my-repo/main/index.min.json",
        )) {
            append(url).append(" -> ").append(ExtensionRepoMetaHelper.getRepoBadgeName(url)).append('\n')
        }
    }

    @Test
    fun `parsers return the pinned output for each row shape`() {
        val actual = render()
        val expected = javaClass.classLoader!!.getResource(EXPECTED)?.readText()
        if (expected == null || System.getenv("CHARACTERIZATION_WRITE") != null) {
            File("build/characterization-actual.txt").writeText(actual)
        }
        assertEquals(expected, actual)
    }

    companion object {
        const val EXPECTED = "picker/release_text_characterization.txt"
    }
}
