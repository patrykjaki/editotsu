package ani.dantotsu.media.anime

/**
 * Spoiler shield for episode lists (pure, JVM-tested). When on, episodes
 * past the user's AniList progress show "Episode N" and the show's cover
 * instead of their own title, thumbnail and synopsis.
 */
object EpisodeSpoilerShield {
    /**
     * @param episodeNumber parsed episode number (9999 when unknown).
     * @param userProgress AniList progress, or null when the show isn't on the list.
     */
    fun shields(enabled: Boolean, episodeNumber: Float, userProgress: Int?): Boolean {
        if (!enabled) return false
        return episodeNumber > (userProgress ?: 0).toFloat()
    }
}
