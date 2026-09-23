package ani.dantotsu.media.anime.selector

import java.util.Locale

/**
 * Persistence-owned metadata extraction from a raw release-server
 * description. MUST NOT call `SourceRowClassifier.cleanDisplayTitle`,
 * `SourceRowSummarizer`, or any UI presentation code.
 *
 * Group confidence rule (mirror of the visual classifier's
 * `extractReleaseGroup` / `isGroupPositionPrefix` rule, re-implemented
 * as a narrow persistence-owned policy with no UI dependencies):
 *
 *   1. A bracket token is a candidate group only if it is
 *      non-empty, not all-digits, not in the bounded technical /
 *      language / source-service / resolution / codec / HDR /
 *      audio / source-class deny lists, and not a language tag.
 *
     *   2. A candidate is release-shaped when the prefix before the
     *      first bracket is one of:
     *         a. empty
     *         b. a single quality token (1080p / 720p / 2160p / 480p /
     *            1440p / 4k / 2k)
     *         c. a single tracker-shaped token whose canonical
     *            tracker key equals the parsed tracker's canonical
     *            key (e.g. prefix "nyaasi" corroborates parsed
     *            tracker "NyaaSi")
     *         d. a `[quality] [tracker]` or `[tracker] [quality]` pair
     *            where the tracker token's canonical key equals the
     *            parsed tracker's canonical key
 *
 *   3. The bracket token that satisfies the prefix rule is the
 *      release group. Any other bracket token is treated as a
 *      technical / source-service / codec / HDR marker and is
 *      rejected.
 *
 *   4. Without a parsed tracker, prefixes other than empty or
 *      single-quality are rejected. This rules out `Other 1080p
 *      [Ironclad] Title` while still accepting `[SubsPlease] Title`,
 *      `1080p [SubsPlease] Title`, and the explicit-tracker cases.
 */
data class StableReleaseMetadata(
    val rawTitle: String,
    val group: String?,
    val tracker: String?,
    val resolution: String?,
    val audioFormat: String?,
    val audioMode: String?,
    val sourceService: String?,
    val codec: String?,
    val hdr: String?,
    /**
     * True iff the persistence-owned rule is confident this
     * candidate is a release-shaped torrent / direct-debid
     * candidate. Used to gate the direct stable-key builder and
     * the family auto-selection. Generic direct streams (e.g.
     * `"1080p"`) are NOT release-shaped and stay on legacy
     * exact-name behavior.
     */
    val isReleaseShaped: Boolean = false,
)

