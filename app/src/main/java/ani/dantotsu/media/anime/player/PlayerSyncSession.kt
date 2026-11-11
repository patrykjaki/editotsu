package ani.dantotsu.media.anime.player

/**
 * One in-player Sync value (subtitle or audio delay) on top of the global preference.
 *
 * The Sync buttons start from the preference value and set an absolute delay. The value
 * belongs to the player session: every pipeline application (engine rebuild for the next
 * episode or another server, live settings refresh) uses it instead of the preference.
 * When the preference itself changes, the session value is dropped and the new preference
 * applies.
 */
class SyncDelayOverride {
    private var valueMs: Long? = null
    private var basePrefMs: Long = 0L

    /** The delay to apply, given the current preference value. */
    fun effective(prefMs: Long): Long {
        val v = valueMs ?: return prefMs
        return if (basePrefMs == prefMs) v else prefMs
    }

    fun set(valueMs: Long, prefMs: Long) {
        this.valueMs = valueMs
        this.basePrefMs = prefMs
    }

    fun clear() {
        valueMs = null
    }
}

/** Sync values of the open player; reset when a player starts. */
object PlayerSyncSession {
    val subtitle = SyncDelayOverride()
    val audio = SyncDelayOverride()

    fun reset() {
        subtitle.clear()
        audio.clear()
    }
}
