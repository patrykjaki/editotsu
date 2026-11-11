package ani.dantotsu.others

/**
 * Skip times per episode number, including "this episode has none".
 *
 * A result is stored when it has stamps, or when the lookup ran with a known duration (every
 * provider had its chance, so an empty answer is final for this player). A lookup without a
 * duration that found nothing is not stored: live AniSkip needs the duration and runs again
 * once it is known.
 */
class AniskipCache<T> {
    private val entries = HashMap<Int, List<T>?>()

    sealed interface Lookup<out T> {
        data object Miss : Lookup<Nothing>
        /** [stamps] is null or empty when the episode has no skip times. */
        data class Hit<T>(val stamps: List<T>?) : Lookup<T>
    }

    @Synchronized
    fun lookup(episode: Int): Lookup<T> =
        if (entries.containsKey(episode)) Lookup.Hit(entries[episode]) else Lookup.Miss

    @Synchronized
    fun store(episode: Int, stamps: List<T>?, durationKnown: Boolean) {
        if (!stamps.isNullOrEmpty() || durationKnown) entries[episode] = stamps
    }
}
