package ani.dantotsu.download.novel

import ani.dantotsu.download.WifiGateAction
import ani.dantotsu.download.nextWifiGateAction
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

    @Test
    fun `wifi BLOCK_NOW drives the exact all-cancel scope exactly once`() {
        // Blocker 3: the Wi-Fi transition may fire edge-triggered, but when
        // it fires every active novel transfer must receive cancellation
        // through its EXACT key — never a chapter-only broadcast.
        val queued = listOf(a1)
        val active = listOf(a1, b1)
        val first = nextWifiGateAction(prefOn = true, satisfied = false, wasBlocked = false)
        assertEquals(WifiGateAction.BLOCK_NOW, first)
        val scope = novelAllCancelKeys(queued + active)
        assertEquals(setOf(a1.key(), b1.key()), scope.toSet())
        // Edge-triggered: the repeat transition must not re-fire.
        assertEquals(
            WifiGateAction.ALREADY_BLOCKED,
            nextWifiGateAction(prefOn = true, satisfied = false, wasBlocked = true)
        )
        // Restore to allowed leaves no stale block behind.
        assertEquals(
            WifiGateAction.ALLOWED,
            nextWifiGateAction(prefOn = true, satisfied = true, wasBlocked = true)
        )
    }
}
