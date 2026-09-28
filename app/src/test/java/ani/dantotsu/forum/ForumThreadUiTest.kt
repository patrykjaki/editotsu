package ani.dantotsu.forum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M2 correction (HOLD2 adjacent integrity): unconditional
 * indentation and exact subtree ranges for the flattened nested list.
 */
class ForumThreadUiTest {

    @Test
    fun `depth zero always yields zero indent`() {
        assertEquals(0, indentForDepth(0, 2.0f))
        assertEquals(0, indentForDepth(0, 3.5f))
    }

    @Test
    fun `rebind from depth two to depth zero clears indentation`() {
        val before = indentForDepth(2, 2.0f)
        assertTrue(before > 0)
        assertEquals(0, indentForDepth(0, 2.0f))
    }

    @Test
    fun `indent scales linearly with depth`() {
        assertEquals(indentForDepth(1, 2.0f) * 2, indentForDepth(2, 2.0f))
    }

    @Test
    fun `subtree range covers only deeper rows`() {
        // P C1 G1 C2 P2 -> depths [0,1,2,1,0]
        val depths = listOf(0, 1, 2, 1, 0)
        assertEquals(4, threadSubtreeEnd(depths, 0))
        assertEquals(3, threadSubtreeEnd(depths, 1))
        assertEquals(3, threadSubtreeEnd(depths, 2))
        assertEquals(4, threadSubtreeEnd(depths, 3))
        assertEquals(5, threadSubtreeEnd(depths, 4))
    }

    @Test
    fun `subtree range stops at unknown rows`() {
        val depths = listOf(0, 1, -1, 1)
        assertEquals(2, threadSubtreeEnd(depths, 0))
    }

    @Test
    fun `subtree range is safe out of bounds`() {
        assertEquals(9, threadSubtreeEnd(listOf(0, 1), 9))
        assertEquals(0, threadSubtreeEnd(emptyList(), 0))
    }
}
