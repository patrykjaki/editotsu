package ani.dantotsu.torrent

/**
 * Seek handoff from the player to the torrent relay.
 *
 * mpv's demuxer reads ahead on one relay request; at the edge of the downloaded
 * data that read waits for the next piece, and mpv cannot run a seek until the
 * read returns. For a seek outside mpv's cache (mpv opens a new request anyway)
 * the player calls [uncachedSeek] and the relay ends the waiting request, so the
 * seek runs at once instead of after the next piece lands.
 */
object TorrentSeekSignal {
    @Volatile
    var listener: ((hash: String, fileIndex: Int) -> Unit)? = null

    fun uncachedSeek(uri: String) {
        val link = TorrentRelayLink.parse(uri) ?: return
        listener?.invoke(link.hash, link.index)
    }

    /**
     * True when [targetSec] is outside every cached range by more than [marginSec]:
     * mpv then reads from a new position. Inside a range (or within the margin of
     * one) mpv seeks in its cache and still needs the current request.
     */
    fun needsNewRead(targetSec: Double, cachedRanges: List<Pair<Double, Double>>, marginSec: Double = 1.0): Boolean =
        cachedRanges.none { (start, end) -> targetSec >= start - marginSec && targetSec <= end + marginSec }
}
