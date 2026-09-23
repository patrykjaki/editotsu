package ani.dantotsu.media.anime.selector

/**
 * Persistence-owned URL canonicaliser.
 *
 * Strict `java.net.URI` parsing. Reject malformed / unsupported input
 * (malformed authority, out-of-range port, userInfo present, etc.)
 * rather than silently canonicalising into another valid identity.
 *
 * Default-port stripping:
 *   https:443 stripped
 *   http:80 stripped
 *   https:80 preserved
 *   http:443 preserved
 *
 * Path is preserved verbatim. Query and fragment are intentionally
 * discarded only AFTER the URL passes strict validation.
 */
data class CanonicalUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val rawPath: String,
) {
    fun canonicalUrlString(): String {
        val sb = StringBuilder()
        sb.append(scheme).append("://").append(host)
        if (port != null && port >= 0) sb.append(":").append(port)
        sb.append(rawPath)
        return sb.toString()
    }
}

object UrlCanonicaliser {
    fun canonicalise(raw: String?): CanonicalUrl? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        // Explicit empty port (https://host:/x, https://[::1]:/x):
        // java.net.URI reports port -1 for these, so detect the
        // trailing ':' in the raw authority directly. No port at
        // all (https://host/x) has no trailing colon and stays
        // valid.
        if (uri.rawAuthority?.endsWith(":") == true) return null
        if (uri.host.isNullOrEmpty()) return null
        if (uri.port != -1 && (uri.port !in 1..65535)) return null
        val host = uri.host.lowercase(java.util.Locale.ROOT)
        val rawPort = uri.port
        val isDefault = (scheme == "https" && rawPort == 443) ||
                        (scheme == "http" && rawPort == 80)
        val finalPort: Int? = when {
            rawPort == -1 -> null
            isDefault -> null
            else -> rawPort
        }
        val rawPath = uri.rawPath ?: ""
        return CanonicalUrl(scheme, host, finalPort, rawPath)
    }
}
