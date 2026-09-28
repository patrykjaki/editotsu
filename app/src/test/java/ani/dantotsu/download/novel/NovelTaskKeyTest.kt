package ani.dantotsu.download.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 HOLD (Blocker 3): chapter-only identity is ambiguous;
 * exact `(title, chapter)` keys are not.
 */
class NovelTaskKeyTest {

    private val a1 = NovelTaskId("Novel A", "1")
    private val b1 = NovelTaskId("Novel B", "1")

    @Test
    fun `same chapter different titles yield distinct keys`() {
        assertTrue(a1.key() != b1.key())
    }

    @Test
    fun `all-cancel covers both novels independently`() {
        assertEquals(
            listOf(a1.key(), b1.key()),
            novelAllCancelKeys(listOf(a1, b1, a1))
        )
    }

    @Test
    fun `single-item cancel never scopes a sibling title`() {
        assertEquals(listOf(a1.key()), novelSingleCancelKeys(listOf(a1, b1), "Novel A", "1"))
        assertEquals(listOf(b1.key()), novelSingleCancelKeys(listOf(a1, b1), "Novel B", "1"))
        assertTrue(novelSingleCancelKeys(listOf(a1, b1), "Novel C", "1").isEmpty())
    }

    @Test
    fun `queued plus active mixed set all covered`() {
        val queued = listOf(a1)
        val active = listOf(b1)
        assertEquals(
            setOf(a1.key(), b1.key()),
            novelAllCancelKeys(queued + active).toSet()
        )
    }
}
