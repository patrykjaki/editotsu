package ani.dantotsu.media.anime.player

/**
 * CP4-B (REPO_REVIEW §3.6): mandatory redaction at the mpv logging boundary.
 *
 * Media URIs carry signed-query credentials and mpv command/option values can embed
 * Authorization/Cookie/API-key headers. Every log statement that emits a URI, command
 * argument, option value, or property value MUST route through this object — raw values
 * never reach logcat or the shareable [ani.dantotsu.util.Logger] file.
 *
 * Pure Kotlin so the sentinel-secret tests run on the JVM.
 */
object MpvLogRedactor {

    /** Query-string values (token=..., sig=...) are always masked; keys stay for debugging. */
    private val URI_QUERY = Regex("([?&])([^=&\\s]+)=([^&\\s]*)")

    /** Credential-bearing header payloads: "Authorization: Bearer x", "Cookie: k=v", etc.
     *  Everything after the separator is consumed so multi-word tokens cannot leak. */
    private val SENSITIVE_HEADER = Regex(
        "(?i)(authorization|cookie|referer|user-agent|x-api-key|api[_-]?key|token|secret|password)\\s*[:=]\\s*.*"
    )

    /**
     * mpv option/argument KEYS whose VALUE token is sensitive when it appears as a separate
     * argv element (e.g. ["set", "http-header-fields", "Authorization: ..."]).
     */
    private val SENSITIVE_ARG_KEYS = setOf(
        "http-header-fields", "http-header-fields-append",
        "http-post-fields", "user-agent", "referer",
        "ytdl-raw-options", "stream-lavf-o", "lavf-o", "file-local-options"
    )

    const val REDACTED = "<redacted>"

    /**
     * Allowlisted mpv options whose values are safe to log verbatim (no credentials, no
     * user-identifying payload). Everything else falls back to [REDACTED] — redaction is
     * the default, not the exception.
     */
    private val SAFE_OPTION_NAMES = setOf(
        "config", "vo", "hwdec", "hwdec-codecs", "sub-auto",
        "keep-open", "ytdl", "force-window", "idle",
        "gpu-context", "opengl-es", "slang", "alang",
        "sub-font-provider", "sub-font", "embeddedfonts"
    )

    /** Path-style options are logged as kind markers only (never the actual paths). */
    private val PATH_OPTION_SUFFIX = "-dir"

    fun redactUri(raw: String): String {
        if (raw.isEmpty()) return raw
        return URI_QUERY.replace(raw) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}=$REDACTED"
        }
    }

    /**
     * Redact an mpv command argv. Masks:
     *  - the value token following a sensitive key (separate-token form);
     *  - inline `key=value` forms of sensitive keys;
     *  - credential patterns inside any token (header strings);
     *  - query-string credentials inside any URI-like token.
     */
    fun redactCommandArgs(args: List<String>): List<String> {
        var maskNext = false
        return args.map { arg ->
            when {
                maskNext -> {
                    maskNext = false
                    REDACTED
                }
                SENSITIVE_HEADER.containsMatchIn(arg) ->
                    SENSITIVE_HEADER.replace(arg, "$1=$REDACTED")
                else -> {
                    val trimmed = arg.removePrefix("--")
                    val key = trimmed.substringBefore('=')
                    val hasInlineValue = '=' in trimmed
                    if (key in SENSITIVE_ARG_KEYS) {
                        if (hasInlineValue) "$key=$REDACTED" else {
                            maskNext = true
                            arg
                        }
                    } else {
                        redactUri(arg)
                    }
                }
            }
        }
    }

    /**
     * Redact an mpv OPTION value by name (used for `setOptionString` logging):
     * safe allowlist passes through, path options become kind markers, all else [REDACTED].
     */
    fun redactOptionValue(name: String, value: String): String = when {
        name in SAFE_OPTION_NAMES -> value
        name.endsWith(PATH_OPTION_SUFFIX) -> "[pathKind: ${name.removeSuffix(PATH_OPTION_SUFFIX)}]"
        else -> REDACTED
    }

    /**
     * Redact an mpv PROPERTY value by name (used for `setPropertyString` logging):
     * filename/path/stream properties may embed signed URLs → URI-redacted;
     * unknown/suspicious names → [REDACTED]; benign playback state → passthrough.
     */
    private val SAFE_PROPERTY_NAMES = setOf(
        "pause", "speed", "volume", "mute", "deinterlace",
        "sub-delay", "audio-delay", "video-rotate", "vid", "aid", "sid",
        "glsl-shaders"
    )
    private val PATHY_PROPERTY_NAMES = setOf(
        "stream-open-filename", "file-open-filename",
        "sub-file-paths", "audio-file-paths", "snapshot-path",
        "config-dir", "cache-dir"
    )

    fun redactPropertyValue(name: String, value: String): String = when {
        name in SAFE_PROPERTY_NAMES -> value
        name in PATHY_PROPERTY_NAMES -> "[pathKind: $name]"
        else -> REDACTED
    }

    /**
     * CP4v1-04: generic scrubber for ARBITRARY diagnostic text — chiefly Throwable messages,
     * which JNI/Android/mpv may fill with the original option, header, or failing source URI.
     * Strips credential/header patterns and URI query values from any text before it is
     * logged or placed into a shareable [PlaybackError].
     */
    fun redactDiagnosticText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = SENSITIVE_HEADER.replace(text) { m -> "${m.groupValues[1]}=$REDACTED" }
        out = URI_QUERY.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}=$REDACTED"
        }
        return out
    }
}
