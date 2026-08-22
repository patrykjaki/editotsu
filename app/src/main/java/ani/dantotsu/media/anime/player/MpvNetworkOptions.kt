package ani.dantotsu.media.anime.player

object MpvNetworkOptions {

    private val SENSITIVE_HEADER_KEYS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "proxy-authorization",
        "token",
        "x-auth-token"
    )

    fun validateHeaderKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.contains('\r') || trimmed.contains('\n')) {
            throw IllegalArgumentException("CRLF injection detected in header key: $trimmed")
        }
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Header key cannot be empty")
        }
        return trimmed
    }

    fun validateHeaderValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.contains('\r') || trimmed.contains('\n')) {
            throw IllegalArgumentException("CRLF injection detected in header value")
        }
        return trimmed
    }

    fun extractUserAgent(headers: Map<String, String>): String? {
        val entry = headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
        return try {
            entry?.value?.let { validateHeaderValue(it) }
        } catch (_: Exception) {
            entry?.value?.replace("\r", "")?.replace("\n", "")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun extractReferrer(headers: Map<String, String>): String? {
        val entry = headers.entries.firstOrNull {
            it.key.equals("referer", ignoreCase = true) || it.key.equals("referrer", ignoreCase = true)
        }
        return try {
            entry?.value?.let { validateHeaderValue(it) }
        } catch (_: Exception) {
            entry?.value?.replace("\r", "")?.replace("\n", "")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun formatHeaderFields(headers: Map<String, String>): String {
        val filtered = headers.entries.filterNot {
            it.key.equals("user-agent", ignoreCase = true) ||
            it.key.equals("referer", ignoreCase = true) ||
            it.key.equals("referrer", ignoreCase = true)
        }
        if (filtered.isEmpty()) return ""

        return filtered.mapNotNull { entry ->
            try {
                val key = validateHeaderKey(entry.key)
                val value = validateHeaderValue(entry.value)
                // Escape backslashes and commas for MPV string list
                val escapedValue = value.replace("\\", "\\\\").replace(",", "\\,")
                "$key: $escapedValue"
            } catch (_: Exception) {
                null
            }
        }.joinToString(",")
    }

    fun buildPerFilePerformanceOptions(
        sourceClass: PlaybackSourceClass = PlaybackSourceClass.LOCAL_FILE,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L
    ): String {
        val optionsList = mutableListOf<String>()

        val ua = extractUserAgent(headers)
        if (!ua.isNullOrBlank()) {
            val escapedUa = ua.replace("\\", "\\\\").replace("\"", "\\\"").replace(",", "\\,")
            optionsList.add("user-agent=\"$escapedUa\"")
        }

        val ref = extractReferrer(headers)
        if (!ref.isNullOrBlank()) {
            val escapedRef = ref.replace("\\", "\\\\").replace("\"", "\\\"").replace(",", "\\,")
            optionsList.add("referrer=\"$escapedRef\"")
        }

        val headerFields = formatHeaderFields(headers)
        if (headerFields.isNotBlank()) {
            val escapedFields = headerFields.replace("\"", "\\\"")
            optionsList.add("http-header-fields=\"$escapedFields\"")
        }

        if (startPositionMs > 0L && sourceClass != PlaybackSourceClass.TORRENT_LOCALHOST) {
            val startSec = TimeConverter.msToSeconds(startPositionMs)
            optionsList.add("start=$startSec")
        }

        when (sourceClass) {
            PlaybackSourceClass.DIRECT_HTTP -> {
                optionsList.add("demuxer-max-bytes=67108864")
                optionsList.add("demuxer-max-back-bytes=33554432")
                optionsList.add("network-timeout=20")
                optionsList.add("cache-pause-initial=no")
                optionsList.add("cache-pause-wait=1")
            }
            PlaybackSourceClass.HLS -> {
                optionsList.add("demuxer-max-bytes=67108864")
                optionsList.add("network-timeout=20")
            }
            PlaybackSourceClass.TORRENT_LOCALHOST -> {
                optionsList.add("demuxer-max-bytes=67108864")
                optionsList.add("demuxer-max-back-bytes=33554432")
                optionsList.add("demuxer-readahead-secs=30")
                optionsList.add("network-timeout=30")
                optionsList.add("cache-pause-initial=no")
                optionsList.add("cache-pause-wait=1")
            }
            PlaybackSourceClass.LOCAL_FILE,
            PlaybackSourceClass.CONTENT_FD -> {
                // Default local options
            }
        }

        return optionsList.joinToString(",")
    }

    fun buildPerFileOptions(
        headers: Map<String, String>,
        startPositionMs: Long = 0L
    ): String {
        return buildPerFilePerformanceOptions(
            sourceClass = PlaybackSourceClass.LOCAL_FILE,
            headers = headers,
            startPositionMs = startPositionMs
        )
    }

    fun redactHeadersForLogging(headers: Map<String, String>): Map<String, String> {
        return headers.mapValues { (key, value) ->
            if (SENSITIVE_HEADER_KEYS.contains(key.lowercase())) {
                "<REDACTED>"
            } else {
                value
            }
        }
    }

    fun getRedactedOptionsDescription(
        headers: Map<String, String>,
        startPositionMs: Long,
        sourceClass: PlaybackSourceClass = PlaybackSourceClass.LOCAL_FILE
    ): String {
        val redactedHeaders = redactHeadersForLogging(headers)
        return "sourceClass=$sourceClass, headers=$redactedHeaders, startMs=$startPositionMs"
    }
}

