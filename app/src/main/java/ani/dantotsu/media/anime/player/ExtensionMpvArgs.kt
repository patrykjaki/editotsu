package ani.dantotsu.media.anime.player

/**
 * Beta03 playback-compat seam: validated extension mpv option passthrough.
 *
 * Some extensions (current AniKoto) explicitly require an mpv demuxer option
 * (`demuxer-lavf-o = force_mpegts=1`) for their streams, which Editotsu's
 * adapter previously discarded. Arbitrary extension options must NOT cross
 * the trust boundary unvalidated: only allowlisted keys with strictly
 * validated values survive. See EXT-COMPAT-4/5.
 */
object ExtensionMpvArgs {

    private val ALLOWED_KEYS = setOf("demuxer-lavf-o")

    // Conservative value charset: no whitespace, quotes, backticks, $ or
    // control characters (CRLF injection impossible by construction).
    private val VALUE_RE = Regex("^[A-Za-z0-9_+=.,:;@/%-]{1,128}$")

    /**
     * Keep the first occurrence of each allowlisted key whose value validates;
     * drop everything else (unknown keys, blank keys, invalid values).
     */
    fun sanitize(args: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rawKey, rawValue) in args) {
            val key = rawKey.trim()
            if (key.isEmpty() || key !in ALLOWED_KEYS || out.containsKey(key)) continue
            val value = rawValue.trim()
            if (!VALUE_RE.matches(value)) continue
            out[key] = value
        }
        return out
    }

    /**
     * Render sanitized options as mpv per-file option assignments suitable for
     * appending to the `loadfile` options string. Values are already charset
     * constrained; only `"` needs escaping for the quoted form.
     */
    fun toFileOptions(sanitized: Map<String, String>): String {
        return sanitized.entries.joinToString(",") { (key, value) ->
            val escaped = value.replace("\"", "\\\"")
            "$key=\"$escaped\""
        }
    }
}
