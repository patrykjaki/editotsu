package ani.dantotsu.media.anime.selector

import java.util.Locale

/**
 * Shows a card title in the user's title language (AniList "userPreferred": romaji or
 * English). Display only: matching, filtering and remembering use the raw release names.
 *
 * - Notes that only repeat this show's name ("(Kusuriya no Hitorigoto 3rd Season, Multi-Subs)",
 *   "| Kusuriya no Hitorigoto - 53") lose that part; other notes stay.
 * - A title that starts with the show's name in another language gets the preferred one
 *   ("Kusuriya no Hitorigoto - 49v2" -> "The Apothecary Diaries - 49v2"); a season-less name
 *   gets the season-less preferred name.
 * Names it doesn't recognise are left as they are.
 */
class ShowTitles(preferred: String, all: Collection<String>) {

    private class Variant(val regex: Regex, val norm: String, val replacement: String)

    private val known: Set<String>
    private val variants: List<Variant>

    init {
        val prefFull = preferred.trim()
        val prefBase = base(prefFull)
        val names = (all + prefFull).map { it.trim() }.filter { it.length >= MIN_LEN }.distinct()
        known = names.flatMap { listOf(norm(it), norm(base(it))) }.filter { it.length >= MIN_LEN }.toSet()
        variants = names.flatMap { n ->
            listOf(n to prefFull, base(n) to prefBase)
        }
            .filter { (v, _) -> v.length >= MIN_LEN }
            .distinctBy { norm(it.first) }
            .sortedByDescending { norm(it.first).length }
            .map { (v, r) -> Variant(prefixRegex(v), norm(v), r) }
    }

    fun localize(title: String?): String? {
        if (title.isNullOrBlank()) return title
        var t = dropRepeatedNames(title)
        if (variants.any { it.regex.containsMatchIn(t) }) t = dropTrailingAltTitles(t)
        for (v in variants) {
            val m = v.regex.find(t) ?: continue
            if (norm(m.value) == norm(v.replacement)) break
            t = v.replacement + t.substring(m.range.last + 1)
            break
        }
        return ReleaseTitleNormalizer.normalize(t).ifBlank { title }
    }

    private fun isShowName(part: String): Boolean {
        val n = norm(part)
        if (n.length < MIN_LEN) return false
        return n in known || norm(base(part)) in known
    }

    private fun dropRepeatedNames(title: String): String {
        // "A | B | C": later segments that start with the show's name go
        val pipes = title.split(" | ")
        var t = pipes.first() + pipes.drop(1).filterNot { seg ->
            val n = norm(seg)
            known.any { k -> n == k || n.startsWith("$k ") }
        }.joinToString("") { " | $it" }
        // "(Name, Multi-Subs)" -> "(Multi-Subs)"; "(Name)" -> ""
        t = PARENS.replace(t) { m ->
            val parts = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val keep = parts.filterNot(::isShowName)
            when {
                keep.size == parts.size -> m.value
                keep.isEmpty() -> ""
                else -> "(" + keep.joinToString(", ") + ")"
            }
        }
        return t
    }

    /**
     * The card already starts with the show's name, so a trailing "(...)" part that reads like
     * another title (3+ words, letters only, no tag words) is an alternate name AniList doesn't
     * list: "(There Is Also a Hole in the Student Organization)".
     */
    private fun dropTrailingAltTitles(title: String): String {
        val m = TRAILING_PARENS.find(title) ?: return title
        val parts = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keep = parts.filterNot(::looksLikeTitle)
        if (keep.size == parts.size) return title
        val rest = if (keep.isEmpty()) "" else " (" + keep.joinToString(", ") + ")"
        return title.substring(0, m.range.first).trimEnd() + rest
    }

    private fun looksLikeTitle(part: String): Boolean {
        val words = part.split(' ').filter { it.isNotEmpty() }
        if (words.size < 3) return false
        if (!words.all { w -> w.all { it.isLetter() || it == '\'' || it == '!' || it == '?' || it == ':' } }) return false
        return words.none { it.lowercase(Locale.ROOT).trimEnd('!', '?', ':') in TAG_WORDS }
    }

    override fun equals(other: Any?): Boolean =
        other is ShowTitles && other.known == known && other.variants.map { it.norm to it.replacement } == variants.map { it.norm to it.replacement }

    override fun hashCode(): Int = known.hashCode()

    companion object {
        private const val MIN_LEN = 4
        private val PARENS = Regex("""\(([^()]*)\)""")
        private val TRAILING_PARENS = Regex("""\(([^()]*)\)\s*$""")
        private val TAG_WORDS = setOf(
            "sub", "subs", "subbed", "dub", "dubbed", "audio", "dual", "multi", "raw", "raws", "uncensored",
            "censored", "batch", "complete", "web", "bd", "bluray", "remux", "weekly", "english", "french",
            "spanish", "german", "italian", "portuguese", "subtitles", "version", "edition", "cut",
        )
        private val SEASON_SUFFIX = Regex(
            """(?i)[\s:\-–]*(?:season\s*\d+|\d+(?:st|nd|rd|th)\s+season|s\d{1,2}|part\s*\d+|cour\s*\d+|\d+(?:st|nd|rd|th)\s+cour|\(\d{4}\))\s*$""",
        )
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        fun base(name: String): String {
            var s = name.trim()
            while (true) {
                val next = SEASON_SUFFIX.replace(s, "").trim()
                if (next == s || next.isEmpty()) return s
                s = next
            }
        }

        fun norm(s: String): String = NON_WORD.replace(s.lowercase(Locale.ROOT), " ").trim()

        /** the name at the start of a title, any separators between its words, whole words only */
        private fun prefixRegex(name: String): Regex {
            val words = norm(name).split(' ').filter { it.isNotEmpty() }.map(Regex::escape)
            return Regex("^" + words.joinToString("""[^\p{L}\p{N}]+""") + """(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        }
    }
}
