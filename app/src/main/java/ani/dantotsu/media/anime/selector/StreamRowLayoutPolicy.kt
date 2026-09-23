package ani.dantotsu.media.anime.selector

/**
 * Pure layout policy for picker rows.
 *
 * STREAM rows (single-line host/track/resolution labels, never
 * carrying release-group / clean-title / seeders / size-tracker
 * metadata) use the compact inline card
 * (`item_url_stream_inline.xml`): one primary horizontal content
 * row with the server name (weight 1, single-line, ellipsized),
 * compact pills, and subtitle / download / info actions.
 * RELEASE rows always use the full v3.7 card (`item_url.xml`).
 *
 * Width policy: the name shrinks first (weight 1 + ellipsize);
 * pills and actions are wrap_content at the row end and are never
 * set GONE for width, so they cannot disappear at ordinary
 * widths. Action touch targets stay at full 48dp; only the icon
 * glyph may render smaller inside the target.
 *
 * Pure JVM decision logic; applying it to views stays in the
 * adapter. No persistence/identity role.
 */
object StreamRowLayoutPolicy {
    /** Which card composition a row uses. */
    enum class RowLayout {
        /** Single-row inline card for direct-stream rows. */
        INLINE_STREAM,
        /** Full v3.7 card for release/torrent rows. */
        FULL_RELEASE,
    }

    /** Minimum action touch-target edge; matches both card layouts. */
    const val MIN_ACTION_TOUCH_DP = 48

    fun layoutFor(presentation: SourcePresentationKind): RowLayout =
        if (presentation == SourcePresentationKind.STREAM) {
            RowLayout.INLINE_STREAM
        } else {
            RowLayout.FULL_RELEASE
        }

    /**
     * Adapter-level decision from an extension server name. All
     * rows of one extractor share `server.name` as both display
     * label and quality text, so every row of one section resolves
     * to the same layout deterministically.
     */
    fun isInlineServerName(serverName: String): Boolean =
        layoutFor(SourceRowClassifier.classifyPresentation(serverName)) ==
            RowLayout.INLINE_STREAM
}
