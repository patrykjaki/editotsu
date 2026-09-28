package ani.dantotsu.forum

/**
 * MAX MERGE M2 correction (Blocker 3): one consistent logged-out contract
 * for every Forum mutation surface.
 *
 * No token → mutation-only controls are hidden/disabled; the mutation can
 * never be dispatched from the click path. Delete/edit stay
 * identity-based: visible only with a session AND owner match (a null
 * viewer never equals an owner, so logged-out delete is impossible).
 *
 * Unit-proved (ForumAuthPolicyTest).
 */
object ForumAuthPolicy {
    fun canMutate(token: String?): Boolean = !token.isNullOrBlank()

    fun deleteVisibleFor(ownerId: Int?, viewerId: Int?, token: String?): Boolean =
        canMutate(token) && ownerId != null && ownerId == viewerId
}
