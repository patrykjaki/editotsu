package ani.dantotsu.media.anime.selector

import java.util.Locale

/**
 * Persistence-owned canonicalizers for stable group / tracker /
 * source-service keys. None of these call UI presentation code; they
 * are independent of `SourceRowClassifier.cleanDisplayTitle`.
 */
object StableGroupCanonicalizer {
    fun canonicalKey(rawGroup: String?): String? {
        if (rawGroup.isNullOrBlank()) return null
        if (looksLikeTracker(rawGroup)) return null
        if (looksLikeService(rawGroup)) return null
        if (looksLikeResolution(rawGroup)) return null
        if (looksLikeAudioFormat(rawGroup)) return null
        if (looksLikeCodecOrHdr(rawGroup)) return null
        if (looksLikeSourceClass(rawGroup)) return null
        return rawGroup.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }

    fun isValidKey(raw: String?): Boolean =
        raw != null && canonicalKey(raw) == raw.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)

    private val TRACKER_NAMES = setOf(
        "nyaasi", "1337x", "nekobt", "hon3yhoneyp"
    )
    private fun looksLikeTracker(s: String): Boolean {
        val t = s.trim().lowercase(Locale.ROOT)
        return t in TRACKER_NAMES
    }

    private val SERVICE_ALIASES = mapOf(
        "nf" to "netflix",
        "amzn" to "amazon",
        "cr" to "crunchyroll",
        "dsnp" to "disney+",
        "hulu" to "hulu",
        "at-x" to "at-x",
        "atx" to "at-x",
    )
    private fun looksLikeService(s: String): Boolean {
        val t = s.trim().lowercase(Locale.ROOT)
        return SERVICE_ALIASES.containsKey(t) || t in SERVICE_ALIASES.values
    }

    private val RESOLUTION = Regex(
        """^\d{3,4}p$|^\d{3,4}x\d{3,4}$|^4k$|^2k$""",
        RegexOption.IGNORE_CASE,
    )
    private fun looksLikeResolution(s: String) =
        RESOLUTION.matches(s.trim())

    private val AUDIO_FORMAT = Regex(
        """^(?:AAC(?:2\.0|5\.1)?|AC3|DTS|FLAC|OPUS|MP3|EAC3|DDP(?:2\.0|5\.1|2|5)?|DTS-?HD|TRUEHD|ATMOS)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun looksLikeAudioFormat(s: String) =
        AUDIO_FORMAT.matches(s.trim().split(" ").firstOrNull() ?: "")

    private val CODEC_OR_HDR = Regex(
        """^(?:HEVC|AVC|H\.?264|H\.?265|AV1|VP9|X\.?264|X\.?265|HDR10\+?|HDR|DV|DOLBY|VIERX|10-?BITS?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun looksLikeCodecOrHdr(s: String) =
        CODEC_OR_HDR.matches(s.trim().split(" ").firstOrNull() ?: "")

    private val SOURCE_CLASS = Regex(
        """^(?:WEB-?DL|WEBDL|WEB-?RIP|BLURAY|BDRIP|HDTV|DVD-?RIP|REMUX)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun looksLikeSourceClass(s: String) =
        SOURCE_CLASS.matches(s.trim().split(" ").firstOrNull() ?: "")
}

object TrackerCanonicalizer {
    fun canonicalKey(rawTracker: String?): String? {
        if (rawTracker.isNullOrBlank()) return null
        return rawTracker.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }
}

/**
 * Persistence-owned canonical soft-value normalizer for the family
 * payload. Free-text soft values are trim + whitespace-collapse +
 * lowercase(Locale.ROOT); resolution additionally maps the display
 * alias `4K` to the canonical internal value `2160p`.
 *
 * Tracker stays through [TrackerCanonicalizer]; source service
 * stays through [SourceServiceCanonicalizer]. No UI presentation
 * code is called.
 */
object StableSoftCanonicalizer {
    fun canonicalText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
    }

    fun canonicalResolution(raw: String?): String? {
        val t = canonicalText(raw) ?: return null
        // 4K is a display alias only; the stable internal value is
        // 2160p. 1440p stays 1440p (it is NOT renamed 2K).
        return if (t == "4k") "2160p" else t
    }
}

object SourceServiceCanonicalizer {
    fun canonicalKey(rawService: String?): String? {
        if (rawService.isNullOrBlank()) return null
        // Bracketed form first (e.g. "[NF]", "[AMZN]", "[ Netflix ]"):
        // the bracket CONTENT is normalized and alias-mapped too.
        val bracket = Regex("""\[([A-Za-z0-9 .+-]+)]""")
            .find(rawService)?.groupValues?.get(1)
        val core = collapseWs(bracket ?: rawService)
        if (core.isEmpty()) return null
        // Short aliases, standalone or bracketed.
        val shortMap = mapOf(
            "nf" to "netflix",
            "amzn" to "amazon",
            "cr" to "crunchyroll",
            "dsnp" to "disney+",
            "hulu" to "hulu",
            "at-x" to "at-x",
        )
        shortMap[core.lowercase(Locale.ROOT)]?.let { return it }
        // Known service names. No substring matching of arbitrary
        // words; explicit unknown values stay trim/collapse/lower.
        val known = setOf(
            "netflix", "amazon", "crunchyroll",
            "disney+", "hulu", "at-x",
        )
        val lower = core.lowercase(Locale.ROOT)
        if (lower in known) return lower
        return lower
    }

    private fun collapseWs(s: String): String {
        return s.trim().replace(Regex("""\s+"""), " ")
    }
}
