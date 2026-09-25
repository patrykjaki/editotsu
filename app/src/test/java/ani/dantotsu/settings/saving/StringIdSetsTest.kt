package ani.dantotsu.settings.saving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 2 correction (review HOLD, Blocker 1): the removeList/activities
 * contract after the Set<String> migration (upstream 5ffa682b).
 *
 * Proves for a legacy persisted removeList (and the empty/strings cases):
 * same media stays hidden, IDs can be added/removed, and the set survives
 * a write→read round-trip. Exercises the real StringIdSets helpers used by
 * PrefManager's sanitize branches and the migrated call sites.
 */
class StringIdSetsTest {

    @Test
    fun `legacy Int set hides the same media`() {
        val legacy: Set<Any?> = setOf(101, 202, 303)
        val strings = StringIdSets.sanitizeToStrings(legacy)
        assertEquals(setOf("101", "202", "303"), strings)
        assertTrue(StringIdSets.containsId(strings, 202))
        assertFalse(StringIdSets.containsId(strings, 999))
    }

    @Test
    fun `legacy Long set hides the same media`() {
        val legacy: Set<Any?> = setOf(101L, 202L)
        val strings = StringIdSets.sanitizeToStrings(legacy)
        assertTrue(StringIdSets.containsId(strings, 101))
        assertTrue(StringIdSets.containsId(strings, 202))
    }

    @Test
    fun `add and remove by Int id`() {
        var strings = StringIdSets.sanitizeToStrings(setOf<Any?>(101))
        strings = StringIdSets.plusId(strings, 202)
        assertTrue(StringIdSets.containsId(strings, 202))
        strings = StringIdSets.minusId(strings, 101)
        assertFalse(StringIdSets.containsId(strings, 101))
        assertTrue(StringIdSets.containsId(strings, 202))
    }

    @Test
    fun `round-trip through write normalization is stable`() {
        val afterOps = StringIdSets.minusId(
            StringIdSets.plusId(StringIdSets.sanitizeToStrings(setOf<Any?>(101, 202)), 303),
            101
        )
        val persisted = StringIdSets.normalizeForWrite(afterOps)
        val reread = StringIdSets.sanitizeToStrings(persisted)
        assertEquals(setOf("202", "303"), reread)
        assertTrue(StringIdSets.containsId(reread, 202))
        assertFalse(StringIdSets.containsId(reread, 101))
    }

    @Test
    fun `initially empty works`() {
        val strings = StringIdSets.sanitizeToStrings(null)
        assertTrue(strings.isEmpty())
        val added = StringIdSets.plusId(strings, 42)
        assertTrue(StringIdSets.containsId(added, 42))
    }

    @Test
    fun `legacy string storage is unchanged`() {
        val strings = StringIdSets.sanitizeToStrings(setOf<Any?>("101", "202"))
        assertEquals(setOf("101", "202"), strings)
        assertTrue(StringIdSets.containsId(strings, 101))
    }

    @Test
    fun `unparseable entries are dropped at the Int boundary`() {
        val strings = StringIdSets.sanitizeToStrings(setOf<Any?>("101", "junk", null))
        assertEquals(setOf(101), StringIdSets.toIntIds(strings))
    }
}
