package ani.dantotsu.media.anime.player

/**
 * Where an episode starts, from its saved position and the duration seen last time
 * (Dantotsu's rule): a finished episode, 92% or more watched, starts again at 0:00;
 * otherwise it continues from the saved position.
 */
object ResumeStart {
    const val FINISHED_FRACTION = 0.92

    fun startPosition(savedMs: Long, durationMs: Long?): Long {
        if (savedMs <= 0L) return 0L
        if (durationMs == null || durationMs <= 0L) return savedMs
        return if (savedMs >= durationMs || savedMs > durationMs * FINISHED_FRACTION) 0L else savedMs
    }
}
