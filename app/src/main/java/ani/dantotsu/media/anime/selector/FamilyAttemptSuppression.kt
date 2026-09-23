package ani.dantotsu.media.anime.selector

/**
 * Attempt-local family auto-resolution suppression. The set holds
 * episode keys for which the family auto-resolve has already been
 * tried in the current selector dialog attempt. After the family
 * resolution fires once for an episode and falls through to the
 * manual picker, subsequent `failToList` calls in the same attempt
 * will NOT re-attempt the family resolution. The persisted family
 * preference itself is left intact.
 *
 * This is a small, testable helper. The fragment holds one of
 * these per dialog instance and resets it when the fragment is
 * recreated.
 */
class FamilyAttemptSuppression {
    private val attempted = HashSet<String>()

    /**
     * Returns true if a family auto-resolve should be attempted
     * for the given episode key. Marks the episode as attempted
     * regardless of whether the resolve itself succeeds.
     */
    fun shouldAttempt(episodeKey: String?): Boolean {
        val key = episodeKey ?: ""
        return attempted.add(key)
    }

    fun reset() {
        attempted.clear()
    }

    fun size(): Int = attempted.size

    fun contains(episodeKey: String?): Boolean =
        attempted.contains(episodeKey ?: "")
}
