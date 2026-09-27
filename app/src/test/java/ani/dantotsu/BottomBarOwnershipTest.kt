package ani.dantotsu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 HOLD closure: bottom-bar identity-scoped release contract.
 *
 * [shouldReleaseBottomBarSlot] is the exact predicate [clearBottomBar]
 * delegates to. Plain [Any] owners stand in for AnimatedBottomBar, which
 * cannot be instantiated in local JVM unit tests; identity (`===`)
 * semantics are identical. Both owners (MainActivity teardown,
 * NoInternet teardown) pass their own navbar instance, so the contract
 * below is what protects the live owner from a stale destroy.
 */
class BottomBarOwnershipTest {

    @Test
    fun `owner clearing its own bar releases the slot`() {
        val ownerA = Any()
        assertTrue(shouldReleaseBottomBarSlot(ownerA, ownerA))
    }

    @Test
    fun `stale owner must not erase the replacing owner`() {
        val ownerA = Any()
        val ownerB = Any()
        // B replaced A as current; stale A destroy arrives.
        assertFalse(shouldReleaseBottomBarSlot(ownerB, ownerA))
    }

    @Test
    fun `replacing owner clearing its own bar releases the slot`() {
        val ownerB = Any()
        assertFalse(shouldReleaseBottomBarSlot(ownerB, Any()))
        assertTrue(shouldReleaseBottomBarSlot(ownerB, ownerB))
    }

    @Test
    fun `empty slot never releases`() {
        assertFalse(shouldReleaseBottomBarSlot(null, Any()))
    }
}
