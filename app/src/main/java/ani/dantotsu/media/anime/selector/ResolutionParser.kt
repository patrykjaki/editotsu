package ani.dantotsu.media.anime.selector

import java.util.Locale

/**
 * The one reader of video resolution in release and stream names, used by the card
 * pills, the filters, the quality grouping, the stream group names and the remembered
 * family. It accepts "1080p", "1080 p", "1080i", "4K", "[4K]", "(4K)", "UHD", "2K",
 * "8K" and "1920x1080"; the first one in the text wins.
 */
object ResolutionParser {
    /** Every resolution token; group 1 = NNNp/i height, groups 2/3 = width/height, 4 = 4k/uhd, 5 = 2k, 6 = 8k. */
    val TOKEN = Regex(
        """(?i)(?<![a-z0-9])(?:(\d{3,4})\s?[pi]|(\d{3,4})\s?[x×]\s?(\d{3,4})|(4k|uhd)|(2k)|(8k))(?![a-z0-9])""",
    )

    private const val MIN_HEIGHT = 144
    private const val MAX_HEIGHT = 4320

    /** Height in pixels of the first resolution token in [text], or null. */
    fun height(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        for (m in TOKEN.findAll(text)) {
            val g = m.groupValues
            val h = when {
                g[1].isNotEmpty() -> g[1].toIntOrNull()
                g[3].isNotEmpty() -> g[3].toIntOrNull()
                g[4].isNotEmpty() -> 2160
                g[5].isNotEmpty() -> 1440
                g[6].isNotEmpty() -> 4320
                else -> null
            }
            if (h != null && h in MIN_HEIGHT..MAX_HEIGHT) return h
        }
        return null
    }

    /** "1080p", "2160p"; null for null. */
    fun label(height: Int?): String? = height?.let { "${it}p" }

    // Spelling used inside stored exact release keys ("res=1920x1080", "res=4k").
    // Frozen: changing it would change the keys already saved on devices.
    private val LEGACY_PX = Regex("""\b(\d{3,4}p)\b""")
    private val LEGACY_WXH = Regex("""\b(\d{3,4}x\d{3,4})\b""")
    private val LEGACY_4K = Regex("""\b(4k|2k)\b""", RegexOption.IGNORE_CASE)

    /** The resolution token as older builds wrote it into exact keys. Not for display or comparison. */
    fun legacyToken(raw: String): String? {
        LEGACY_PX.find(raw)?.groupValues?.get(1)?.let { return it }
        LEGACY_WXH.find(raw)?.groupValues?.get(1)?.let { return it }
        LEGACY_4K.find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { return it }
        return null
    }
}
