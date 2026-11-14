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

    /** a link the player can open directly */
    fun usable(link: String?): Boolean =
        link != null && (link.startsWith("https://") || link.startsWith("http://"))
}
