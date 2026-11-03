package ani.dantotsu.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    @Test
    fun `exact title ignoring case scores 100`() {
        assertEquals(100, titleMatchScore("Attack on Titan", "attack on titan "))
    }

    @Test
    fun `word order does not matter`() {
        assertEquals(100, titleMatchScore("Kimetsu no Yaiba Demon Slayer", "Demon Slayer Kimetsu no Yaiba"))
    }

    @Test
    fun `a later season does not score as the exact title`() {
        val exact = titleMatchScore("Attack on Titan", "Attack on Titan")
        val season2 = titleMatchScore("Attack on Titan Season 2", "Attack on Titan")
        assertTrue("season 2 scored $season2", season2 < 85)
        assertTrue(exact > season2)
    }

    @Test
    fun `exact title sorts ahead of its sequel`() {
        val results = listOf("Attack on Titan Season 2", "Attack on Titan", "Attack on Titan: Junior High")
        assertEquals("Attack on Titan", results.maxBy { titleMatchScore(it, "Attack on Titan") })
    }
}