object StableReleaseMetadataExtractor {
    private val BRACKETED = Regex("""\[([^\[\]]+)]""")
    private val TRACKER_FOLD = Regex("""📂\s*([A-Za-z0-9 .+\-]+)""")
    private val RES_PX = Regex("""\b(\d{3,4}p)\b""")
    private val RES_WXH = Regex("""\b(\d{3,4}x\d{3,4})\b""")
    private val RES_4K = Regex("""\b(4k|2k)\b""", RegexOption.IGNORE_CASE)
    private val AFMT = Regex(
        """\b(AAC|AC3|DTS(?:-?HD)?|EAC3|DDP|FLAC|OPUS|MP3|TRUEHD|ATMOS)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMODE = Regex(
        """\b(DUAL|DSUB|JPN(?:\.DUB)?|ENG(?:\.DUB)?|JP)?\s*(AUDIO|CHANS?|CH)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CODEC = Regex(
        """\b(HEVC|AVC|H\.?264|H\.?265|AV1|VP9|X\.?264|X\.?265|10-?BITS?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val HDR = Regex(
        """\b(HDR10\+?|DOLBY(?:\.?VISION)?|DV)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SOURCE_CLASS = Regex(
        """\b(WEB-?DL|WEBDL|WEB-?RIP|BLURAY|BDRIP|HDTV|DVD-?RIP|REMUX)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SVC_BRACKET = Regex(
        """\[(NF|AMZN|CR|DSNP|HULU|AT-X|Netflix|Amazon|Crunchyroll|Disney\+|Hulu|AT-X)]""",
        RegexOption.IGNORE_CASE,
    )

    private val ALLOWED_PREFIX_QUALITY: Set<String> = setOf(
        "4k", "2k", "2160p", "1080p", "720p", "480p", "1440p",
    )

    /**
     * Bounded technical / language / source-service / resolution /
     * codec / HDR / audio / source-class deny-list. Tokens in any
     * of these classes must never be promoted to a release group.
     * This is a persistence-owned mirror of the visual classifier's
     * policy; it is intentionally independent of
     * `SourceRowClassifier` so the persistence layer does not
     * depend on UI presentation code.
     */
    private val KNOWN_NON_GROUP_BRACKETS: Set<String> = setOf(
        "HDR", "HEVC", "AVC", "H264", "H265", "X264", "X265",
        "AV1", "VP9", "XVID", "DIVX", "H.264", "H.265",
        "10BIT", "8BIT", "10-BIT", "8-BIT", "10BITS", "10 BITS",
        "1080P", "720P", "2160P", "480P", "1440P", "4K", "2K",
        "AAC", "AC3", "DTS", "FLAC", "OPUS", "MP3", "EAC3", "DDP", "DDP2",
        "DDP5", "ATMOS", "TRUEHD", "MKA", "M4A", "DDP2.0", "DDP5.1", "AAC2.0", "AAC5.1",
        "BATCH", "DUAL AUDIO", "MULTI AUDIO", "COMPLETE", "UNCENSORED",
        "WEB-DL", "WEBDL", "WEBRIP", "BLURAY", "BDRIP", "HDTV", "DVDRIP", "REMUX",
        "AMZN", "NF", "CR", "DSNP", "HULU", "AT-X", "ATX", "WEB.10", "10BITS",
        "MULTISUBS", "MULTI SUBS", "MULTIPLE SUBTITLE", "MULTIPLE SUBTITLES",
        "MULTIPLE AUDIO", "HOURS", "2 HOURS SPECIAL", "SPECIAL",
        "1080P HEVC", "1080P AVC", "1080P X265", "1080P X264",
    )

    private val KNOWN_LANG_CODES: Set<String> = setOf(
        "AR", "DE", "EN", "ES", "FR", "IT", "JA", "JP", "KO", "PT", "RU", "ZH",
        "BS", "BG", "CA", "CS", "DA", "EL", "EO", "ET", "FA", "FI", "HE", "HI",
        "HR", "HU", "ID", "IS", "KK", "KM", "LT", "LV", "MK", "ML", "MN",
        "MR", "MS", "MY", "NL", "NO", "PL", "RO", "SH", "SI", "SK", "SL", "SR",
        "SV", "TA", "TE", "TH", "TL", "TR", "UK", "UR", "VI", "YI", "YO",
        "ARA", "BAQ", "BUL", "CAT", "CHI", "CZE", "DAN", "DUT", "ELL", "ENG",
        "EST", "FIN", "FRE", "GER", "GRE", "HEB", "HIN", "HRV", "HUN", "ICE",
        "IND", "ITA", "JPN", "KAN", "KOR", "LAV", "LIT", "MAC", "MAL", "MAR",
        "MAY", "MKD", "MNG", "NOR", "PER", "POL", "POR", "RUM", "RUS", "SCC",
        "SCR", "SIN", "SLO", "SLV", "SPA", "SRP", "SWE", "TAM", "TEL", "THA",
        "TIR", "TUR", "UKR", "URD", "VIE", "YID", "YOR", "ZHO",
        "CHT", "CHS", "PT-BR", "ZH-CN", "ZH-TW", "ZH-HK",
    )

    private val SOURCE_SERVICE_ALIASES: Set<String> = setOf(
        "AMZN", "NF", "CR", "DSNP", "HULU", "AT-X", "ATX",
    )

    private val KNOWN_TRACKER_BARE = setOf(
        "nyaasi", "1337x", "nekobt", "hon3yhoneyp",
    )

    fun extract(rawDescription: String?): StableReleaseMetadata {
        val raw = rawDescription?.trim() ?: ""
        val tracker = TRACKER_FOLD.find(raw)?.groupValues?.get(1)?.trim()
        val resolution = pickResolution(raw)
        val afmt = AFMT.find(raw)?.groupValues?.get(1)
        val amode = AMODE.find(raw)?.groupValues?.get(0)
        val svc = SVC_BRACKET.find(raw)?.groupValues?.get(1)
        val codec = CODEC.find(raw)?.groupValues?.get(1)
        val hdr = HDR.find(raw)?.groupValues?.get(1)
        val srcClass = SOURCE_CLASS.containsMatchIn(raw)
        val group = pickGroup(raw, tracker)
        val isRelease = group != null
        return StableReleaseMetadata(
            rawTitle = raw,
            group = group,
            tracker = tracker,
            resolution = resolution,
            audioFormat = afmt,
            audioMode = amode,
            sourceService = svc,
            codec = codec,
            hdr = hdr,
            isReleaseShaped = isRelease,
        )
    }

    /**
     * Pick the FIRST bracketed token that:
     *   - is a valid group candidate (deny-list check), AND
     *   - its prefix matches the bounded group-position rule
     *     (empty / single-quality / tracker-corroborated).
     *
     * Returns null when no such token is found, in which case
     * `isReleaseShaped` is also false.
     */
    private fun pickGroup(raw: String, parsedTracker: String?): String? {
        // Strip a leading "📂 <tracker> |" prefix from the candidate
        // text before applying the group-position rule. The visual
        // classifier operates on the cleaned title (without the
        // tracker line); we mirror that here by isolating the title
        // portion.
        val titlePortion = raw
            .replace(Regex("""📂\s+[^\n|]+\|"""), "")
            .trimStart()
        for (m in BRACKETED.findAll(titlePortion)) {
            val token = m.groupValues[1].trim()
            if (!isGroupCandidate(token)) continue
            val prefix = m.range.first.let { idx ->
                titlePortion.substring(0, idx)
                    .substringAfterLast('\n')
                    .trim()
            }
            if (!isGroupPositionPrefix(prefix, parsedTracker)) continue
            return token
        }
        return null
    }

    /**
     * Group-position prefix rule. Mirrors
     * `SourceRowClassifier.isGroupPositionPrefix` and is
     * intentionally independent of that class.
     *
     * The prefix may be:
     *   - empty
     *   - a single quality token
     *   - a single tracker-shaped token equal to the parsed tracker
     *   - `[quality] [tracker]` or `[tracker] [quality]` where the
     *     tracker equals the parsed tracker
     *
     * When the parsed tracker is null, only empty and single-quality
     * prefixes are accepted. This rules out `Other 1080p [Ironclad]
     * Title` while still accepting `[SubsPlease] Title`, `1080p
     * [SubsPlease] Title`, and the explicit-tracker cases.
     */
    private fun isGroupPositionPrefix(
        prefix: String,
        parsedTracker: String?,
    ): Boolean {
        if (prefix.isEmpty()) return true
        val tokens = prefix.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        if (tokens.size > 2) return false

        val firstIsQuality = tokens[0].lowercase(Locale.ROOT) in ALLOWED_PREFIX_QUALITY
        val firstIsTrackerShape = isTrackerLikeToken(tokens[0])
        val secondIsQuality = tokens.size == 2 &&
            tokens[1].lowercase(Locale.ROOT) in ALLOWED_PREFIX_QUALITY
        val secondIsTrackerShape = tokens.size == 2 &&
            isTrackerLikeToken(tokens[1])

        if (parsedTracker == null) {
            if (tokens.size == 1 && firstIsQuality) return true
            return false
        }

        if (tokens.size == 2) {
            if (firstIsQuality && secondIsTrackerShape &&
                corroboratesTracker(tokens[1], parsedTracker)) return true
            if (firstIsTrackerShape && secondIsQuality &&
                corroboratesTracker(tokens[0], parsedTracker)) return true
            return false
        }
        if (tokens.size == 1) {
            if (corroboratesTracker(tokens[0], parsedTracker)) return true
            if (firstIsQuality) return true
            return false
        }
        return false
    }

    /**
     * Tracker corroboration through canonical tracker keys, so the
     * same tracker spelled with different case still corroborates
     * (e.g. prefix "nyaasi" matches parsed tracker "NyaaSi").
     * Arbitrary non-tracker tokens are NOT broadened: both sides
     * must canonicalize to a non-null key and be equal.
     */
    private fun corroboratesTracker(
        prefixToken: String,
        parsedTracker: String,
    ): Boolean {
        val a = TrackerCanonicalizer.canonicalKey(prefixToken) ?: return false
        val b = TrackerCanonicalizer.canonicalKey(parsedTracker) ?: return false
        return a == b
    }

    private fun isTrackerLikeToken(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.any { it == '.' || it == '/' || it == '\\' || it == ':' }) return false
        return token.all { it.isLetterOrDigit() }
    }

    private fun isGroupCandidate(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.length > 20) return false
        if (s.all { it.isDigit() }) return false
        val upper = s.uppercase(Locale.ROOT)
        if (upper in KNOWN_NON_GROUP_BRACKETS) return false
        if (SOURCE_SERVICE_ALIASES.contains(upper)) return false
        if (KNOWN_LANG_CODES.any { it.equals(upper, ignoreCase = true) }) {
            return false
        }
        return true
    }

    private fun pickResolution(raw: String): String? {
        RES_PX.find(raw)?.groupValues?.get(1)?.let { return it }
        RES_WXH.find(raw)?.groupValues?.get(1)?.let { return it }
        RES_4K.find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { return it }
        return null
    }
}
