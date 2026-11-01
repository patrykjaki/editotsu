package ani.dantotsu.media.anime

/**
 * End-of-episode autoplay decision. Matches the "Autoplay Next Episode" setting:
 * advance only if the viewer interacted with the player within the last hour,
 * otherwise cancel with a toast.
 */
internal object AutoplayPolicy {

    const val IDLE_TIMEOUT_MS: Long = 60L * 60L * 1000L

    enum class Decision { ADVANCE, CANCELLED, NONE }

    fun decide(
        autoPlayOn: Boolean,
        nearPhysicalEnd: Boolean,
        interactedRecently: Boolean
    ): Decision = when {
        !autoPlayOn || !nearPhysicalEnd -> Decision.NONE
        interactedRecently -> Decision.ADVANCE
        else -> Decision.CANCELLED
    }

    fun shouldAdvance(autoPlayOn: Boolean, nearPhysicalEnd: Boolean, interactedRecently: Boolean): Boolean =
        decide(autoPlayOn, nearPhysicalEnd, interactedRecently) == Decision.ADVANCE
}

/**
 * Lets one end of playback act on autoplay at most once. A repeated Ended
 * before the next load would otherwise skip two episodes, since
 * changeEpisode is asynchronous. Re-armed when playback loads or resumes.
 */
internal class AutoplayEndGate {
    private var handled = false

    fun tryConsume(): Boolean {
        if (handled) return false
        handled = true
        return true
    }

    fun rearm() {
        handled = false
    }
}
