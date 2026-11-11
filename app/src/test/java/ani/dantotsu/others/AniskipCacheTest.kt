package ani.dantotsu.others

import org.junit.Assert.assertEquals
import org.junit.Test

class AniskipCacheTest {
    @Test
    fun `no skip times with a known duration is a cached hit`() {
        val c = AniskipCache<String>()
        c.store(5, null, durationKnown = true)
        assertEquals(AniskipCache.Lookup.Hit<String>(null), c.lookup(5))
        c.store(6, emptyList(), durationKnown = true)
        assertEquals(AniskipCache.Lookup.Hit<String>(emptyList()), c.lookup(6))
    }

    @Test
    fun `nothing found without a duration stays a miss`() {
        val c = AniskipCache<String>()
        c.store(5, null, durationKnown = false)
        assertEquals(AniskipCache.Lookup.Miss, c.lookup(5))
    }

    @Test
    fun `stamps are cached with or without duration`() {
        val c = AniskipCache<String>()
        c.store(1, listOf("op"), durationKnown = false)
        assertEquals(AniskipCache.Lookup.Hit(listOf("op")), c.lookup(1))
        assertEquals(AniskipCache.Lookup.Miss, c.lookup(2))
    }
}
