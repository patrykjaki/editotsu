package ani.dantotsu.media.anime

/**
 * Player sleep timer (pure, JVM-tested). Either pauses after a duration or
 * stops at the end of the current episode (autoplay doesn't advance).
 * Times are passed in so tests don't need a clock.
 */
class SleepTimer {
    sealed interface Mode {
        data object Off : Mode
        /** [minutes] is the choice that started it, for the dialog. */
        data class At(val deadlineMs: Long, val minutes: Int = 0) : Mode
        data object EndOfEpisode : Mode
    }

    var mode: Mode = Mode.Off
        private set

    fun setMinutes(minutes: Int, nowMs: Long) {
        mode = if (minutes <= 0) Mode.Off else Mode.At(nowMs + minutes * 60_000L, minutes)
    }

    fun setEndOfEpisode() {
        mode = Mode.EndOfEpisode
    }

    fun cancel() {
        mode = Mode.Off
    }

    /** Milliseconds until a timed pause, or null when not timed. */
    fun remainingMs(nowMs: Long): Long? =
        (mode as? Mode.At)?.let { (it.deadlineMs - nowMs).coerceAtLeast(0L) }

    /** Whole minutes left of a timed pause, rounded up (59 s left shows 1), or null. */
    fun remainingMinutes(nowMs: Long): Int? =
        remainingMs(nowMs)?.let { ((it + 59_999L) / 60_000L).toInt() }

    /** Index in [CHOICES_MINUTES] of the running mode, so the dialog checks it. */
    fun checkedChoice(): Int = when (val m = mode) {
        Mode.Off -> CHOICES_MINUTES.indexOf(0)
        Mode.EndOfEpisode -> CHOICES_MINUTES.indexOf(-1)
        is Mode.At -> CHOICES_MINUTES.indexOf(m.minutes)
    }

    /** Called when the timed deadline fires: true means pause now (and the timer ends). */
    fun onDeadline(nowMs: Long): Boolean {
        val at = mode as? Mode.At ?: return false
        if (nowMs < at.deadlineMs) return false
        mode = Mode.Off
        return true
    }

    /**
     * Called when an episode ends. True means stop here: autoplay must not
     * advance. A one-shot: the timer is off afterwards.
     */
    fun onEpisodeEnded(): Boolean {
        if (mode != Mode.EndOfEpisode) return false
        mode = Mode.Off
        return true
    }

    companion object {
        /** Dialog choices; 0 = off, -1 = end of episode. */
        val CHOICES_MINUTES = listOf(0, 15, 30, 45, 60, 90, -1)
    }
}
