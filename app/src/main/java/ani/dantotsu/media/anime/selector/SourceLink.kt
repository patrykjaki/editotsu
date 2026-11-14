package ani.dantotsu.media.anime.selector

/**
 * A source can ask to be consulted when one of its torrent releases is picked: it may have a
 * direct link for it (an account of the user's that already holds the file). The app asks, plays
 * the link if one comes back, and plays the torrent otherwise.
 */
object SourceLink {
    /** how long the source gets to answer before the torrent plays */
    const val TIMEOUT_MS = 12_000L

    /** true when the release at [url] is a torrent whose source asked to be consulted */
    fun wanted(internalData: String?, url: String?): Boolean {
        if (url == null || !url.startsWith("magnet:", ignoreCase = true)) return false
        return EditotsuInternalData.parse(internalData)?.resolve == true
    }

    /**
     * What to tell the user when the source answered with an error that carries a message:
     * the source decided this release should not play right now (for example it is still being
     * fetched to the user's account, on a network where the torrent can't play). An error
     * without a message is a plain failure: null, and the torrent plays.
     */
    fun refusal(error: Throwable?): String? {
        if (error == null || error is kotlinx.coroutines.CancellationException) return null
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** a link the player can open directly */
    fun usable(link: String?): Boolean =
        link != null && (link.startsWith("https://") || link.startsWith("http://"))
}
