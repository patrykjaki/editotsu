package ani.dantotsu.media.anime.selector

/**
 * Pure functions that classify a candidate's quality text + URL + format into
 * presentation kind, transport, and (for RELEASE rows) parsed field values.
 *
 * No Android dependencies. No I/O. No persistence writes. No class state.
 *
 * All methods are stateless and pure. They are the single source of truth
 * for how the picker translates raw extension output into picker UI fields.
 *
 * v3.1 rules (per `source_picker_visual_v3_3_feedback.md` and
 * `source_picker_v3_identity_v6_reviewer_decision.md`):
 *
 *   - Release group is the FIRST valid bracketed group token in the release
 *     title, NOT the value next to the cog emoji (`⚙`/📂). A quality prefix
 *     (e.g. `4k`, `1080p`, `2160p`) is allowed before the bracket.
 *   - Tracker/provenance is the value next to the `📂` emoji. It is NOT
 *     the release group and never appears in the card header.
 *   - Subtitle vs audio flag separation: only flags with line-local
 *     context (Sub/Subs/Multi-Subs for subtitles; Audio/Dual Audio/Dub for
 *     audio) become classified flags. Uncontextual flags stay unclassified.
 *   - NF / AMZN / CR mean Netflix / Amazon / Crunchyroll source service
 *     and are low-priority metadata, NOT top-row chips.
 *   - `2160p` displays as `4K` in the collapsed card; details show
 *     `4K (2160p)`. Internal value stays `2160p`.
 */
object SourceRowClassifier {

    // ---- Marker / token sets ---------------------------------------------

    private val MARKER_EMOJIS: Set<String> = setOf(
        "📂", "⚙", "💾", "👤", "🎯", "🎬", "🔗",
    )

    /**
     * Bracket tokens that must NEVER be treated as the release group. These
     * are codec/quality/HDR/audio/source-service/etc. markers.
     */
    private val KNOWN_NON_GROUP_BRACKETS: Set<String> = setOf(
        "HDR", "HEVC", "AVC", "H264", "H265", "X264", "X265",
        "AV1", "VP9", "XVID", "DIVX", "H.264", "H.265",
        "10BIT", "8BIT", "10-BIT", "8-BIT", "10BITS", "10 BITS",
        "1080P", "720P", "2160P", "480P", "1440P", "4K", "2K",
        "AAC", "AC3", "DTS", "FLAC", "OPUS", "MP3", "EAC3", "DDP", "DDP2",
        "DDP5", "DD", "ATMOS", "TRUEHD", "MKA", "M4A", "DDP2.0", "DDP5.1", "AAC2.0", "AAC5.1",
        "BATCH", "DUAL AUDIO", "MULTI AUDIO", "COMPLETE", "UNCENSORED",
        "HD", "SD", "HDMA", "HRA",
        "WEB-DL", "WEBDL", "WEBRIP", "BLURAY", "BDRIP", "HDTV", "DVDRIP", "REMUX",
        "AMZN", "NF", "CR", "DSNP", "HULU", "AT-X", "ATX", "WEB.10", "10BITS",
        "MULTISUBS", "MULTI SUBS", "MULTIPLE SUBTITLE", "MULTIPLE SUBTITLES",
        "MULTIPLE AUDIO", "HOURS", "2 HOURS SPECIAL", "SPECIAL",
        "COMPLETA", "COMPLETO", "COMPLET",
        "HDDVD", "PDTV", "AHDTV", "APDTV", "DSR", "ADSR", "MBLURAY",
        "WEB", "TELECINE", "WORKPRINT", "DCP", "MP2", "LPCM", "SDR",
        "REAL", "READNFO", "FINAL", "REMASTERED", "RESTORED",
        "THEATRICAL", "EXTENDED", "UNCUT", "HR",
        "1080P HEVC", "1080P AVC", "1080P X265", "1080P X264",
    )

    /**
     * 2- and 3-letter language codes (ISO 639-1 + 639-2). Used to reject
     * 2-3 letter bracketed tokens as the release group.
     */
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

    /** Source service aliases that must NOT become release-group tokens. */
    private val SOURCE_SERVICE_ALIASES: Map<String, String> = mapOf(
        "AMZN" to "Amazon",
        "NF" to "Netflix",
        "CR" to "Crunchyroll",
        "DSNP" to "Disney+",
        "HULU" to "Hulu",
        "AT-X" to "AT-X",
        "ATX" to "AT-X",
        "ATVP" to "Apple TV+",
    )

    // ---- Regexes ----------------------------------------------------------

    private val REGEX_RELEASE_FILENAME =
        Regex("""\[.+?]\s*.+?\.(mkv|mp4|avi|webm|ts|m4v|m2ts|mov|flv|rmvb)$""", RegexOption.IGNORE_CASE)

    private val REGEX_RESOLUTION = Regex("""\b(\d{3,4})p\b""")
    private val REGEX_RESOLUTION_WXH =
        Regex("""\b(\d{3,4})\s*[x×]\s*(\d{3,4})\b""")

    private val REGEX_INDEXER = Regex("""📂\s*([^|\n]+)""")

    /**
     * Strict cog value: the literal `⚙️` marker is required, pipe- and
     * newline-terminated. Replaces the old optional-marker cog line for
     * both tracker derivation and the legacy-⚙ group step (the optional
     * form could match an arbitrary line start and is retired).
     */
    private val REGEX_COG_VALUE = Regex("""⚙️\s*([^|\n]+)""")

    private val REGEX_SIZE = Regex("""💾\s*([\d.]+\s*[KMGT]?B)""", RegexOption.IGNORE_CASE)

    private val REGEX_SEEDERS = Regex("""👤\s*(\d+)""")

    private val REGEX_AUDIO = Regex("""🎬\s*([^\n(]+?)(?:\s*\(|\n|${'$'})""", RegexOption.MULTILINE)

    private val REGEX_HDR = Regex("""(?i)\b(HDR10\+|HDR10|HDR|DOLBY\s*VISION|DV)\b""")
    private val REGEX_HDR_SIMPLE = Regex("""(?i)[\[(]HDR[)\]]""")
    private val REGEX_HDR_PLUS = Regex("""(?i)[\[(]HDR10\+[)\]]""")
    private val REGEX_HDR10 = Regex("""(?i)[\[(]HDR10[)\]]""")
    private val REGEX_DV = Regex("""(?i)[\[(](DOLBY\s*VISION|DV)[)\]]""")
    private val REGEX_HI10P = Regex("""(?i)[\[(]HI10P[)\]]""")

