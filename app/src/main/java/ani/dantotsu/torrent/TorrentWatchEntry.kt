package ani.dantotsu.torrent

/**
 * Pure decisions behind the Watch-tab torrent button (upstream-style
 * `mediaTorrentButton` in the source row). No Android, so the JVM tests
 * cover them directly.
 */
object TorrentWatchEntry {

    /**
     * Same floor as [TorrentServerManager.isAvailable] (API 28 / Android 9).
     * Kept as a pure copy so the button rule is testable without Injekt.
     */
    const val MIN_SDK = 28

    /** Button shows only when the Torrent add-on can actually run. */
    fun isButtonVisible(sdkInt: Int, torrentEnabled: Boolean): Boolean =
        sdkInt >= MIN_SDK && torrentEnabled

    /**
     * Clipboard text to prefill into the sheet's input, or null. Only
     * magnet links and http(s) `.torrent` URLs qualify (the sheet's own
     * classifier); content/file URIs and anything else are ignored. The
     * prefill is never auto-resolved: the user still taps Add.
     */
    fun clipboardPrefill(text: String?): String? =
        when (val payload = ManualTorrentInput.classify(text)) {
            is ManualTorrentPayload.Magnet -> payload.uri
            is ManualTorrentPayload.HttpUrl -> payload.url
            else -> null
        }

    /**
     * Episode the sheet anchors to: the Continue episode the Watch tab
     * shows, looked up by key or by episode number. Null means there is no
     * safe target, and the caller opens the sheet unanchored (plays, never
     * syncs) instead of guessing.
     */
    fun <E> anchorEpisode(
        continueKey: String?,
        episodes: Map<String, E>?,
        numberOf: (E) -> String,
    ): E? {
        if (continueKey == null || episodes.isNullOrEmpty()) return null
        return episodes[continueKey] ?: episodes.values.firstOrNull { numberOf(it) == continueKey }
    }
}
