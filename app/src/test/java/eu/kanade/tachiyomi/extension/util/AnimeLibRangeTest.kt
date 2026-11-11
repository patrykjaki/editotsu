package eu.kanade.tachiyomi.extension.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeLibRangeTest {
    @Test
    fun `anime extensions above lib 16 are not offered or loaded`() {
        // AniyomiAdapter bridges extensions-lib up to 16; raise this together with the adapter
        assertEquals(12, ExtensionLoader.ANIME_LIB_VERSION_MIN)
        assertEquals(16, ExtensionLoader.ANIME_LIB_VERSION_MAX)
    }
}