    /**
     * Bare/dot-delimited HDR grammar (standards-gap fix v1, TRaSH
     * cross-check): `EAC3.Atmos.5.1.DV.HDR10Plus.h265` must resolve the
     * same variants as the bracketed forms. Delimiters are start/end,
     * whitespace, dots, hyphens, underscores and brackets. The
     * trailing lookahead (never a letter/digit/`+`) keeps substrings
     * inside ordinary words false (`ADV`, `DVDRip`, `HDRip`).
     */
    private val HDR_DELIM = """(?:^|[\s.\-_\[(])"""
    private val HDR_END = """(?=$|[\s.\-_\])])"""
    private val REGEX_HDR10P_BARE = Regex(HDR_DELIM + """HDR10\+""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_HDR10PLUS_BARE =
        Regex(HDR_DELIM + """HDR10PLUS""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_HDR10_BARE =
        Regex(HDR_DELIM + """HDR10(?!\+)""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_DV_BARE =
        Regex(HDR_DELIM + """DV""" + HDR_END, RegexOption.IGNORE_CASE)
    private val REGEX_DOLBY_VISION_BARE = Regex(
        HDR_DELIM + """DOLBY[\s.\-_]*VISION""" + HDR_END,
        RegexOption.IGNORE_CASE,
    )
    private val REGEX_HDR_BARE =
        Regex(HDR_DELIM + """HDR(?![\d+])""" + HDR_END, RegexOption.IGNORE_CASE)

    private val REGEX_BIT_DEPTH = Regex("""(?i)\b(10[- ]?BITS?|10[- ]?BIT|8[- ]?BITS?|8[- ]?BIT|HI10P)\b""")

    private val REGEX_CODEC_BRACKETED =
        Regex("""[\[(](HEVC\s*10[- ]?BIT|HEVC|H\.?264|H\.?265|AVC|AV1|VP9|X\.?264|X\.?265|XVID|DIVX)[\])]""",
            RegexOption.IGNORE_CASE)
    private val REGEX_CODEC_BARE =
        Regex("""(?i)\b(x\.?264|x\.?265|hevc|h\.?264|h\.?265|avc|av1|vp9|xvid|divx)\b""")

    // `DD+` lives outside the trailing `\b` (standards-gap fix v1): a
    // `\b` after `+` can never match, so bare `DD+` uses a lookahead
    // end instead (end of string, separators, or a media-extension dot
    // such as `DD+.mkv`). Plain `PCM` is a first-class format like `LPCM`.
    // Dolby is explicit full-phrase families only (v2.2 Fix C): the old
    // generic `DOLBY...` catch-all mislabeled `Dolby Vision` as audio
    // and reduced `Dolby Digital Plus 2.0` to `DOLBY DIGITAL`. Bare
    // `Dolby` alone matches nothing (Vision/Atmos/Digital?).
    // The DTS tail repeats a bounded `separator + known token` group
    // (standards-gap v2.1 correction): `DTS-HD MA 5.1` keeps `MA` and
    // channels, while `DTS-HD.X265-IAHD` still stops before codec/group
    // text. The lone `X` token carries an end-guard (same lesson as
    // `DD+`: a bare `\b`-style boundary fails at dots, so extension
    // dots like `DTS:X.mkv` are explicitly allowed while `.X265`
    // is not).
    private val AUDIO_FORMAT_REGEX = Regex(
        """(?i)\b(DDP(?:[\s._\-]*\d+\.?\d*)?|DTS((?:[\s\-.:]+(?:HDMA|HRA|HD|MA|HR|X(?=$|[\s\-_\])]|\.[a-zA-Z]{2,4}($|[\s\]]))|ES|EX|TRUEHD|ATMOS|\d\.\d))+)?|DOLBY\s+DIGITAL\s+PLUS(?:\s+\d\.\d)?|DOLBY\s+DIGITAL(?!\s*PLUS)(?:\s+\d\.\d)?|DOLBY\s+TRUEHD(?:\s+\d\.\d)?|E-?A-?C-?3(?:[\s._\-]*\d+\.?\d*)?|A-?C-?3(?:[\s._\-]*\d+\.?\d*)?|FLAC(?:[\s._\-]*\d+\.?\d*)?|AAC(?:[\s._\-]*\d+\.?\d*)?|OPUS(?:[\s._\-]*\d+\.?\d*)?|MP3(?:[\s._\-]*\d+\.?\d*)?|MP2|LPCM|PCM(?:[\s._\-]*\d+\.?\d*)?|DD\+[\s._\-]*\d+\.?\d*|DD[\s._\-]*\d+\.?\d*|DD(?![\w+])|TRUEHD(?:[\s._\-]*\d+\.?\d*)?|ATMOS)\b|\bDD\+(?=$|[\s\-_\])]|\.[a-zA-Z]{2,4}$)""",
    )

    private val REGEX_LANGUAGE_TAG =
        Regex("""\[\s*((?:[A-Za-z]{2,3})(?:[-+](?:[A-Za-z0-9]{2,4}))*)\s*]""")

    // Allowed bounded technical prefixes before the first release-group
    // bracket. Anything else stops the prefix scan.
    private val ALLOWED_PREFIX_TOKENS = setOf(
        "4k", "2160p", "1080p", "720p", "480p", "1440p",
    )

    /**
     * Generalized quality-prefix token (provider-dataset v1): uncommon
     * heights such as 800p/528p/540p/360p occur in real provider rows
     * (`800p [IceBlue]`, `528p [EvilMini]`).
     */
    private val REGEX_QUALITY_PREFIX_TOKEN =
        Regex("""(?i)^(\d{3,4}p|4k|2k|8k)$""")

    private fun isQualityPrefixToken(token: String): Boolean {
        if (token.lowercase() in ALLOWED_PREFIX_TOKENS) return true
        return REGEX_QUALITY_PREFIX_TOKEN.matches(token)
    }

    // Subtitle context markers. Hyphen-joined (`Multi-Subs`) and
    // unseparated (`Multisubs`) forms count as subtitle context, same
    // as the spaced form. Provider-dataset v1 adds the French
    // (`vostfr`), Polish (`napisy`), Portuguese (`legendado`) and
    // Spanish (`subtitulado`) scene markers so flags on those lines
    // classify as subtitle flags.
    private val SUB_CONTEXT_REGEX = Regex(
        """(?i)\b(subs?|subtitles?|multi[\s\-]*subs?|multiple\s*subtitles?|vostfr?|napisy|legendad[oa]s?|subtitulad[oa]s?)\b""",
    )

    // Audio context markers. The 🎬 emoji is the Torrentio audio-line
    // marker and indicates an audio context. Provider-dataset v1 adds
    // the French (`french`), Spanish (`castellano`, `latino`) and
    // Portuguese (`dublado`) scene markers so flags on those lines
    // classify as audio flags.
    private val AUDIO_CONTEXT_REGEX = Regex(
        """(?i)(\b(audio|dual\s*audio|multi\s*audio|multidubs?|dub|dubbed|french|castellano|latino|dublado?s?)\b|🎬)""",
    )

    // Bare dub/sub track-kind markers in the release title. These fire
    // only when no higher-confidence explicit signal (🎬 audio line /
    // subtitle flags / language codes) already populated the field, and
    // they are matched against the release title only, never against
    // the provider/tracker name.
    private val REGEX_DUAL_WORD = Regex("""(?i)\bdual\b""")
    private val REGEX_DUBBED_WORD = Regex("""(?i)\bdubbed\b""")
    private val REGEX_ENG_DUB =
        Regex("""(?i)\beng(?:lish)?[\s._\-]*dubs?\b""")
    private val REGEX_FUNI_DUB = Regex("""(?i)\bfuni(?:mation)?[\s._\-]*dubs?\b""")
    private val REGEX_MULTI_SUBS_WORD =
        Regex("""(?i)\bmulti[\s\-]*subs?\b|\bmultiple\s*subtitles?\b""")

    /**
     * Explicit dual-audio separators (standards-gap fix v1, TRaSH
     * Anime Dual Audio grammar): `Dual Audio`, `Dual-Audio`,
     * `Dual_Audio`, `Dual.Audio`. Underscore is a word character, so
     * the plain `\bdual\b` word rule misses `Dual_Audio`; this form
     * does not.
     */
    private val REGEX_DUAL_AUDIO_SEP =
        Regex("""(?i)\bdual[\s._\-]+audio\b""")

    /**
     * Dual-audio language pairs (standards-gap fix v1): `JA+EN`,
     * `EN+JA`, `Japanese + English` and reverse forms with common
     * `. _ + & -` separators. At least one side is English; bare
     * space-only adjacency (`JA EN`) is excluded as too ambiguous.
     * Group/provider names alone never trigger this (no pair shape).
     */
    private val REGEX_LANG_PAIR_DUAL = Regex(
        """(?i)\b((ja|jpn?|zh|chi|ko|kor)\s*[.+&\-]\s*(en|eng)|(en|eng)\s*[.+&\-]\s*(ja|jpn?|zh|chi|ko|kor))\b""",
    )
    private val REGEX_LANG_PAIR_WORDS_DUAL = Regex(
        """(?i)\b((japanese|chinese|korean)(?:\s*[.+&\-_]\s*|\s+)(english)|(english)(?:\s*[.+&\-_]\s*|\s+)(japanese|chinese|korean))\b""",
    )

    /**
     * Provider-dataset v1 dub vocabulary (French / Spanish / Portuguese
     * scenes). All word-boundary matched against the release title
     * only, never the provider/tracker name (`HorribleSubs`,
     * `MicoLeaoDublado` do not match: no boundary inside the word).
     */
    private val REGEX_FRENCH_DUB = Regex("""(?i)\bfrench\b""")
    private val REGEX_CASTELLANO_DUB = Regex("""(?i)\bcastellano\b""")
    private val REGEX_LATINO_DUB = Regex("""(?i)\blatino\b""")
    private val REGEX_DUBLADO_DUB = Regex("""(?i)\bdublado?s?\b""")
    private val REGEX_MULTIDUB = Regex("""(?i)\bmultidubs?\b""")
    private val REGEX_ENGLISH_AUDIO_DUB = Regex("""(?i)\benglish\s+audio\b""")
    private val REGEX_SLASHED_AUDIO_DUAL =
        Regex("""(?i)\b[a-z]{2,4}(/[a-z]{2,4})+\s+audio\b""")

    /**
     * Provider-dataset v1 subtitle vocabulary. Bare `sub(s)` /
     * `subbed` / `subtitled` / `napisy` (PL) / `legendado` (PT) /
     * `subtitulado` (ES) markers map to the existing `Multi-subs`
     * fallback string; `VOSTFR` keeps its precise scene label.
     */
    private val REGEX_VOSTFR = Regex("""(?i)\bvostfr?\b""")
    private val REGEX_SUBS_WORD =
        Regex("""(?i)\bsubtitles?\b|\bsubs?\b|\bsubbed\b""")
    private val REGEX_NAPISY = Regex("""(?i)\bnapisy\b""")
    private val REGEX_LEGENDADO = Regex("""(?i)\blegendad[oa]s?\b""")
    private val REGEX_SUBTITULADO =
        Regex("""(?i)\bsubtitulad[oa]s?\b|\bsubtitulos?\b""")

    /**
     * Neutral language words: `espanol` and the trash-guides full
     * language names resolve through nearby context (sub words ->
     * subs, dub words -> dub), otherwise null. `japanese` is
     * deliberately excluded: for anime it is the original language,
     * not a dub signal.
     */
    private val REGEX_ESPANOL = Regex("""(?i)\bespa[nñ]ol\b""")
    private val REGEX_FULL_LANGUAGE = Regex(
        """(?i)\b(german|italian|russian|portuguese|polish|dutch|korean|chinese|arabic|hindi|turkish)\b""",
    )
    private val REGEX_SUB_CONTEXT_WORD =
        Regex("""(?i)subtitles?|subs?|subbed|vostfr?|napisy|legendad|subtitulad""")
    private val REGEX_DUB_CONTEXT_WORD =
        Regex("""(?i)dubbed|dub\b|dual|audio|doblaje|dublado|castellano|latino""")

    /**
     * Bracket tokens led by an audio/sub marker are content signals,
     * not release groups (e.g. `[DUAL AAC2.0]`, `[Multi-Subs]`, `[Dub]`).
     * Whole-token technical forms (`DUAL AUDIO`, `MULTI SUBS`) are
     * already covered by [KNOWN_NON_GROUP_BRACKETS]; the patterns below
     * cover the hyphen/space/none-joined variants that the fixed list
     * cannot enumerate.
     *
     * Real groups survive: the audio continuation set is bounded, so
     * `Dubs-Empire`, `SubsPlease`, `DualAudio`, `Subzero`, `Multicast`
     * and `Dubious` do not match.
     */
    private val REGEX_AUDIO_SUB_WHOLE_BRACKET = Regex(
        """(?i)^(dual|dubs?|dubbed|subs?|subbed|subtitles?|multisubs?|multi-subs|dual-audio)$""",
    )
    private val REGEX_AUDIO_SUB_LED_BRACKET = Regex(
        """(?i)^(dual|dubbed|dub|multi)\b[\s\-]+(audio|aac[\d.]*|ac-?3[\d.]*|dts[\w\-]*|flac[\d.]*|opus[\d.]*|mp3|e-?ac-?3[\d.]*|ddp[\d.]*|truehd|atmos|subs?|subbed|subtitles?)\b""",
    )

    /**
     * Bracket tokens starting with an audio-format word are technical
     * metadata, never groups (`[AC3 5.1 Castellano]`, `[AAC 2.0]`,
     * `[LPCM]`, `[DTS-HD MA]`).
     * Whole-token forms are already denied; this covers the spaced /
     * channeled variants.
     */
    private val REGEX_AUDIO_FORMAT_LED_BRACKET = Regex(
        """(?i)^(aac|ac-?3|dts(\s*hd)?|dts-?hd|dts-?x|e-?ac-?3|ddp|dd\+?|flac|opus|mp3|mp2|pcm|lpcm|truehd|atmos)\b""",
    )

    /**
     * Season / episode / volume / version-shaped bracket tokens are
     * structural markers, never release groups (provider-dataset v1 +
     * trash-guides/scenerules cross-check: `[S01]`, `[S01-04 + OVA]`,
     * `[E01]`, `[S01E01]`, `[S01E01-E02]`, `[S02E01A]`, `[0001-0130]`,
     * `[Cap.101]`, `[01x01]`, `[Part 1]`, `[2020.01.15]`, `[OVA 01]`,
     * `[Specials]`, `[v2]`).
     */
    private val REGEX_SEASON_EPISODE_BRACKET = Regex(
        """(?i)^(?:s\d{1,4}([\-+]\d{1,4})?(\s*\+\s*[a-z]+)?|e\d{1,4}|s\d{1,2}e\d{1,3}([\-+]e?\d{1,3})?[a-z]?|ep?\d{1,4}|cap\.?\d+([_\-]\d+)?|\d{1,4}[x×]\d{1,4}|\d{1,4}[\-+~]\d{1,4}|part[\s.\-]*\d{1,3}|\d{4}\.\d{1,2}\.\d{1,2}|ova(\s+\d{1,4})?|oad(\s+\d{1,4})?|specials|special\s+\d{1,4}|v\d+[a-z]?)$""",
    )

    /**
     * URL-shaped bracket tokens are provenance links, never release
     * groups (`[www.descargas2020.org]`).
     */
    private val REGEX_URL_LIKE_BRACKET = Regex(
        """(?i)(?:^www\.|\.(org|com|net|info|tv|io|me|to|cc|ru|pl|es|fr|it|de|nl|se)$)""",
    )

    // Suffix-style release group: a trailing `-GROUP` on the filename
    // (nyaa-style `...-VARYG`, `...-Rapta`) when no authoritative
    // `[Group]` tag exists. The media extension is stripped first so
    // `Title-VARYG.mkv` still resolves.
    private val REGEX_STRIP_MEDIA_EXTENSION =
        Regex("""\.(mkv|mp4|avi|webm|ts|m4v|m2ts|mov|flv|rmvb)$""", RegexOption.IGNORE_CASE)
    private val REGEX_TRAILING_SUFFIX_GROUP =
        Regex("""-([A-Za-z][A-Za-z0-9]{1,11})\s*$""")

    /**
     * Tokens that must never be promoted from a `-SUFFIX` position to
     * a release group: codec / bit-depth / audio / resolution-ish /
     * source-class / tag / container tokens. Compared uppercase.
     */
    private val SUFFIX_GROUP_DENY: Set<String> = setOf(
        "X264", "X265", "H264", "H265", "HEVC", "AVC", "AV1", "VP9",
        "XVID", "DIVX",
        "8BIT", "10BIT", "HI10P",
        "AAC", "AC3", "DTS", "EAC3", "DDP", "DD", "FLAC", "OPUS", "MP3",
        "TRUEHD", "ATMOS", "MKA", "M4A",
        "DUAL", "DUB", "DUBS", "DUBBED", "SUB", "SUBS", "SUBBED",
        "AUDIO",
        "2160P", "1080P", "720P", "480P", "1440P", "4K", "2K", "8K",
        "UHD", "FHD", "HD", "SD",
        "HDMA", "HRA",
        "WEBDL", "WEBRIP", "BLURAY", "BDRIP", "HDTV", "DVDRIP",
        "REMUX", "COMPLETE", "COMPLETA", "COMPLETO", "COMPLET",
        "BATCH", "UNCENSORED",
        "REPACK", "PROPER", "RERIP", "RATED", "UNRATED", "LIMITED",
        "INTERNAL", "RETAIL", "REAL", "READNFO", "FINAL", "REMASTERED",
        "RESTORED", "THEATRICAL", "EXTENDED", "UNCUT",
        "HDDVD", "PDTV", "AHDTV", "APDTV", "DSR", "ADSR", "MBLURAY",
        "WEB", "TELECINE", "WORKPRINT", "DCP", "MP2", "LPCM", "SDR", "HR",
        "CAM", "SCR", "TC", "R5", "TELESYNC", "HDCAM", "HDTS",
        "DVDSCR", "SCREENER", "HQ", "NFO",
        "MKV", "MP4", "AVI", "WEBM", "TS", "M4V", "M2TS", "MOV",
        "FLV", "RMVB",
    )

    // ---- Helpers ----------------------------------------------------------

    private fun isLikelyLanguageTag(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.contains(' ')) return false
        if (KNOWN_NON_GROUP_BRACKETS.any { it.equals(token, ignoreCase = true) }) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(token.uppercase())) return false
        // Plain 2-3 letter tokens (e.g. "ENG", "JPN", "POR") need to be in
        // the known-codes list.
        if (!token.contains('-') && !token.contains('+')) {
            if (token.length > 4) return false
            if (!KNOWN_LANG_CODES.any { it.equals(token, ignoreCase = true) }) return false
            return token.all { it.isLetter() }
        }
        // Hyphenated regional codes (e.g. ESP-ENG, POR-BR, EN-US): each
        // segment must be 2-3 letters, all alphabetic.
        val segments = token.split('-', '+')
        for (s in segments) {
            if (s.length !in 2..3) return false
            if (!s.all { it.isLetter() }) return false
        }
        return true
    }

    private fun isGroupCandidate(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.length > 20) return false
        if (KNOWN_NON_GROUP_BRACKETS.any { it.equals(token, ignoreCase = true) }) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(token.uppercase())) return false
        if (isLikelyLanguageTag(token)) return false
        // Audio/sub-led bracket tokens are content signals, never
        // groups (follow-ups v1, refined by provider-dataset v1 so real
        // hyphenated groups such as Dubs-Empire survive).
        if (REGEX_AUDIO_SUB_WHOLE_BRACKET.matches(token)) return false
        if (REGEX_AUDIO_SUB_LED_BRACKET.containsMatchIn(token)) return false
        if (REGEX_AUDIO_FORMAT_LED_BRACKET.containsMatchIn(token)) return false
        // Season / episode / version / URL-shaped brackets are
        // structural markers, never groups.
        if (REGEX_SEASON_EPISODE_BRACKET.matches(token)) return false
        if (REGEX_URL_LIKE_BRACKET.containsMatchIn(token)) return false
        // A real release group is one or more word-chars (allows dashes for
        // groups like Tenrai-Sensei) but does not contain only digits.
        if (token.all { it.isDigit() }) return false
        return true
    }

    // ---- Presentation -----------------------------------------------------

    fun classifyPresentation(qualityText: String): SourcePresentationKind {
        val lines = qualityText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val hasMarker = lines.any { line -> MARKER_EMOJIS.any { line.contains(it) } }
        val hasReleaseFilename = lines.any { REGEX_RELEASE_FILENAME.containsMatchIn(it) }
        return if (hasMarker || hasReleaseFilename) SourcePresentationKind.RELEASE
        else SourcePresentationKind.STREAM
    }

    // ---- Transport ---------------------------------------------------------

    fun classifyTransport(url: String, formatIsContainer: Boolean, formatName: String): SourceTransport {
        if (url.startsWith("magnet:")) return SourceTransport.TORRENT
        val canonicalPath = canonicalUrlPath(url)
        if (canonicalPath.endsWith(".torrent", ignoreCase = true)) return SourceTransport.TORRENT
        when (formatName.uppercase()) {
            "M3U8" -> return SourceTransport.HLS
            "DASH" -> return SourceTransport.DASH
            "CONTAINER" -> {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return SourceTransport.DIRECT_HTTP
                }
                return SourceTransport.OTHER
            }
        }
        if (canonicalPath.endsWith(".m3u8", ignoreCase = true)) return SourceTransport.HLS
        if (canonicalPath.endsWith(".mpd", ignoreCase = true)) return SourceTransport.DASH
        return SourceTransport.OTHER
    }

    fun canonicalUrlPath(url: String): String {
        val noQuery = url.substringBefore('?')
        return noQuery.substringBefore('#')
    }

    // ---- Release-name extraction ----------------------------------------

    fun extractReleaseName(qualityText: String): String {
        val lines = qualityText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val titleLines = lines.filterNot { isMetadataMarkerLine(it) }
        return if (titleLines.isNotEmpty()) titleLines.joinToString(" ")
        else qualityText.trim()
    }

    private fun isMetadataMarkerLine(line: String): Boolean {
        if (line == "Torrentio") return true
        if (line.firstOrNull()?.let { MARKER_EMOJIS.any { e -> line.startsWith(e) } } == true) return true
        if (line.matches(Regex("""^(seeders?|size|indexer|provider|language|audio)\s*[:=].+""",
                RegexOption.IGNORE_CASE))) return true
        return false
    }

    // ---- Resolution parsing (UI only) -----------------------------------

    // Match an unambiguous "4k" / "4K" token that means 2160p. Only
    // matched when the surrounding context is a quality prefix (i.e. the
    // token is at the very start of the line, or surrounded by
    // whitespace, or follows a [brackets] before the title).
    private val REGEX_RESOLUTION_4K =
        Regex("""(?i)(?:^|\s|\])4k(?:\s|$)""")

    fun parseResolution(qualityText: String): String? {
        // Explicit 4k (2160p) is a recognised resolution marker in the
        // current Torrentio naming convention. Prefer it over later
        // bracketed technical-resolution metadata.
        if (REGEX_RESOLUTION_4K.containsMatchIn(qualityText)) return "2160p"
        REGEX_RESOLUTION.find(qualityText)?.value?.let { return it }
        val wxh = REGEX_RESOLUTION_WXH.find(qualityText) ?: return null
        val height = wxh.groupValues[2].toIntOrNull() ?: return null
        return "${height}p"
    }

    /**
     * Display alias for the collapsed card. 2160p -> "4K" per the v3.3
     * feedback. Internal value stays "2160p".
     */
    fun resolutionDisplayAlias(resolution: String?): String? {
        if (resolution == null) return null
        if (resolution == "2160p") return "4K"
        return resolution
    }

    // ---- Codec / bit-depth / HDR / DV normalisation ---------------------

    fun normaliseCodec(raw: String): String? {
        val t = raw.uppercase().replace(Regex("""[\s.]"""), "")
        return when {
            t.startsWith("X264") || t.startsWith("H264") -> "AVC"
            t.startsWith("X265") || t.startsWith("H265") || t == "HEVC" -> "HEVC"
            t == "AVC" -> "AVC"
            t == "AV1" -> "AV1"
            t == "VP9" -> "VP9"
            t == "XVID" -> "XVID"
            t == "DIVX" -> "DIVX"
            else -> null
        }
    }

    fun normaliseBitDepth(raw: String): String? {
        val t = raw.lowercase().replace(Regex("""[\s]"""), "")
        return when {
            t == "10-bit" || t == "10bit" || t == "10-bits" || t == "10bits" -> "10-bit"
            t == "8-bit" || t == "8bit" || t == "8-bits" || t == "8bits" -> "8-bit"
            t == "hi10p" -> "10-bit"
            else -> null
        }
    }

    fun normaliseHdr(raw: String): String? {
        val t = raw.lowercase().replace(Regex("""\s+"""), " ").trim()
        return when {
            t == "hdr10+" || t == "hdr10plus" -> "HDR10+"
            t == "hdr10" -> "HDR10"
            t == "hdr" -> "HDR"
            t == "dolby vision" || t == "dolbyvision" -> "Dolby Vision"
            else -> null
        }
    }

    // ---- Audio / language flag extraction -------------------------------

    /**
     * Unicode regional-indicator flag pair: two consecutive regional
     * indicator codepoints form a flag glyph. Each regional indicator is
     * U+1F1E6..U+1F1FF, encoded as a UTF-16 surrogate pair (high
     * U+D83C + low U+DDE6..DDFF). Manual code-point scanner because
     * Java/Kotlin regex on a lone high surrogate is unreliable across JVM
     * versions.
     */
    private fun extractFlags(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val cps = text.codePoints().toArray()
        var k = 0
        while (k < cps.size - 1) {
            val a = cps[k]
            val b = cps[k + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                out.add(String(Character.toChars(a)) + String(Character.toChars(b)))
                k += 2
            } else {
                k += 1
            }
        }
        return out.toList()
    }

    /**
     * Bracketed language codes [ENG], [POR-BR], [ESP-ENG] etc. Always
     * in the input string (the bracket syntax is the marker).
     */
    private fun extractBracketedLanguages(text: String): List<String> {
        val out = LinkedHashSet<String>()
        REGEX_LANGUAGE_TAG.findAll(text).forEach { match ->
            val raw = match.groupValues[1]
            raw.split("+").forEach { part ->
                if (isLikelyLanguageTag(part) ||
                    part.matches(Regex("""[A-Za-z]{2,3}-[A-Za-z0-9]{2,4}"""))
                ) {
                    out.add(part.uppercase())
                }
            }
        }
        return out.toList()
    }

    data class FlagSets(
        val subtitleFlags: List<String>,
        val audioFlags: List<String>,
        val subtitleLanguages: List<String>,
        val audioLanguages: List<String>,
    )

    /**
     * Extract flags and bracketed language codes with line-local context.
     * A flag or bracketed language only counts toward subtitle/audio if it
     * appears on a line with the matching context marker.
     */
    private fun extractFlagsAndLanguages(text: String): FlagSets {
        val subFlags = LinkedHashSet<String>()
        val audFlags = LinkedHashSet<String>()
        val subLangs = LinkedHashSet<String>()
        val audLangs = LinkedHashSet<String>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val isSubCtx = SUB_CONTEXT_REGEX.containsMatchIn(trimmed)
            val isAudCtx = AUDIO_CONTEXT_REGEX.containsMatchIn(trimmed) ||
                trimmed.startsWith("🎬")
            if (isSubCtx) {
                subFlags.addAll(extractFlags(trimmed))
                subLangs.addAll(extractBracketedLanguages(trimmed))
            }
            if (isAudCtx) {
                audFlags.addAll(extractFlags(trimmed))
                audLangs.addAll(extractBracketedLanguages(trimmed))
            }
        }
        return FlagSets(
            subtitleFlags = subFlags.toList(),
            audioFlags = audFlags.toList(),
            subtitleLanguages = subLangs.toList(),
            audioLanguages = audLangs.toList(),
        )
    }

    // ---- Source service detection (NF / AMZN / CR) ---------------------

    fun detectSourceService(releaseName: String): String? {
        // 1. Bracketed token in the title (the common form).
        val bracketToken = Regex("""\[([A-Za-z0-9\-]+)]""")
            .findAll(releaseName)
            .map { it.groupValues[1].uppercase() }
            .firstOrNull { SOURCE_SERVICE_ALIASES.containsKey(it) }
        if (bracketToken != null) return SOURCE_SERVICE_ALIASES[bracketToken]
        // 2. Standalone, clearly delimited alias derived from the
        //    authoritative visual map (standards-gap fix v1), so every
        //    high-confidence entry works — not just NF/AMZN/CR.
        //    Delimiters are start/end, whitespace or dots.
        val aliases = SOURCE_SERVICE_ALIASES.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val standalone = Regex(
            """(?:^|[\s.])(?:$aliases)(?:[\s.]|$)""",
            RegexOption.IGNORE_CASE,
        )
        standalone.find(releaseName)?.let { match ->
            val alias = match.value.trim().trim('.').uppercase()
            if (SOURCE_SERVICE_ALIASES.containsKey(alias)) {
                return SOURCE_SERVICE_ALIASES[alias]
            }
        }
        return null
    }

    // ---- Source class detection (WEB-DL / BluRay / etc.) -----------------

    fun detectSourceClass(releaseName: String): String? {
        val candidates = listOf(
            "WEB-DL", "WEBDL", "WEBRip", "BluRay", "BDRip", "REMUX",
            "HDTV", "DVDRip",
        )
        for (c in candidates) {
            if (releaseName.contains(c, ignoreCase = true)) return c.uppercase()
        }
        return null
    }

    // ---- Release-group extraction (v3.1 rule) -------------------------

    /**
     * Release-group rule (v2.2 current-format compatibility).
     *
     * The returned 784-row corpus contains zero `📂` rows: current
     * Torrentio/TorrentioAnime payloads carry the provider/indexer in
     * `⚙️`, so a lone `⚙️` value is provenance — never a group.
     *
     * Precedence (strongest first; a weaker guess never overwrites a
     * higher-confidence explicit tag):
     *   1. title bracket (preferred)
     *   2. legacy `⚙` group, ONLY in the dedicated-`📂` two-marker
     *      shape and only when the `⚙` value is distinct from the
     *      `📂` tracker
     *   3. trailing `-GROUP` filename suffix (weak guess, scanned over
     *      the raw `qualityText` title/file lines)
     *   4. null (correct when no group is present; never fabricate
     *      from a provider name)
     *
     * The `⚙` value and the title bracket can disagree in real Torrentio
     * output. Per the v3.3 feedback: prefer the title bracket; the `⚙`
     * value is corroborating only. If both are present and disagree,
     * return the title bracket and record the discrepancy.
     */
    fun extractReleaseGroup(qualityText: String, releaseName: String): String? {
        // Dedicated 📂 tracker (may be absent in current payloads).
        val dedicatedTracker = REGEX_INDEXER
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Effective tracker for prefix corroboration: 📂 wins, lone ⚙
        // is provenance.
        val effectiveTracker = dedicatedTracker ?: cogValue(qualityText)

        // 1. Title bracket (preferred) — but only if corroborated by the
        //    effective tracker (or no tracker at all).
        titleBracketGroup(releaseName, effectiveTracker)?.let { return it }

        // 2. Legacy cog group: only in the dedicated-📂 two-marker
        //    shape, and only when distinct from that tracker. The
        //    literal ⚙️ marker is required (strict match).
        if (dedicatedTracker != null) {
            cogValue(qualityText)?.let { cog ->
                if (!cog.equals(dedicatedTracker, ignoreCase = true) &&
                    isGroupCandidate(cog)
                ) {
                    return cog
                }
            }
        }

        // 3. Trailing `-GROUP` filename suffix (weak guess), scanned
        //    over the raw qualityText title/file lines (Fix B), so an
        //    explicit tag always wins over the suffix guess.
        trailingSuffixGroupFromText(qualityText)?.let { return it }

        // 4. Neither title bracket, legacy cog, nor suffix yielded a
        //    group. Tracker/provenance is never the group.
        return null
    }

    /**
     * Strict `⚙️` value (literal marker required), trimmed, or null.
     */
    private fun cogValue(qualityText: String): String? {
        return REGEX_COG_VALUE
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Effective tracker/provenance (v2.2 current-format compatibility):
     * the dedicated `📂` value when present, otherwise the lone `⚙`
     * provider value. Null when neither exists.
     */
    fun parseTracker(qualityText: String): String? {
        REGEX_INDEXER
            .find(qualityText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return cogValue(qualityText)
    }

    /**
     * Suffix-style group extraction over raw `qualityText` lines
     * (v2.2 Fix B).
     *
     * `extractReleaseName()` flattens title/file lines for display, so
     * scanning the flattened release name loses the filename line
     * whenever a non-marker language/audio line follows it
     * (`...-Psaro.mkv` + `Dubbed / Multi Audio`). This scans the
     * ORIGINAL line structure instead, skipping marker lines
     * (`👤`/`💾`/`⚙️`/`📂`/emoji-led and `Torrentio` header lines —
     * same filter as the display name), and returns the first valid
     * trailing `-GROUP`. Display `releaseName` behavior is unchanged.
     * Guards unchanged (fail conservative -> null).
     */
    private fun trailingSuffixGroupFromText(qualityText: String): String? {
        for (rawLine in qualityText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (isMetadataMarkerLine(line)) continue
            val noExt = REGEX_STRIP_MEDIA_EXTENSION.replace(line, "")
            val token = REGEX_TRAILING_SUFFIX_GROUP.find(noExt)
                ?.groupValues?.getOrNull(1) ?: continue
            if (!isSuffixGroupCandidate(token)) continue
            return token
        }
        return null
    }

    private fun isSuffixGroupCandidate(token: String): Boolean {
        if (token.length !in 2..12) return false
        // Episode/version shaped: S01E01, E01, S1, 01, v2.
        if (token.matches(Regex("""(?i)^([se]?\d+)+[a-z]?$"""))) return false
        if (token.matches(Regex("""(?i)^v\d+[a-z]?$"""))) return false
        // Resolution shaped: 1080p (4K/8K are in the deny set).
        if (token.matches(Regex("""(?i)^\d+p$"""))) return false
        val upper = token.uppercase()
        if (upper in SUFFIX_GROUP_DENY) return false
        if (SOURCE_SERVICE_ALIASES.containsKey(upper)) return false
        if (KNOWN_LANG_CODES.any { it.equals(upper, ignoreCase = true) }) {
            return false
        }
        return true
    }

    /**
     * Find the first valid bracketed group token in the release title,
     * corroborated against the parsed `📂` tracker (if any).
     *
     * Allowed prefix shapes:
     *   - "" (no prefix; the first bracket is the group)
     *   - "<quality> [...]":           e.g. "1080p [...]"
     *   - "<parsed-tracker> [...]":     e.g. "NyaaSi [...]"
     *   - "<quality> <parsed-tracker> [...]" or vice-versa
     *
     * If `parsedTracker` is non-null, the prefix's tracker-shaped token
     * MUST match the parsed tracker (case-preserved) exactly. Generic
     * title words like "Title" or "S01E01" are never accepted as
     * trackers. If no tracker was parsed from the `📂` line, the prefix
     * is allowed to be empty or to be a single quality token only.
     */
    private fun titleBracketGroup(
        releaseName: String,
        parsedTracker: String?,
    ): String? {
        for (rawLine in releaseName.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val firstBracket = line.indexOf('[')
            if (firstBracket < 0) continue
            val prefix = line.substring(0, firstBracket).trim()
            if (!isGroupPositionPrefix(prefix, parsedTracker)) continue
            val bracketRegex = Regex("""\[([^\]]+)]""")
            val match = bracketRegex.find(line.substring(firstBracket)) ?: continue
            val token = match.groupValues[1].trim()
            if (!isGroupCandidate(token)) continue
            if ('.' in token) {
                // Dotted scene compounds: resolve the trailing group
                // when the head is technical (`HDTV.x264-zyl` -> `zyl`);
                // reject the line when the head is technical but the
                // tail is not a group (`SD.H265.JAP.AC3.SUB.ITA`);
                // accept whole dotted names that are not technical
                // compounds (`anime4life.`).
                val compound = dottedCompoundGroup(token)
                if (compound != null) return compound
                if (hasTechnicalDotHead(token)) continue
            }
            return token
        }
        return null
    }

    /**
     * Check that the prefix before the first bracket is a valid
     * group-position prefix, optionally corroborated by the parsed tracker.
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

        // Determine which token slots are "quality" and which are
        // "tracker". The tracker slot must be corroborated by the parsed
        // tracker when one is available.
        val firstIsQuality = isQualityPrefixToken(tokens[0])
        val firstIsTrackerShape = isTrackerLikeToken(tokens[0])
        val secondIsQuality = tokens.size == 2 &&
            isQualityPrefixToken(tokens[1])
        val secondIsTrackerShape = tokens.size == 2 &&
            isTrackerLikeToken(tokens[1])
        // A technical second token (HDR / codec / bit-depth / ...) is
        // metadata already shown elsewhere, not a tracker or title word
        // (`1080p HDR [Salieri]` must accept Salieri). Bounded by the
        // same deny-list that rejects technical brackets as groups.
        val secondIsTechnical = tokens.size == 2 &&
            KNOWN_NON_GROUP_BRACKETS.any { it.equals(tokens[1], ignoreCase = true) }

        // Without a parsed tracker, the prefix may be empty, a
        // single quality token, or quality + one technical token.
        // No arbitrary title word may be treated as
        // a tracker in that case. We accept single-quality prefixes
        // without corroboration.
        if (parsedTracker == null) {
            if (tokens.size == 1 && firstIsQuality) return true
            if (tokens.size == 2 && firstIsQuality && secondIsQuality) {
                // "1080p 720p" is nonsensical; reject.
                return false
            }
            if (tokens.size == 2 && firstIsQuality && secondIsTechnical) {
                return true
            }
            // Reject any non-quality token in the prefix when there is
            // no tracker corroboration.
            return false
        }

        // With a parsed tracker, the prefix must have exactly one
        // tracker-shaped token, and it must equal the parsed tracker
        // (case-preserved).
        if (tokens.size == 2) {
            // "<quality> <tracker>": quality then tracker.
            if (firstIsQuality && secondIsTrackerShape &&
                tokens[1] == parsedTracker) return true
            // "<tracker> <quality>": tracker then quality.
            if (firstIsTrackerShape && tokens[0] == parsedTracker &&
                secondIsQuality) return true
            // "<quality> <technical>": technical metadata, no tracker.
            if (firstIsQuality && secondIsTechnical) return true
            return false
        }
        // Single-token prefix must be either the tracker itself or a
        // known quality token (which would be unusual but is allowed).
        if (tokens.size == 1) {
            if (tokens[0] == parsedTracker) return true
            if (firstIsQuality) return true
            return false
        }
        return false
    }

    /**
     * Technical dot-segments for dotted-compound brackets
     * (`HDTV.x264-zyl`, `SD.H265.JAP.AC3.SUB.ITA`): resolution, codec,
     * source-class, audio, bit-depth, HDR, subtitle/audio words,
     * language codes and SD/HD/VFR markers.
     */
    private fun isTechnicalDotSegment(segment: String): Boolean {
        val upper = segment.uppercase()
        if (upper in KNOWN_NON_GROUP_BRACKETS) return true
        if (upper in COMPOUND_TECHNICAL_EXTRA) return true
        if (SOURCE_SERVICE_ALIASES.containsKey(upper)) return true
        if (KNOWN_LANG_CODES.any { it.equals(upper, ignoreCase = true) }) return true
        if (segment.matches(Regex("""(?i)^(\d{3,4}p|4k|2k|8k)$"""))) return true
        if (segment.matches(Regex("""(?i)^((x|h)\.?26[45]|hevc|avc|av1|vp9|xvid|divx)$"""))) return true
        if (segment.matches(Regex("""(?i)^(aac|ac-?3|dts|e-?ac-?3|ddp|flac|opus|mp3|truehd|atmos)[\d.]*$"""))) return true
        if (segment.matches(Regex("""(?i)^((8|10)-?bits?|hi10p|hdr\d*\+?|dolby(vision)?|dv)$"""))) return true
        if (segment.matches(Regex("""(?i)^(web-?dl|webrip|bluray|bdrip|hdtv|dvdrip|remux)$"""))) return true
        if (segment.matches(Regex("""(?i)^(subs?|subbed|subtitles?|dubs?|dubbed|dual|multi|audio)$"""))) return true
        return false
    }

    private val COMPOUND_TECHNICAL_EXTRA: Set<String> = setOf(
        "SD", "HD", "VFR", "COMPLETA", "COMPLETO", "COMPLET",
        // Common non-ISO scene language shorthands (ISO codes are in
        // KNOWN_LANG_CODES already).
        "JAP", "ENG", "ESP", "POR", "FRE", "GER", "RUS", "CHI",
        "KOR", "ARA",
    )

    /**
     * Dotted-compound bracket groups (provider-dataset v1): scene-style
     * `[HDTV.x264-zyl]` carries the group after the technical head, so
     * it resolves to `zyl`. Returns null when the compound is
     * undecidable or the tail is itself technical
     * (`[SD.H265.JAP.AC3.SUB.ITA]` -> tail `ITA` is a language code).
     */
    private fun dottedCompoundGroup(token: String): String? {
        if ('.' !in token) return null
        val segments = token.split('.')
        if (segments.size < 2) return null
        if (!segments.dropLast(1).all { isTechnicalDotSegment(it) }) return null
        val tail = segments.last().substringAfterLast('-')
        if (!isGroupCandidate(tail)) return null
        return tail
    }

    private fun hasTechnicalDotHead(token: String): Boolean {
        if ('.' !in token) return false
        val segments = token.split('.')
        if (segments.size < 2) return false
        return segments.dropLast(1).all { isTechnicalDotSegment(it) }
    }

    private fun isTrackerLikeToken(token: String): Boolean {
        // Trackers are short alpha tokens (sometimes alphanumeric like
        // 1337x), without dot/colon/path markers.
        if (token.length !in 1..30) return false
        if (token.any { it == '.' || it == '/' || it == '\\' || it == ':' }) return false
        return token.any { it.isLetter() }
    }

    /**
     * True if the prefix before the first bracket is empty or only
     * contains known technical tokens (resolution, HDR, audio mode).
     *
     * Note: this is retained for any caller that needs to filter, but
     * the current [titleBracketGroup] implementation does not require
     * the prefix to be bounded — Torrentio's actual format uses both
     * `tracker [GROUP]` and `quality [GROUP]` patterns.
     */
    @Suppress("unused")
    private fun isAllowedPrefix(prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        val tokens = prefix.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        for (t in tokens) {
            val lower = t.lowercase()
            if (lower in ALLOWED_PREFIX_TOKENS) continue
            if (lower in setOf("hdr", "hdr10", "hdr10+", "10bit", "10-bit", "8bit", "8-bit",
                "dual", "audio", "dub", "sub", "subs")) continue
            return false
        }
        return true
    }

    // ---- Release field parsing ------------------------------------------

    data class ReleaseFields(
        val resolution: String?,
        val hdr: Boolean,
        val releaseName: String,
        val releaseGroup: String?,
        val seeders: Int?,
        val sizeText: String?,
        val tracker: String?,
        val indexer: String?,
        val audioSummary: String?,
        val audioFormat: String?,
        val codec: String?,
        val bitDepth: String?,
        val hdrVariant: String?,
        val subtitleSummary: String?,
        val subtitleFlags: List<String>,
        val audioFlags: List<String>,
        val subtitleLanguages: List<String>,
        val audioLanguages: List<String>,
        val sourceService: String?,
        val sourceClass: String?,
    )

    fun parseRelease(qualityText: String): ReleaseFields {
        val resolution = parseResolution(qualityText)
        val hdr = REGEX_HDR_SIMPLE.containsMatchIn(qualityText) ||
            REGEX_HDR_BARE.containsMatchIn(qualityText)
        val releaseName = extractReleaseName(qualityText)
        val releaseGroup = extractReleaseGroup(qualityText, releaseName)
        val seeders = REGEX_SEEDERS.find(qualityText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val sizeText = REGEX_SIZE.find(qualityText)?.groupValues?.getOrNull(1)?.trim()
        // Tracker/provenance: the dedicated 📂 value when present,
        // otherwise the lone ⚙ provider value (v2.2 current-format
        // compatibility — the 784-row corpus has zero 📂 rows).
        val tracker = parseTracker(qualityText)
        // Kept for backwards compatibility with older render paths.
        val indexer = tracker
        // Explicit 🎬 audio line wins; bare dub markers (DUAL /
        // Dubbed / Eng Dub / FRENCH / Castellano / ...) in the title
        // only fill the field when no explicit audio signal exists.
        // Bare MULTI is audio only when no multi-subs marker claims
        // the title (French-scene MULTI = FR+JP audio). Never inferred
        // from the provider/tracker name: detection scans the release
        // title.
        val audioSummaryExplicit =
            REGEX_AUDIO.find(qualityText)?.groupValues?.getOrNull(1)?.trim()
        val allowBareMulti =
            !REGEX_MULTI_SUBS_WORD.containsMatchIn(releaseName)
        val audioSummary = audioSummaryExplicit
            ?: detectAudioTrackKind(releaseName, allowBareMulti)
        val audioFormat = extractAudioFormat(qualityText, releaseName, audioSummary)
        val codec = extractCodec(qualityText, releaseName)
        val bitDepth = extractBitDepth(qualityText, releaseName)
        val hdrVariant = extractHdrVariant(qualityText, releaseName)
        val flagSets = extractFlagsAndLanguages(qualityText)
        val hasExplicitSubs = flagSets.subtitleFlags.isNotEmpty() ||
            flagSets.subtitleLanguages.isNotEmpty()
        // VOSTFR (original audio + French subs, by definition) materializes
        // as a French flag when no parsed flags exist, so the row carries
        // a flag chip like every other subtitled row instead of a text
        // label. Explicit parsed languages still win over it.
        val subtitleFlags = flagSets.subtitleFlags.ifEmpty {
            if (flagSets.subtitleLanguages.isEmpty() &&
                REGEX_VOSTFR.containsMatchIn(releaseName)
            ) {
                listOf("🇫🇷")
            } else {
                emptyList()
            }
        }
        // Bare title markers (VOSTFR / Multi-Subs / ...) beat the group
        // default below.
        val markedSubs = if (!hasExplicitSubs) {
            detectMultiSubsSummary(releaseName)
        } else {
            null
        }
        // Known group subtitle defaults (SubsPlease / HorribleSubs
        // always ship English subs): last resort only, filling both
        // the summary and the language list.
        val impliedLangs = if (!hasExplicitSubs && markedSubs == null) {
            IMPLIED_SUBTITLE_LANGUAGES[releaseGroup?.uppercase()]
                ?: emptyList()
        } else {
            emptyList()
        }
        val subtitleLanguages = flagSets.subtitleLanguages.ifEmpty { impliedLangs }
        // Subtitle summary prefers flags, falls back to language codes
        // (including the group default), then bare title markers.
        val subtitleSummary = buildSubtitleSummary(subtitleFlags,
            subtitleLanguages)
            ?: markedSubs
        val sourceService = detectSourceService(releaseName)
        val sourceClass = detectSourceClass(releaseName)
        return ReleaseFields(
            resolution, hdr, releaseName, releaseGroup, seeders, sizeText,
            tracker, indexer, audioSummary, audioFormat, codec, bitDepth, hdrVariant,
            subtitleSummary, subtitleFlags, flagSets.audioFlags,
            subtitleLanguages, flagSets.audioLanguages,
            sourceService, sourceClass,
        )
    }

    private fun buildSubtitleSummary(
        flags: List<String>,
        langs: List<String>,
    ): String? {
        if (flags.isNotEmpty()) {
            return if (flags.size <= 3) flags.joinToString(" ")
            else "${flags.take(3).joinToString(" ")} +${flags.size - 3}"
        }
        return when {
            langs.isEmpty() -> null
            langs.size == 1 -> langs.single()
            langs.size in 2..3 -> langs.joinToString("+")
            else -> "Multi-subs"
        }
    }

    /**
     * Bare dub track-kind in the release title (follow-ups v1, extended
     * by provider-dataset v1 with French / Spanish / Portuguese scene
     * vocabulary, all user-confirmed).
     *
     * Returns `Dual` for standalone DUAL / MULTIDUB / bare MULTI /
     * slashed `Jpn/Eng/Ger Audio` forms, `Dub` for Dubbed / English
     * Dub / Eng Dub / English Audio / FRENCH / Castellano / Latino /
     * Dublado, else null. Neutral `espanol` resolves through nearby
     * sub/dub context words. Word-boundary matched, so `individual`,
     * `gradual`, `redubbed`, `Japanese`, `HorribleSubs` or
     * `MicoLeaoDublado` never fire. Matched against the release title
     * only, never the provider or tracker name.
     *
     * Bare MULTI is audio only when no multi-subs marker is present
     * (French-scene MULTI = FR+JP audio, user-confirmed).
     */
    private fun detectAudioTrackKind(releaseName: String, allowBareMulti: Boolean = true): String? {
        if (REGEX_DUAL_WORD.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_DUAL_AUDIO_SEP.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_LANG_PAIR_DUAL.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_LANG_PAIR_WORDS_DUAL.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_DUBBED_WORD.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_ENG_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_FUNI_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_ENGLISH_AUDIO_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_FRENCH_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_CASTELLANO_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_LATINO_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_DUBLADO_DUB.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_MULTIDUB.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_SLASHED_AUDIO_DUAL.containsMatchIn(releaseName)) return "Dual"
        if (allowBareMulti && REGEX_BARE_MULTI.containsMatchIn(releaseName)) return "Dual"
        if (REGEX_ESPANOL.containsMatchIn(releaseName) &&
            REGEX_DUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Dub"
        if (REGEX_FULL_LANGUAGE.containsMatchIn(releaseName) &&
            REGEX_DUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Dub"
        return null
    }

    private val REGEX_BARE_MULTI = Regex("""(?i)\bmulti\b""")

    /**
     * Bare subtitle markers in the release title (follow-ups v1,
     * extended by provider-dataset v1). VOSTFR is handled upstream by
     * materializing a 🇫🇷 flag, so it is not listed here.
     *
     * Returns `Multi-subs` (the existing fallback string) for multi /
     * subs / subbed / subtitled / napisy / legendado / subtitulado
     * forms and context-resolved `espanol`, else null.
     */
    /**
     * Groups whose releases carry known subtitle defaults even when the
     * title says nothing (user-confirmed: the info is real release
     * metadata, just not printed in the title). `SubsPlease` and
     * `HorribleSubs` always ship English subtitles. Applied LAST, so
     * every explicit signal (flags, codes, markers) wins.
     */
    private val IMPLIED_SUBTITLE_LANGUAGES: Map<String, List<String>> = mapOf(
        "SUBSPLEASE" to listOf("ENG"),
        "HORRIBLESUBS" to listOf("ENG"),
    )

    private fun detectMultiSubsSummary(releaseName: String): String? {
        if (REGEX_MULTI_SUBS_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_SUBS_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_NAPISY.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_LEGENDADO.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_SUBTITULADO.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_ESPANOL.containsMatchIn(releaseName) &&
            REGEX_SUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        if (REGEX_FULL_LANGUAGE.containsMatchIn(releaseName) &&
            REGEX_SUB_CONTEXT_WORD.containsMatchIn(releaseName)) return "Multi-subs"
        return null
    }

    private fun extractAudioFormat(
        qualityText: String,
        releaseName: String,
        audioSummary: String?,
    ): String? {
        // NOTE: whole-match `value` (not group 1): the trailing DD+
        // alternative sits outside the numbered group, and an
        // unmatched group would read back as "".
        audioSummary?.let { line ->
            AUDIO_FORMAT_REGEX.find(line)?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { return normaliseAudioFormat(it) }
        }
        AUDIO_FORMAT_REGEX.find(releaseName)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return normaliseAudioFormat(it) }
        return AUDIO_FORMAT_REGEX.find(qualityText)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { normaliseAudioFormat(it) }
    }

    private fun normaliseAudioFormat(raw: String): String {
        // Collapse internal whitespace, then expand common codec
        // abbreviations to their canonical display form. A trailing
        // stereo `2.0` channel count is dropped: stereo is the default
        // these days, so `AAC 2.0` displays as `AAC` (user-confirmed);
        // real multichannel (`5.1`, `7.1`) is always kept. Smashed scene
        // shorthands (`DDP5`, `AC351`) expand to their spaced forms.
        //
        // Dolby families stay separate (standards-gap v2.1 correction):
        // plain `DD` is basic Dolby Digital and must never upgrade to
        // the `DDP`/`DD+`/`EAC3` (E-AC-3 / Dolby Digital Plus) family.
        // All E-AC-3 spellings (`EAC3`, `E-AC3`, `EAC-3`, `E-AC-3`)
        // canonicalize before channel rules, so the generic
        // letters+digit rewrite can never emit `EAC 3`.
        val collapsed = raw.replace(Regex("""\s+"""), " ").trim()
        // Channel-separator normalization (v2.2 Fix E): bounded
        // `FORMAT<dot/underscore/hyphen>channels` spellings become
        // `FORMAT channels` before the existing pipeline, so `DD.5.1`,
        // `EAC3.5.1`, `FLAC.5.1` and `PCM.5.1` resolve exactly like
        // their spaced forms. Family words are explicit; version-like
        // `MP3.320` stays descriptive via fallthrough.
        val chanNorm = collapsed.replace(
            Regex("""(?i)\b(DDP?|DD\+|AC-?3|E-?A-?C-?3|FLAC|AAC|OPUS|MP3|PCM|LPCM)\s*[._\-]+\s*(\d)"""),
            "$1 $2",
        )
        // Early E-AC-3 canonicalization on the channel-normalized form,
        // before the letters+digit rewrite below can split `EAC3` into
        // `EAC 3` (which no later rule could rejoin).
        val eacEarly = chanNorm.replace(
            Regex("""(?i)^E-?A-?C-?3\b"""),
            "E-AC-3",
        )
        val withSpace = eacEarly.replace(Regex("""^([A-Za-z]+)(\d)"""), "$1 $2")
        val stereoStripped = withSpace
            .replace(Regex("""\s+2\.0$"""), "")
            .trim()
        // Canonical E-AC-3 prefix: every spelling (`EAC3`, `E-AC3`,
        // `EAC-3`, `E-AC-3`, with optional channels) normalizes before
        // channel rules, so the generic letters+digit rewrite can never
        // emit `EAC 3`.
        val eacNorm = stereoStripped.replace(
            Regex("""(?i)^E-?A-?C-?3\b"""),
            "E-AC-3",
        )
        // Canonical DTS-HD tails (standards-gap v2.1 correction,
        // completed in v2.2): separator spellings collapse to `DTS-HD
        // MA` / `DTS-HD HRA` with channels preserved; joined `HDMA`
        // keeps the family with stereo `2.0` collapsed and 5.1/7.1
        // preserved (`DTS-HDMA.2.0` -> `DTS-HD MA`, reviewer blocker).
        val dtsNorm = eacNorm.replace(
            Regex("""(?i)^DTS[\s.\-_]+HD[\s.\-_]+(MA|HRA)([\s.]+(\d\.\d))?$"""),
        ) { m ->
            val ch = m.groupValues[3]
            "DTS-HD ${m.groupValues[1].uppercase()}${if (ch.isEmpty() || ch == "2.0") "" else " $ch"}"
        }.replace(
            Regex("""(?i)^DTS[\s.\-_]*HDMA(?:[\s.]+(\d\.\d))?$"""),
        ) { m ->
            val ch = m.groupValues[1]
            if (ch.isEmpty() || ch == "2.0") "DTS-HD MA" else "DTS-HD MA $ch"
        }
        // Explicit Dolby families (v2.2 Fix C): full-phrase match on
        // the spaced form; stereo already collapsed above, multichannel
        // (5.1/6.1/7.1) preserved. `Dolby Vision` never reaches here
        // (no audio alternative matches it).
        val upSpaced = dtsNorm.uppercase()
        val dolbyFamily: String? = when {
            upSpaced.startsWith("DOLBY DIGITAL PLUS") -> "DDP"
            upSpaced.startsWith("DOLBY DIGITAL") -> "DD"
            upSpaced.startsWith("DOLBY TRUEHD") -> "TRUEHD"
            else -> null
        }
        if (dolbyFamily != null) {
            val ch = Regex("""([567])\.1$""").find(upSpaced.trim())?.groupValues?.get(1)
            return when (dolbyFamily) {
                "DDP" -> if (ch != null) "E-AC-3 / DDP $ch.1" else "E-AC-3 / DDP"
                "DD" -> if (ch != null) "DD $ch.1" else "DD"
                else -> if (ch != null) "TrueHD $ch.1" else "TrueHD"
            }
        }
        // Spaceless, trailing-dot-tolerant compare key so `AC 3`,
        // `AC351.` and `DDP 5` all hit their canonical branches.
        // Smashed multichannel (`DD61`, `EAC361`) is re-dotted first so
        // one dotted branch set covers every spelling.
        val key = dtsNorm.uppercase()
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""\.+$"""), "")
            .replace(
                Regex("""^((?:DDP?|DD\+|AC-?3|E-?A-?C-?3|AAC|FLAC|TRUEHD))(\d)(\d)$"""),
                "$1$2.$3",
            )
        return when (key) {
            "DD" -> "DD"
            "DD2.0", "DD2" -> "DD"
            "DD5.1", "DD5" -> "DD 5.1"
            "DD6.1", "DD7.1" -> key.take(2) + " " + key.drop(2)
            "DDP2.0", "DDP2", "DDP", "DD+", "DD+2.0", "DD+2" -> "E-AC-3 / DDP"
            "DDP5.1", "DDP5", "DD+5.1", "DD+5" -> "E-AC-3 / DDP 5.1"
            "DDP6.1", "DDP7.1", "DD+6.1", "DD+7.1" -> "E-AC-3 / DDP " + key.takeLast(3)
            "AC-3", "AC3" -> "AC-3"
            "AC-35.1", "AC35.1", "AC351" -> "AC-3 5.1"
            "AC-36.1", "AC36.1", "AC-37.1", "AC37.1" -> "AC-3 " + key.takeLast(3)
            "E-AC-3" -> "E-AC-3"
            "E-AC-35.1" -> "E-AC-3 5.1"
            "EAC36.1" -> "E-AC-3 6.1"
            "EAC37.1" -> "E-AC-3 7.1"
            "E-AC-36.1", "E-AC-37.1" -> "E-AC-3 " + key.takeLast(3)
            "AAC5.1", "AAC51" -> "AAC 5.1"
            "AAC6.1", "AAC61", "AAC7.1", "AAC71" -> "AAC " + key.takeLast(3)
            "FLAC6.1", "FLAC7.1" -> "FLAC " + key.takeLast(3)
            "TRUEHD6.1", "TRUEHD7.1" -> "TrueHD " + key.takeLast(3)
            "DTS" -> "DTS"
            "TRUEHD" -> "TrueHD"
            "ATMOS" -> "Atmos"
            else -> dtsNorm.uppercase()
        }
    }

    private fun extractCodec(qualityText: String, releaseName: String): String? {
        REGEX_CODEC_BRACKETED.find(releaseName)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BRACKETED.find(qualityText)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BARE.find(releaseName)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        REGEX_CODEC_BARE.find(qualityText)?.let {
            val n = normaliseCodec(it.groupValues[1]); if (n != null) return n
        }
        return null
    }

    private fun extractBitDepth(qualityText: String, releaseName: String): String? {
        REGEX_BIT_DEPTH.find(releaseName)?.let {
            val n = normaliseBitDepth(it.value); if (n != null) return n
        }
        REGEX_BIT_DEPTH.find(qualityText)?.let {
            val n = normaliseBitDepth(it.value); if (n != null) return n
        }
        return null
    }

    private fun extractHdrVariant(qualityText: String, releaseName: String): String? {
        REGEX_HDR_PLUS.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR_PLUS.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10P_BARE.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10P_BARE.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10PLUS_BARE.find(releaseName)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10PLUS_BARE.find(qualityText)?.let { return normaliseHdr("hdr10+") }
        REGEX_HDR10.find(releaseName)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10.find(qualityText)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10_BARE.find(releaseName)?.let { return normaliseHdr("hdr10") }
        REGEX_HDR10_BARE.find(qualityText)?.let { return normaliseHdr("hdr10") }
        REGEX_DV.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV_BARE.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DV_BARE.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        REGEX_DOLBY_VISION_BARE.find(releaseName)?.let { return normaliseHdr("dolby vision") }
        REGEX_DOLBY_VISION_BARE.find(qualityText)?.let { return normaliseHdr("dolby vision") }
        if (REGEX_HDR_SIMPLE.containsMatchIn(releaseName) ||
            REGEX_HDR_SIMPLE.containsMatchIn(qualityText) ||
            REGEX_HDR_BARE.containsMatchIn(releaseName) ||
            REGEX_HDR_BARE.containsMatchIn(qualityText)) {
            if (!REGEX_HDR10.containsMatchIn(releaseName) &&
                !REGEX_HDR10.containsMatchIn(qualityText) &&
                !REGEX_HDR_PLUS.containsMatchIn(releaseName) &&
                !REGEX_HDR_PLUS.containsMatchIn(qualityText) &&
                !REGEX_HDR10P_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10P_BARE.containsMatchIn(qualityText) &&
                !REGEX_HDR10PLUS_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10PLUS_BARE.containsMatchIn(qualityText) &&
                !REGEX_HDR10_BARE.containsMatchIn(releaseName) &&
                !REGEX_HDR10_BARE.containsMatchIn(qualityText) &&
                !REGEX_DV.containsMatchIn(releaseName) &&
                !REGEX_DV.containsMatchIn(qualityText) &&
                !REGEX_DV_BARE.containsMatchIn(releaseName) &&
                !REGEX_DV_BARE.containsMatchIn(qualityText) &&
                !REGEX_DOLBY_VISION_BARE.containsMatchIn(releaseName) &&
                !REGEX_DOLBY_VISION_BARE.containsMatchIn(qualityText)) {
                return "HDR"
            }
        }
        return null
    }

    // ---- Clean display title (v3.3) ------------------------------------

    /**
     * Produce a cleaned display title that removes tokens already
     * confidently represented elsewhere (quality, codec, bit depth,
     * source service, release group). The exact raw title is preserved
     * separately for identity/selection.
     */
    fun cleanDisplayTitle(
        releaseName: String,
        releaseGroup: String?,
        resolution: String?,
        codec: String?,
        bitDepth: String?,
        sourceService: String?,
    ): String {
        var cleaned = releaseName
        // Remove a single matching technical token per pass; stop when
        // nothing more is removed in a full pass.
        val removableBrackets = mutableSetOf<String>()
        codec?.let { removableBrackets.add(it) }
        bitDepth?.let { removableBrackets.add(it) }
        resolution?.let { removableBrackets.add(it) }
        // Use the source-service ALIAS KEY (NF, AMZN, CR) when matching
        // the raw title, because that is the form Torrentio actually
        // emits in the title. The expanded form ("Netflix") is what the
        // caller passes in for display purposes.
        sourceService?.let { service ->
            val alias = SOURCE_SERVICE_ALIASES.entries.firstOrNull { it.value == service }?.key
            if (alias != null) removableBrackets.add(alias)
        }
        // Try up to 8 passes to converge.
        repeat(8) {
            var changed = false
            for (token in removableBrackets) {
                // Match bracketed token either as-is (e.g. "[10-bit]") or
                // with the hyphen stripped (e.g. "[10bit]").
                val noHyphen = token.replace("-", "")
                val pattern = Regex(
                    """\[(?:\Q$token\E|\Q$noHyphen\E)\]""",
                    RegexOption.IGNORE_CASE,
                )
                if (pattern.containsMatchIn(cleaned)) {
                    cleaned = pattern.replace(cleaned, "")
                    changed = true
                }
            }
            // Remove a leading "[GROUP] " if it matches the release group
            // (the group is shown in the header so don't repeat it).
            if (releaseGroup != null) {
                val groupPattern = Regex(
                    """(?:^|\s)\[\Q$releaseGroup\E]\s*""",
                    RegexOption.IGNORE_CASE,
                )
                if (groupPattern.containsMatchIn(cleaned)) {
                    cleaned = groupPattern.replace(cleaned, " ")
                    changed = true
                }
            }
            // Remove leading technical tokens like "1080p" or "4k" before
            // the title.
            val leadingTechPattern =
                Regex("""^(?:\s*(?:4k|2160p|1080p|720p|480p|1440p)\b)+""", RegexOption.IGNORE_CASE)
            if (leadingTechPattern.containsMatchIn(cleaned)) {
                cleaned = leadingTechPattern.replace(cleaned, "")
                changed = true
            }
            // Remove a leading "[GROUP] " if not already removed (alternative
            // bracket placement).
            if (releaseGroup != null) {
                val altPattern = Regex(
                    """^\[\Q$releaseGroup\E]\s+""",
                    RegexOption.IGNORE_CASE,
                )
                if (altPattern.containsMatchIn(cleaned)) {
                    cleaned = altPattern.replace(cleaned, "")
                    changed = true
                }
            }
            // Remove source-service aliases (NF / AMZN / CR) that appear
            // unbracketed in the title. The raw name is preserved in
            // details; the compact title is UI-only.
            if (sourceService != null) {
                // The pattern uses the ALIAS KEY (NF / AMZN / CR) since
                // that's what Torrentio actually emits. sourceService is
                // the expanded form (Netflix / Amazon / Crunchyroll).
                val alias = SOURCE_SERVICE_ALIASES.entries.firstOrNull {
                    it.value == sourceService
                }?.key ?: sourceService
                val ssPattern = Regex(
                    """(?:^|\s)\Q$alias\E(?:\s|$)""",
                    RegexOption.IGNORE_CASE,
                )
                if (ssPattern.containsMatchIn(cleaned)) {
                    cleaned = ssPattern.replace(cleaned, " ")
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        // Final cleanup: collapse whitespace.
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }
}