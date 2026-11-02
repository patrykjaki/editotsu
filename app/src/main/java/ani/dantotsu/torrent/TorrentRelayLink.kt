package ani.dantotsu.torrent

/** A link to the local torrent relay (TorrentServerManager.getLink), parsed back. */
data class TorrentRelayLink(val hash: String, val index: Int) {
    companion object {
        private val PATTERN =
            Regex("""^http://127\.0\.0\.1:\d+/stream\?hash=([0-9A-Fa-f]{40})&index=(\d+)$""")

        fun parse(url: String): TorrentRelayLink? {
            val m = PATTERN.matchEntire(url.trim()) ?: return null
            val index = m.groupValues[2].toIntOrNull() ?: return null
            return TorrentRelayLink(m.groupValues[1].uppercase(), index)
        }
    }
}
