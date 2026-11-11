package ani.dantotsu.media.anime.cast

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Who may use the cast proxy and for what.
 *
 * The proxy listens on the LAN while a cast session runs. Every url the app hands the
 * receiver carries a random per-session token; requests without it are refused.
 * `/local` serves only the content/file uris registered for the current cast item.
 * `/proxy` serves only:
 *  - the media and subtitle urls registered for the current item, and
 *  - urls the proxy itself wrote into an HLS/DASH manifest it served for an allowed url
 *    (variant playlists, segments, keys, init segments, DASH segment templates).
 * Request headers for upstream fetches are stored here, never taken from the request.
 *
 * Pure JVM logic so it can be unit tested.
 */
class CastProxyAccess(
    private val random: SecureRandom = SecureRandom(),
    private val maxDerived: Int = MAX_DERIVED,
) {
    private val lock = Any()

    private var token: String? = null
    private var baseUrl: String? = null
    var generation: Long = 0L
        private set

    private val localUris = HashSet<String>()
    private val proxyUrls = HashMap<String, Map<String, String>>()
    private val derivedUrls = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?): Boolean =
            size > maxDerived
    }
    private val templates = ArrayList<Pair<Regex, Map<String, String>>>()

    val isOpen: Boolean get() = synchronized(lock) { token != null }

    /** Starts a new session: new token, empty allow-list. Returns its generation. */
    fun openSession(ipAddress: String, port: Int): Long = synchronized(lock) {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        token = bytes.joinToString("") { "%02x".format(it) }
        baseUrl = "http://$ipAddress:$port"
        clearItemLocked()
        generation += 1
        generation
    }

    /** Opens a session only if none is open. Returns the current generation. */
    fun ensureSession(ipAddress: String, port: Int): Long = synchronized(lock) {
        if (token != null) generation else openSession(ipAddress, port)
    }

    /** Closes the session [gen]; a newer session opened since is left alone. */
    fun closeSession(gen: Long) = synchronized(lock) {
        if (gen != generation) return@synchronized
        token = null
        baseUrl = null
        clearItemLocked()
    }

    private fun clearItemLocked() {
        localUris.clear()
        proxyUrls.clear()
        derivedUrls.clear()
        templates.clear()
    }

    /** Replaces what the receiver may fetch with the given item. */
    fun setItem(
        proxied: Map<String, Map<String, String>>,
        local: Collection<String>,
    ) = synchronized(lock) {
        clearItemLocked()
        proxyUrls.putAll(proxied)
        localUris.addAll(local)
    }

    fun tokenMatches(candidate: String?): Boolean {
        val current = synchronized(lock) { token } ?: return false
        if (candidate == null) return false
        return MessageDigest.isEqual(current.toByteArray(), candidate.toByteArray())
    }

    fun isLocalAllowed(uri: String): Boolean = synchronized(lock) { uri in localUris }

    /** Headers to fetch [url] with, or null when the receiver may not fetch it. */
    fun proxyHeadersFor(url: String): Map<String, String>? = synchronized(lock) {
        proxyUrls[url] ?: derivedUrls[url] ?: templates.firstOrNull { it.first.matches(url) }?.second
    }

    /** Called for each url the proxy writes into a manifest it served. */
    fun allowDerived(url: String, headers: Map<String, String>) = synchronized(lock) {
        if (token != null && url !in proxyUrls) derivedUrls[url] = headers
    }

    /** Called for DASH segment templates such as `seg-$Number%05d$.m4s`. */
    fun allowTemplate(template: String, headers: Map<String, String>) = synchronized(lock) {
        if (token == null) return@synchronized
        val regex = templateRegex(template)
        if (templates.none { it.first.pattern == regex.pattern }) templates.add(regex to headers)
    }

    /** `/proxy` url for [targetUrl] in the current session, or null when no session is open. */
    fun proxyUrl(targetUrl: String): String? {
        val (base, t) = synchronized(lock) { (baseUrl ?: return null) to (token ?: return null) }
        return base.toHttpUrl().newBuilder()
            .addPathSegment("proxy")
            .addQueryParameter(TOKEN_PARAM, t)
            .addQueryParameter("url", targetUrl)
            .build().toString()
    }

    /** `/local` url for [localUri] in the current session, or null when no session is open. */
    fun localUrl(localUri: String): String? {
        val (base, t) = synchronized(lock) { (baseUrl ?: return null) to (token ?: return null) }
        return base.toHttpUrl().newBuilder()
            .addPathSegment("local")
            .addQueryParameter(TOKEN_PARAM, t)
            .addQueryParameter("url", localUri)
            .build().toString()
    }

    enum class Decision { ALLOW, FORBIDDEN, NOT_FOUND }

    /** The allow/deny rule for one request: path, token and target url as received. */
    fun decide(path: String, tokenParam: String?, url: String?): Decision {
        if (!tokenMatches(tokenParam)) return Decision.FORBIDDEN
        if (url.isNullOrEmpty()) return Decision.FORBIDDEN
        return when {
            path.startsWith("/local") -> if (isLocalAllowed(url)) Decision.ALLOW else Decision.FORBIDDEN
            path.startsWith("/proxy") -> if (proxyHeadersFor(url) != null) Decision.ALLOW else Decision.FORBIDDEN
            else -> Decision.NOT_FOUND
        }
    }

    companion object {
        const val TOKEN_PARAM = "t"
        private const val TOKEN_BYTES = 16
        private const val MAX_DERIVED = 20_000
        val DASH_TOKEN_REGEX = Regex("""\$[A-Za-z0-9]*(?:%0\d+d)?\$""")

        /** Regex matching every url a DASH segment template can expand to. */
        fun templateRegex(template: String): Regex {
            val out = StringBuilder()
            var last = 0
            DASH_TOKEN_REGEX.findAll(template).forEach { m ->
                out.append(Regex.escape(template.substring(last, m.range.first)))
                // "$$" is a literal dollar sign in DASH templates.
                out.append(if (m.value == "$$") Regex.escape("$") else "[^/?#]*")
                last = m.range.last + 1
            }
            out.append(Regex.escape(template.substring(last)))
            return Regex(out.toString())
        }
    }
}
