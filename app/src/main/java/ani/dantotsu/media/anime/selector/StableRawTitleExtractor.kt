package ani.dantotsu.media.anime.selector

/**
 * Persistence-owned stable raw release-title extraction.
 *
 * Produces a deterministic, canonicalized raw release title that
 * strips volatile / provenance-only metadata BEFORE the direct
 * stable-key hashing. The output is used as the `raw=` component
 * of the canonical stable release.
 *
 * Metadata is stripped ONLY as bounded segments, never as
 * "everything from here to end of line":
 *
 *   - newline metadata blocks: a WHOLE line is dropped only when
 *     the entire line is a bounded metadata carrier (tracker line,
 *     seeder line, size line, cog line, URL-only line, or an
 *     explicit `Key: value` provenance line)
 *   - pipe-delimited inline metadata: within one line, text is
 *     split on `|` and a SEGMENT is dropped only when that
 *     segment alone is a bounded metadata carrier
 *
 * Dropped carriers:
 *
 *   - explicit tracker provenance (a segment/line starting with 📂)
 *   - seeder / leecher / completion counters (segments/lines
 *     starting with 👤)
 *   - file size expressions (segments/lines starting with 💾)
 *   - provider / indexer / host metadata carriers (segments/lines
 *     of the form `Key: value` for a bounded key set)
 *   - segments/lines starting with ⚙️
 *   - magnet: or http(s) URL tokens pasted into the description
 *     (token-bounded `[^\s]+`; separators are preserved as spaces)
 *
 * The actual release title/body and meaningful release tokens are
 * preserved verbatim (whitespace collapsed). Returns null when no
 * meaningful release title remains.
 *
 * The implementation NEVER calls `SourceRowClassifier.cleanDisplayTitle`,
 * `SourceRowSummarizer`, or any UI presentation function. It is a
 * narrow persistence-owned normalizer.
 */
object StableRawTitleExtractor {
    private val URL_TOKEN = Regex("""\b(magnet:\?[^\s]+|https?://[^\s]+)""",
        RegexOption.IGNORE_CASE)

    private val PROVENANCE_KEYS = setOf(
        "provider", "source", "host", "tracker", "index",
        "group", "uploader", "imdb", "anilist", "mal",
    )

    /**
     * Extract a stable raw release title from a candidate's raw
     * description. Returns null when no meaningful release title
     * remains (metadata-only input).
     */
    fun extract(rawDescription: String?): String? {
        if (rawDescription.isNullOrBlank()) return null
        val keptLines = mutableListOf<String>()
        for (rawLine in rawDescription.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // A line WITHOUT any pipe is either a metadata-only
            // block line (tracker/seeder/size/cog/URL/provenance)
            // or a title line. A line WITH pipes is inline
            // pipe-delimited metadata: split first so that a
            // leading 📂 token cannot consume the title segment.
            if (!line.contains('|')) {
                if (isMetadataOnlyLine(line)) continue
            }
            val keptSegments = line.split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isMetadataSegment(it) }
            if (keptSegments.isEmpty()) continue
            // URL tokens inside an otherwise meaningful segment are
            // removed token-wise; this cannot consume the title
            // because the token matcher stops at whitespace.
            val cleaned = keptSegments.map { seg ->
                URL_TOKEN.replace(seg, " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) continue
            keptLines.add(cleaned.joinToString(" "))
        }
        if (keptLines.isEmpty()) return null
        return keptLines.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifEmpty { null }
    }

    /**
     * True iff an entire line is a bounded metadata carrier and
     * carries no release title.
     */
    private fun isMetadataOnlyLine(line: String): Boolean {
        if (line.startsWith("📂")) return true
        if (line.startsWith("👤")) return true
        if (line.startsWith("💾")) return true
        if (line.startsWith("⚙")) return true
        if (isProvenanceKv(line)) return true
        if (URL_TOKEN.matches(line)) return true
        return false
    }

    /**
     * True iff a single `|`-delimited segment is a bounded metadata
     * carrier and carries no release title.
     */
    private fun isMetadataSegment(segment: String): Boolean {
        if (segment.startsWith("📂")) return true
        if (segment.startsWith("👤")) return true
        if (segment.startsWith("💾")) return true
        if (segment.startsWith("⚙")) return true
        if (isProvenanceKv(segment)) return true
        if (URL_TOKEN.matches(segment)) return true
        return false
    }

    private fun isProvenanceKv(text: String): Boolean {
        val key = text.substringBefore(':', "").trim().lowercase(java.util.Locale.ROOT)
        return key in PROVENANCE_KEYS && text.contains(':')
    }
}
