package ani.dantotsu.parsers

import ani.dantotsu.media.anime.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = ListCache<Episode> { tmp.root }

    @Test
    fun savedListComesBackInOrderPerSourceAndTitle() {
        val c = cache()
        val list = linkedMapOf("1" to Episode("1", "a"), "2" to Episode("2", "b"))
        c.save("SourceA", 42, list)
        assertEquals(listOf("1", "2"), c.load("SourceA", 42)?.keys?.toList())
        assertEquals("b", c.load("SourceA", 42)?.get("2")?.link)
        assertNull(c.load("SourceB", 42))
        assertNull(c.load("SourceA", 43))
    }

    @Test
    fun damagedFileReadsAsNothingCached() {
        val c = cache()
        c.save("S", 1, mapOf("1" to Episode("1")))
        tmp.root.listFiles()!!.single().writeText("not a list")
        assertNull(c.load("S", 1))
    }

    @Test
    fun newKeysOnlyAfterACachedList() {
        val fresh = mapOf("1" to 1, "2" to 2, "3" to 3)
        assertEquals(listOf("3"), ListCache.newKeys(mapOf("1" to 1, "2" to 2), fresh))
        assertEquals(emptyList<String>(), ListCache.newKeys(null, fresh))
    }
}
