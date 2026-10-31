package ani.dantotsu.parsers

import java.net.URL
import java.net.URLDecoder

/**
 * Upstream Oct sync D (upstream 7eaf0f69 / d3dbb860): stream format
 * detection for extension video URLs. Pure JVM, no Android types.
 *
 * Only the AniyomiAdapter part of upstream's detector is ported. The
 * Uri/HttpUrl overloads, segment/m3u8 URL checks and HLS magic sniffing
 * back upstream's ExoPlayer media-source and OkHttp interceptor code,
 * which Editotsu's MPV-only DantotsuPlayerManager does not have.
 *
 * Editotsu change: [isTorrentStream] also recognises Editotsu's own relay
 * URL (`/stream?hash=<40 hex>&index=N`, see TorrentServerManager.getLink),
 * so a relay URL is never classified as localhost HLS.
 */
object StreamTypeDetector {
    private val TORRENT_HASH_REGEX = Regex("""/[0-9a-fA-F]{40}(?:/|$)""")
    private val RELAY_HASH_QUERY_REGEX = Regex("""(?:^|&)hash=[0-9a-fA-F]{40}(?:&|$)""")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun isLocalhost(host: String?): Boolean = host != null && host in LOOPBACK_HOSTS
    fun isLocalhost(url: URL?): Boolean = isLocalhost(url?.host)

    fun isTorrentStream(host: String?, port: Int, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        if (port == 11470 || port == 8090) return true
        val p = path ?: ""
        val q = query ?: ""
        if (p == "/stream" || RELAY_HASH_QUERY_REGEX.containsMatchIn(q)) return true
        return p.contains("torrent", ignoreCase = true) ||
                q.contains("torrent", ignoreCase = true) ||
                q.contains("infohash", ignoreCase = true) ||
                q.contains("tr=") ||
                TORRENT_HASH_REGEX.containsMatchIn(p)
    }

    fun isTorrentStream(url: URL?): Boolean =
        if (url == null) false else isTorrentStream(url.host, url.port, url.path, url.query)

    private fun extractTargetUrl(query: String?): String? {
        if (query.isNullOrBlank()) return null
        for (key in listOf("url=", "file=")) {
            val idx = query.indexOf(key)
            if (idx != -1) {
                val end = query.indexOf('&', idx)
                val raw = if (end != -1) query.substring(idx + key.length, end) else query.substring(idx + key.length)
                return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
        }
        return null
    }

    fun isLocalMp4(host: String?, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        val p = path ?: ""
        val q = query ?: ""
        val target = extractTargetUrl(q)
        if (target != null) {
            val targetPath = target.substringBefore('?')
            if (targetPath.endsWith(".mp4", ignoreCase = true) || targetPath.endsWith(".m4v", ignoreCase = true)) return true
            if (targetPath.endsWith(".m3u8", ignoreCase = true) || targetPath.endsWith(".mpd", ignoreCase = true)) return false
        }
        return p.endsWith(".mp4", ignoreCase = true) || p.endsWith(".m4v", ignoreCase = true) ||
                q.contains(".mp4", ignoreCase = true) || q.contains("format=mp4", ignoreCase = true)
    }

    fun isLocalMp4(url: URL?): Boolean =
        if (url == null) false else isLocalMp4(url.host, url.path, url.query)

    fun isLocalDash(host: String?, path: String?, query: String?): Boolean {
        if (!isLocalhost(host)) return false
        val p = path ?: ""
        val q = query ?: ""
        val target = extractTargetUrl(q)
        if (target != null) {
            val targetPath = target.substringBefore('?')
            if (targetPath.endsWith(".mpd", ignoreCase = true)) return true
            if (targetPath.endsWith(".m3u8", ignoreCase = true) || targetPath.endsWith(".mp4", ignoreCase = true)) return false
        }
        return p.endsWith(".mpd", ignoreCase = true) || p.contains("/dash/", ignoreCase = true) ||
                q.contains(".mpd", ignoreCase = true) || q.contains("format=mpd", ignoreCase = true)
    }

    fun isLocalDash(url: URL?): Boolean =
        if (url == null) false else isLocalDash(url.host, url.path, url.query)

    /** Path/file-name classification (upstream getVideoType with 7eaf0f69 patterns). */
    fun videoTypeFromPath(fileName: String): VideoType? = when {
        fileName.endsWith(".mp4", ignoreCase = true) ||
                fileName.endsWith(".mkv", ignoreCase = true) ||
                fileName.endsWith(".webm", ignoreCase = true) ||
                fileName.contains("/mp4", ignoreCase = true) ||
                fileName.contains("format=mp4", ignoreCase = true) -> VideoType.CONTAINER

        fileName.contains(".m3u8", ignoreCase = true) ||
                fileName.contains("/m3u8", ignoreCase = true) ||
                fileName.contains("/pl/", ignoreCase = true) ||
                fileName.contains("/playlist/", ignoreCase = true) ||
                fileName.contains("/hls/", ignoreCase = true) ||
                fileName.contains("format=m3u8", ignoreCase = true) ||
                fileName.contains("type=m3u8", ignoreCase = true) ||
                fileName.equals("m3u8", ignoreCase = true) -> VideoType.M3U8

        fileName.contains(".mpd", ignoreCase = true) ||
                fileName.contains("/mpd", ignoreCase = true) ||
                fileName.contains("/dash/", ignoreCase = true) ||
                fileName.contains("format=mpd", ignoreCase = true) -> VideoType.DASH

        else -> null
    }

    /**
     * Fallback when path and query classification found nothing
     * (upstream 7eaf0f69 AniyomiAdapter hunk). A localhost torrent relay
     * stays unclassified so the caller's CONTAINER default applies; the
     * title/quality hint is not applied to it either.
     */
    fun fallbackFormat(url: URL, videoTitle: String?, quality: String?): VideoType? {
        if (isLocalMp4(url)) return VideoType.CONTAINER
        val torrent = isTorrentStream(url)
        if (isLocalhost(url) && !torrent) {
            return if (isLocalDash(url)) VideoType.DASH else VideoType.M3U8
        }
        if (torrent) return null
        val hint = "${videoTitle.orEmpty()} ${quality.orEmpty()}"
        return when {
            hint.contains("hls", ignoreCase = true) || hint.contains("m3u8", ignoreCase = true) -> VideoType.M3U8
            hint.contains("dash", ignoreCase = true) || hint.contains("mpd", ignoreCase = true) -> VideoType.DASH
            else -> null
        }
    }
}
