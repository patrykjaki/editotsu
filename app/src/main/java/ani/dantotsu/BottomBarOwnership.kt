package ani.dantotsu

/**
 * M1 HOLD closure: unit-testable predicate behind [clearBottomBar].
 *
 * Identity-scoped release contract (proved by BottomBarOwnershipTest):
 * - A owns, A clears  -> released (true)
 * - A owns, B replaces, stale A clears -> kept (false), B survives
 * - A owns, B replaces, B clears -> released (true)
 * - nothing attached (null current) -> never released (false)
 *
 * Framework-free on purpose: AnimatedBottomBar cannot be instantiated in
 * local JVM unit tests, so the decision lives here on [Any] and
 * [clearBottomBar] delegates to it unchanged.
 */
internal fun shouldReleaseBottomBarSlot(current: Any?, expected: Any?): Boolean =
    current != null && current === expected
