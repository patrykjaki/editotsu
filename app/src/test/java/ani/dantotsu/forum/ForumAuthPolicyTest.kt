package ani.dantotsu.forum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M2 correction (Blocker 3): logged-out Forum UI must not
 * advertise authenticated actions; delete stays owner-specific.
 */
class ForumAuthPolicyTest {

    @Test
    fun `logged out hides mutation-only controls`() {
        assertFalse(ForumAuthPolicy.canMutate(null))
        assertFalse(ForumAuthPolicy.canMutate(""))
        assertFalse(ForumAuthPolicy.canMutate("   "))
    }

    @Test
    fun `logged in shows mutation controls`() {
        assertTrue(ForumAuthPolicy.canMutate("token123"))
    }

    @Test
    fun `owner delete requires session and match`() {
        assertTrue(ForumAuthPolicy.deleteVisibleFor(7, 7, "token"))
        assertFalse(ForumAuthPolicy.deleteVisibleFor(7, 8, "token"))
        assertFalse(ForumAuthPolicy.deleteVisibleFor(7, 7, null))
        assertFalse(ForumAuthPolicy.deleteVisibleFor(7, null, null))
        // Null owner (deleted user) never matches, even logged in.
        assertFalse(ForumAuthPolicy.deleteVisibleFor(null, 7, "token"))
        assertFalse(ForumAuthPolicy.deleteVisibleFor(null, null, "token"))
    }

    @Test
    fun `logged-out viewer never equals any owner`() {
        assertFalse(ForumAuthPolicy.deleteVisibleFor(7, null, ""))
        assertFalse(ForumAuthPolicy.deleteVisibleFor(null, null, null))
    }
}
