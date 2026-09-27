package ani.dantotsu.home.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ani.dantotsu.profile.User

/**
 * MAX MERGE M6 HOLD (Blocker 3): resolved-empty must hide the rail —
 * never an empty visible section.
 */
class StoriesVisibilityTest {

    private fun user(id: Int) = User(
        id = id,
        name = "u$id",
        pfp = null,
        banner = null,
        activity = emptyList()
    )

    @Test
    fun `loading plus enabled shows container and progress`() {
        val vis = storiesVisibility(null, true)
        assertTrue(vis.containerVisible)
        assertTrue(vis.progressVisible)
        assertFalse(vis.recyclerVisible)
    }

    @Test
    fun `empty plus enabled hides everything`() {
        val vis = storiesVisibility(emptyList<User>(), true)
        assertFalse(vis.containerVisible)
        assertFalse(vis.progressVisible)
        assertFalse(vis.recyclerVisible)
    }

    @Test
    fun `non-empty shows container and recycler`() {
        val vis = storiesVisibility(listOf(user(1), user(2)), true)
        assertTrue(vis.containerVisible)
        assertFalse(vis.progressVisible)
        assertTrue(vis.recyclerVisible)
    }

    @Test
    fun `disabled hides everything in every state`() {
        assertEquals(
            StoriesVisibility(false, false, false),
            storiesVisibility(null, false)
        )
        assertEquals(
            StoriesVisibility(false, false, false),
            storiesVisibility(emptyList<User>(), false)
        )
        assertEquals(
            StoriesVisibility(false, false, false),
            storiesVisibility(listOf(user(1)), false)
        )
    }
}
