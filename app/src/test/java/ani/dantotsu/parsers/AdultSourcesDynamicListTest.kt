package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import org.junit.Assert.assertEquals
import org.junit.Test

class AdultSourcesDynamicListTest {

    @Test
    fun hAnimeSources_reflectsUpdatesToAnimeSourcesList() {
        val previous = AnimeSources.list
        try {
            AnimeSources.list = emptyList()
            assertEquals(emptyList<String>(), HAnimeSources.names)

            AnimeSources.list = listOf(
                Lazier({ EmptyAnimeParser() }, "Source A"),
                Lazier({ EmptyAnimeParser() }, "Local"),
            )
            assertEquals(listOf("Source A", "Local"), HAnimeSources.names)

            AnimeSources.list = listOf(
                Lazier({ EmptyAnimeParser() }, "Source B"),
                Lazier({ EmptyAnimeParser() }, "Source A"),
                Lazier({ EmptyAnimeParser() }, "Local"),
            )
            assertEquals(listOf("Source B", "Source A", "Local"), HAnimeSources.names)
        } finally {
            AnimeSources.list = previous
        }
    }

    @Test
    fun hMangaSources_reflectsUpdatesToMangaSourcesList() {
        val previous = MangaSources.list
        try {
            MangaSources.list = emptyList()
            assertEquals(emptyList<String>(), HMangaSources.names)

            MangaSources.list = listOf(
                Lazier({ EmptyMangaParser() }, "Manga A"),
                Lazier({ EmptyMangaParser() }, "Downloaded"),
            )
            assertEquals(listOf("Manga A", "Downloaded"), HMangaSources.names)

            MangaSources.list = listOf(
                Lazier({ EmptyMangaParser() }, "Manga B"),
                Lazier({ EmptyMangaParser() }, "Manga A"),
                Lazier({ EmptyMangaParser() }, "Downloaded"),
            )
            assertEquals(listOf("Manga B", "Manga A", "Downloaded"), HMangaSources.names)
        } finally {
            MangaSources.list = previous
        }
    }
}
