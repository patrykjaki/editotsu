package ani.dantotsu.connections.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentsOptInTest {

    @Test
    fun resetTurnsTheOldDefaultOff() {
        // 1 was written by the old default on first start; it cannot be told apart from a user choice.
        assertEquals(CommentsOptIn.OFF, CommentsOptIn.resetValue(CommentsOptIn.ON))
    }

    @Test
    fun resetKeepsOffAndUndecided() {
        assertEquals(CommentsOptIn.OFF, CommentsOptIn.resetValue(CommentsOptIn.OFF))
        assertEquals(CommentsOptIn.UNSET, CommentsOptIn.resetValue(CommentsOptIn.UNSET))
    }

    @Test
    fun onlyAnOnWrittenAfterTheResetCounts() {
        assertTrue(CommentsOptIn.isEnabled(CommentsOptIn.ON, resetDone = true))
        // e.g. a backup from before the reset restored over the General prefs
        assertFalse(CommentsOptIn.isEnabled(CommentsOptIn.ON, resetDone = false))
        assertFalse(CommentsOptIn.isEnabled(CommentsOptIn.UNSET, resetDone = true))
        assertFalse(CommentsOptIn.isEnabled(CommentsOptIn.OFF, resetDone = true))
    }
}
