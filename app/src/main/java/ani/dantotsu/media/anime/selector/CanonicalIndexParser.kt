package ani.dantotsu.media.anime.selector

/**
 * Pure persistence-owned exact magnet-index parser.
 *
 * Parses query tokens in source order (`&`/`;` separated) and
 * inspects the FIRST token whose key is EXACTLY `index`.
 * `xindex`, `someindex`, and any other near-miss key are ignored.
 *
 *   no exact `index` token          -> canonical 0
 *   first `index` token with:
 *     missing `=` or empty value    -> null (no stable exact key)
 *     non-digit / signed / malformed-> null
 *     0..99999                      -> accepted canonical number
 *     > 99999                       -> null
 *
 * A malformed FIRST exact token is never rescued by a later
 * valid `index` token.
 */
object CanonicalIndexParser {
    private const val MAX_INDEX = 99_999L

    fun canonicalIndex(magnetUrl: String?): Long? {
        if (magnetUrl.isNullOrBlank()) return null
        val query = magnetUrl.substringAfter('?', "").substringBefore('#')
        for (token in query.split('&', ';')) {
            if (!token.contains('=')) {
                // A bare `index` token with no `=` is a present
                // but malformed exact key, not an absent one.
                if (token == "index") return null
                continue
            }
            if (token.substringBefore('=') != "index") continue
            val value = token.substringAfter('=')
            if (value.isEmpty()) return null
            if (!value.all { it.isDigit() }) return null
            val n = value.toLongOrNull() ?: return null
            if (n > MAX_INDEX) return null
            return n
        }
        return 0L
    }
}
