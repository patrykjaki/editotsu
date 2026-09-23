package ani.dantotsu.download.manga

import ani.dantotsu.download.DownloadedType
import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaDownloadReconciliationTest {

    private fun d(title: String, chapter: String, type: MediaType, pageCount: Int?): DownloadedType =
        DownloadedType(title, chapter, type, pageCount = pageCount)

    @Test
    fun selectIncomplete_choosesExactlyTheIncompleteKnownCountManga() {
        val validCp5 = d("Manga", "valid", MediaType.MANGA, 5)
        val missingCp5 = d("Manga", "missing", MediaType.MANGA, 5)
        val zeroCp5 = d("Manga", "zero", MediaType.MANGA, 5)
        val legacy = d("Manga", "legacy", MediaType.MANGA, null) // unknown count
        val anime = d("Anime", "a1", MediaType.ANIME, 3) // non-manga

        val all = listOf(validCp5, missingCp5, zeroCp5, legacy, anime)

        // Completeness reflects the actual persisted page set for each entry.
        val completeness = mapOf(
            validCp5.uniqueName to true,
            missingCp5.uniqueName to false,
            zeroCp5.uniqueName to false,
            legacy.uniqueName to false, // would fail if checked, but pageCount==null protects it
            anime.uniqueName to false,
        )

        val result = MangaDownloadReconciliation.selectIncomplete(all) {
            completeness[it.uniqueName] ?: false
        }

        // Keep: valid CP5 manga, legacy unknown-count manga, non-manga.
        // Remove: invalid CP5 manga (missing + zero-byte).
        assertEquals(listOf(missingCp5, zeroCp5), result)
    }

    @Test
    fun selectIncomplete_keepsEverythingWhenAllKnownMangaComplete() {
        val a = d("Manga", "a", MediaType.MANGA, 3)
        val b = d("Manga", "b", MediaType.MANGA, 7)
        val legacy = d("Manga", "legacy", MediaType.MANGA, null)
        val result = MangaDownloadReconciliation.selectIncomplete(listOf(a, b, legacy)) { true }
        assertEquals(emptyList<DownloadedType>(), result)
    }

    @Test
    fun selectIncomplete_prunesAllKnownIncompleteMangaButNeverLegacyOrNonManga() {
        val incomplete1 = d("Manga", "x", MediaType.MANGA, 2)
        val incomplete2 = d("Manga", "y", MediaType.MANGA, 2)
        val legacy = d("Manga", "legacy", MediaType.MANGA, null)
        val novel = d("Novel", "n", MediaType.NOVEL, 4)
        val all = listOf(incomplete1, incomplete2, legacy, novel)
        val result = MangaDownloadReconciliation.selectIncomplete(all) { false }
        assertEquals(listOf(incomplete1, incomplete2), result)
    }
}
