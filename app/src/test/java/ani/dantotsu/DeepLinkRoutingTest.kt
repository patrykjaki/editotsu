package ani.dantotsu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M6 HOLD (Blocker 1): live-intent routing must honor the same
 * consumption contract as cold start — a dedicated destination suppresses
 * the generic media/profile fallback, or two destinations launch.
 */
class DeepLinkRoutingTest {

    @Test
    fun `comment notification routes dedicated exactly once, generic suppressed`() {
        val route = IncomingRoute(
            fragmentToLoad = "COMMENTS",
            mediaId = 21,
            commentId = 123,
            hasMediaId = true
        )
        assertEquals(RouteDecision.CommentThread, decideRoute(route))
        assertTrue(routeConsumesIntent(decideRoute(route)))
    }

    @Test
    fun `generic media notification routes generic exactly once`() {
        val route = IncomingRoute(
            mediaId = 21,
            hasMediaId = true
        )
        assertEquals(RouteDecision.GenericMedia, decideRoute(route))
        assertFalse(routeConsumesIntent(decideRoute(route)))
    }

    @Test
    fun `feed route consumes, generic suppressed`() {
        val route = IncomingRoute(fragmentToLoad = "FEED", activityId = 7)
        assertEquals(RouteDecision.Feed, decideRoute(route))
        assertTrue(routeConsumesIntent(decideRoute(route)))
    }

    @Test
    fun `notifications route consumes, generic suppressed`() {
        val route = IncomingRoute(fragmentToLoad = "NOTIFICATIONS", activityId = 9)
        assertEquals(RouteDecision.Notifications, decideRoute(route))
        assertTrue(routeConsumesIntent(decideRoute(route)))
    }

    @Test
    fun `username routes profile exactly once when nothing consumed the intent`() {
        val route = IncomingRoute(username = "someuser")
        assertEquals(RouteDecision.Profile, decideRoute(route))
        assertFalse(routeConsumesIntent(decideRoute(route)))
    }

    @Test
    fun `empty intent routes nowhere`() {
        assertEquals(RouteDecision.None, decideRoute(IncomingRoute()))
        assertFalse(routeConsumesIntent(decideRoute(IncomingRoute())))
    }

    @Test
    fun `fragment without ids does not consume`() {
        // Defensive: a bare FRAGMENT_TO_LOAD with no ids must not claim
        // the intent and suppress a valid generic/username route.
        val route = IncomingRoute(fragmentToLoad = "COMMENTS", username = "someuser")
        assertEquals(RouteDecision.Profile, decideRoute(route))
        assertFalse(routeConsumesIntent(decideRoute(route)))
    }
}
