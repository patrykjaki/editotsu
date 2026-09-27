package ani.dantotsu

/**
 * MAX MERGE M6 HOLD (Blocker 1): pure deep-link route decision behind
 * `MainActivity.handleIncomingExtras` / `onNewIntent`.
 *
 * A comment notification carries BOTH a fragment route
 * (`FRAGMENT_TO_LOAD=COMMENTS`, `mediaId`, `commentId`) AND a non-zero
 * `mediaId`. The fragment route consumes the intent: when it launches,
 * the generic media route must NOT also run, or two
 * `MediaDetailsActivity` instances start. Same for FEED/NOTIFICATIONS.
 *
 * Framework-free so the priority/consumption contract is unit-proved
 * (DeepLinkRoutingTest). Both cold-start and live-intent paths honor
 * [routeConsumesIntent].
 */
data class IncomingRoute(
    val fragmentToLoad: String? = null,
    val mediaId: Int = -1,
    val commentId: Int = -1,
    val activityId: Int = -1,
    val hasMediaId: Boolean = false,
    val username: String? = null,
)

sealed interface RouteDecision {
    /** Dedicated comment/details destination. Consumes the intent. */
    data object CommentThread : RouteDecision

    /** Dedicated feed destination. Consumes the intent. */
    data object Feed : RouteDecision

    /** Dedicated notifications destination. Consumes the intent. */
    data object Notifications : RouteDecision

    /** Generic media fetch → details. */
    data object GenericMedia : RouteDecision

    /** Profile destination. */
    data object Profile : RouteDecision

    /** Nothing to route. */
    data object None : RouteDecision
}

internal fun decideRoute(route: IncomingRoute): RouteDecision {
    if (route.fragmentToLoad != null && route.mediaId != -1 && route.commentId != -1) {
        return RouteDecision.CommentThread
    }
    if (route.fragmentToLoad == "FEED" && route.activityId != -1) {
        return RouteDecision.Feed
    }
    if (route.fragmentToLoad == "NOTIFICATIONS" && route.activityId != -1) {
        return RouteDecision.Notifications
    }
    if (route.hasMediaId) {
        return RouteDecision.GenericMedia
    }
    if (route.username != null) {
        return RouteDecision.Profile
    }
    return RouteDecision.None
}

/** Dedicated destinations consume the intent; generic fallbacks must not run. */
internal fun routeConsumesIntent(decision: RouteDecision): Boolean =
    decision == RouteDecision.CommentThread ||
        decision == RouteDecision.Feed ||
        decision == RouteDecision.Notifications
