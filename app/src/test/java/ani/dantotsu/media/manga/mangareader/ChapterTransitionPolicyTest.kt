package ani.dantotsu.media.manga.mangareader

import ani.dantotsu.settings.CurrentReaderSettings.Directions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter list is in reading order. Every reading direction appends the
 * same next chapter and marks the same chapter read; only the on-screen side
 * (reversed layout) changes.
 */
class ChapterTransitionPolicyTest {

    // Chapters 1, 2, 3 in reading order; the reader is in chapter 2.
    private val chapterCount = 3
    private val current = 1

    /** What the continuous reader does when the user reads forward out of [current]. */
    private data class ForwardRead(val appended: Int?, val prepended: Int?, val markedRead: Int?)

    private fun readForward(from: Int): ForwardRead {
        val appended = nextChapterIndex(from, chapterCount)
        val prepended = previousChapterIndex(from, chapterCount)
        // Scrolling forward lands in the appended chapter.
        val markedRead = appended?.takeIf { isForwardChapterTransition(from, it) }?.let { from }
        return ForwardRead(appended, prepended, markedRead)
    }

    private fun assertForwardRead(direction: Directions) {
        val result = readForward(current)
        assertEquals("$direction appends the next chapter", 2, result.appended)
        assertEquals("$direction prepends the previous chapter", 0, result.prepended)
        assertEquals("$direction marks the outgoing chapter read", 1, result.markedRead)
        // Scrolling back into the prepended chapter marks nothing.
        assertFalse("$direction backward", isForwardChapterTransition(current, result.prepended!!))
    }

    @Test
    fun `left to right appends next chapter and marks the outgoing chapter read`() {
        assertFalse(isReversedReadingDirection(Directions.LEFT_TO_RIGHT))
        assertForwardRead(Directions.LEFT_TO_RIGHT)
    }

    @Test
    fun `right to left appends next chapter and marks the outgoing chapter read`() {
        assertTrue(isReversedReadingDirection(Directions.RIGHT_TO_LEFT))
        assertForwardRead(Directions.RIGHT_TO_LEFT)
    }

    @Test
    fun `top to bottom appends next chapter and marks the outgoing chapter read`() {
        assertFalse(isReversedReadingDirection(Directions.TOP_TO_BOTTOM))
        assertForwardRead(Directions.TOP_TO_BOTTOM)
    }

    @Test
    fun `bottom to top appends next chapter and marks the outgoing chapter read`() {
        assertTrue(isReversedReadingDirection(Directions.BOTTOM_TO_TOP))
        assertForwardRead(Directions.BOTTOM_TO_TOP)
    }

    @Test
    fun `ends of the list have no neighbour`() {
        assertNull(nextChapterIndex(2, chapterCount))
        assertNull(previousChapterIndex(0, chapterCount))
        assertNull(readForward(2).markedRead)
    }

    @Test
    fun `unknown index has no neighbours`() {
        assertNull(nextChapterIndex(-1, chapterCount))
        assertNull(previousChapterIndex(-1, chapterCount))
        assertNull(nextChapterIndex(3, chapterCount))
        assertNull(nextChapterIndex(0, 0))
    }

    @Test
    fun `return trip does not complete the middle chapter`() {
        // 1 -> 2 completes 1; 2 -> 1 completes nothing; 1 -> 2 completes 1 again.
        val events = listOf(0 to 1, 1 to 0, 0 to 1)
        assertEquals(2, events.count { (from, to) -> isForwardChapterTransition(from, to) })
    }

    @Test
    fun `unknown or identical indices are not forward`() {
        assertFalse(isForwardChapterTransition(-1, 1))
        assertFalse(isForwardChapterTransition(0, -1))
        assertFalse(isForwardChapterTransition(1, 1))
    }

    @Test
    fun `chapter picker jump to a later chapter is forward`() {
        assertTrue(isForwardChapterTransition(0, 2))
        assertFalse(isForwardChapterTransition(2, 0))
    }
}
