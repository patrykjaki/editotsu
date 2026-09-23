package ani.dantotsu.parsers

import java.net.URI

/**
 * Beta03 playback-compat seam: extension auxiliary track URL/header handling.
 *
 * Pure JVM-safe helpers. The Aniyomi adapter delegates here so subtitle/audio
 * track conversion neither drops the source header context nor leaves relative
 * URLs unresolved. See EXT-COMPAT-1/6.
 */
object ExtensionTrackCompat {

    /**
     * Resolve an extension track URL against the main video URL.
     * Absolute (http/https/file/content) and protocol-relative URLs pass
     * through (protocol-relative inherits the video scheme); anything else is
     * resolved against the video URL. Blank stays blank.
     */
    fun resolveTrackUrl(trackUrl: String, videoUrl: String): String {
        val trimmed = trackUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("file://") || trimmed.startsWith("content://")
        ) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            val scheme = runCatching { URI(videoUrl).scheme }.getOrNull()
                ?.takeIf { it == "http" || it == "https" } ?: "https"
            return "$scheme:$trimmed"
        }
        return runCatching {
            val base = URI(videoUrl)
            base.resolve(trimmed).toString()
        }.getOrDefault(trimmed)
    }

    /**
     * Redacted one-line diagnostic for an auxiliary track attach. Header NAMES
     * are listed so transport presence is provable; header VALUES are never
     * included (cookies/tokens must not enter logs or reviewer artifacts).
     */
    fun redactedTrackLine(kind: String, url: String, headers: Map<String, String>): String {
        val names = headers.keys.sorted().joinToString(",")
        return "aux-track kind=$kind url=$url headerKeys=[$names]"
    }
}
