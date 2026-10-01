package ani.dantotsu.media.manga.mangareader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M4 correction (HOLD2 C1): backward scrolls must switch identity
 * without completing/publishing the outgoing chapter.
 */
class ChapterTransitionPolicyTest {

    @Test
    fun `normal order forward allows completion`() {
        assertTrue(isForwardChapterTransition(0, 1, false))
        assertTrue(shouldCompleteOutgoingChapter(0, 1, false, progressAllowed = true))
    }

    @Test
    fun `normal order backward forbids completion`() {
        assertFalse(isForwardChapterTransition(1, 0, false))
        assertFalse(shouldCompleteOutgoingChapter(1, 0, false, progressAllowed = true))
    }

    @Test
    fun `reversed order forward allows completion`() {
        assertTrue(isForwardChapterTransition(1, 0, true))
        assertTrue(shouldCompleteOutgoingChapter(1, 0, true, progressAllowed = true))
    }

    @Test
    fun `reversed order backward forbids completion`() {
        assertFalse(isForwardChapterTransition(0, 1, true))
        assertFalse(shouldCompleteOutgoingChapter(0, 1, true, progressAllowed = true))
    }

    @Test
    fun `return trip does not complete the middle chapter`() {
        // A -> B -> A: B is entered forward (completes A), then left backward
        // (B itself must NOT be completed on return).
        assertTrue(shouldCompleteOutgoingChapter(0, 1, false, progressAllowed = true))
        assertFalse(shouldCompleteOutgoingChapter(1, 0, false, progressAllowed = true))
    }

    @Test
    fun `gates remain respected on forward transitions`() {
        assertTrue(shouldCompleteOutgoingChapter(0, 1, false, progressAllowed = true))
        assertFalse(shouldCompleteOutgoingChapter(0, 1, false, progressAllowed = false))
        assertFalse(shouldCompleteOutgoingChapter(1, 0, true, progressAllowed = false))
    }

    @Test
    fun `forward transition completes exactly once per crossing`() {
        // A -> B -> A -> B: exactly the two forward crossings complete.
        val events = listOf(0 to 1, 1 to 0, 0 to 1)
        val completions = events.count { (from, to) ->
            shouldCompleteOutgoingChapter(from, to, false, progressAllowed = true)
        }
        assertTrue(completions == 2)
    }

    @Test
    fun `unknown or identical indices never complete`() {
        assertFalse(shouldCompleteOutgoingChapter(-1, 1, false, progressAllowed = true))
        assertFalse(shouldCompleteOutgoingChapter(0, -1, false, progressAllowed = true))
        assertFalse(shouldCompleteOutgoingChapter(1, 1, false, progressAllowed = true))
        assertFalse(shouldCompleteOutgoingChapter(1, 1, true, progressAllowed = true))
    }
}
