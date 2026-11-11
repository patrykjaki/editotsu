package ani.dantotsu.media.anime.player

/**
 * Language matching for automatic subtitle choice, shared by extension subtitles and
 * embedded tracks.
 *
 * Order: the user's language (per-episode saved choice, else Settings > Subtitle language),
 * then English, then the first track. A track matches a language when its language field
 * is one of the language's codes (`pl`, `pol`, `pl-PL`) or names, or when its language or
 * title contains one of the language's names as a whole word ("English [CC]",
 * "Polski (napisy)"). Codes are never matched as substrings, so "en" does not match
 * "French" and "it" does not match "Subtitles".
 */
object SubtitleLanguageMatch {

    /** Settings > Subtitle language, in the order of the stored index. */
    val SETTINGS_LANGUAGES = listOf(
        "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
        "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
        "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
        "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
        "Turkish", "Ukrainian", "Urdu", "Vietnamese"
    )

    fun settingsLanguage(index: Int?): String =
        index?.let { SETTINGS_LANGUAGES.getOrNull(it) } ?: "English"

    private class Lang(val codes: Set<String>, val names: Set<String>)

    private fun lang(codes: String, names: String) = Lang(
        codes.split(' ').toSet(),
        names.split('|').map { it.lowercase() }.toSet(),
    )

    private val LANGS = listOf(
        lang("sq sqi alb", "albanian|shqip"),
        lang("ar ara", "arabic|العربية"),
        lang("bs bos", "bosnian|bosanski"),
        lang("bg bul", "bulgarian|български"),
        lang("zh zho chi", "chinese|中文|简体中文|繁體中文"),
        lang("hr hrv", "croatian|hrvatski"),
        lang("cs ces cze", "czech|čeština"),
        lang("da dan", "danish|dansk"),
        lang("nl nld dut", "dutch|nederlands"),
        lang("en eng", "english"),
        lang("et est", "estonian|eesti"),
        lang("fi fin", "finnish|suomi"),
        lang("fr fra fre", "french|français"),
        lang("ka kat geo", "georgian|ქართული"),
        lang("de deu ger", "german|deutsch"),
        lang("el ell gre", "greek|ελληνικά"),
        lang("he heb iw", "hebrew|עברית"),
        lang("hi hin", "hindi|हिन्दी"),
        lang("id ind", "indonesian|bahasa indonesia"),
        lang("ga gle", "irish|gaeilge"),
        lang("it ita", "italian|italiano"),
        lang("ja jpn", "japanese|日本語"),
        lang("ko kor", "korean|한국어"),
        lang("lt lit", "lithuanian|lietuvių"),
        lang("lb ltz", "luxembourgish|lëtzebuergesch"),
        lang("mk mkd mac", "macedonian|македонски"),
        lang("mn mon", "mongolian|монгол"),
        lang("no nor nb nob nn nno", "norwegian|norsk"),
        lang("pl pol", "polish|polski"),
        lang("pt por", "portuguese|português"),
        lang("pa pan", "punjabi|ਪੰਜਾਬੀ"),
        lang("ro ron rum", "romanian|română"),
        lang("ru rus", "russian|русский"),
        lang("sr srp", "serbian|српски|srpski"),
        lang("sk slk slo", "slovak|slovenčina"),
        lang("sl slv", "slovenian|slovene|slovenščina"),
        lang("es spa", "spanish|español|castellano"),
        lang("tr tur", "turkish|türkçe"),
        lang("uk ukr", "ukrainian|українська"),
        lang("ur urd", "urdu|اردو"),
        lang("vi vie", "vietnamese|tiếng việt"),
    )

    private fun langFor(target: String): Lang? {
        val t = target.trim().lowercase()
        if (t.isEmpty()) return null
        val primary = t.substringBefore('-').substringBefore('_')
        return LANGS.firstOrNull { t in it.codes || primary in it.codes || t in it.names }
    }

    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + word.length)
            if ((before == null || !before.isLetter()) && (after == null || !after.isLetter())) return true
            from = i + 1
        }
    }

    /**
     * True when a track with [language] and [name] is in [target], where [target] is a
     * code ("pl", "pol", "pt-BR") or a name ("Polish"). An unknown target matches only
     * itself exactly or as a whole word.
     */
    fun matches(language: String?, name: String?, target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val lang = language?.trim()?.lowercase().orEmpty()
        val title = name?.trim()?.lowercase().orEmpty()
        val l = langFor(target)
        if (l == null) {
            val t = target.trim().lowercase()
            return lang == t || containsWord(lang, t) || containsWord(title, t)
        }
        if (lang.isNotEmpty()) {
            val primary = lang.substringBefore('-').substringBefore('_')
            if (lang in l.codes || primary in l.codes || lang in l.names) return true
        }
        return l.names.any { containsWord(lang, it) || containsWord(title, it) }
    }

    /** 2 = the user's language, 1 = English, 0 = neither. */
    fun tier(language: String?, name: String?, preferred: String?): Int = when {
        matches(language, name, preferred) -> 2
        matches(language, name, "English") -> 1
        else -> 0
    }

    /**
     * Picks from [items]: the first in [preferred], else the first in English, else the
     * first item. Used for extension subtitles, whose label is in [label].
     */
    fun <T> pick(items: List<T>, preferred: String?, label: (T) -> String?): T? =
        items.firstOrNull { matches(label(it), null, preferred) }
            ?: items.firstOrNull { matches(label(it), null, "English") }
            ?: items.firstOrNull()

    /** True when a saved per-episode value names a language (not Off, a file or a track). */
    fun isLanguageChoice(saved: String?): Boolean =
        !saved.isNullOrBlank() &&
            !saved.equals("None", ignoreCase = true) &&
            !saved.startsWith("Embedded:", ignoreCase = true) &&
            !saved.startsWith("[Local]") &&
            !saved.startsWith("Online:")

    fun isSignsOrSongs(track: PlayerTrack): Boolean {
        if (track.forced) return true
        val name = track.name?.lowercase() ?: ""
        val signsKeywords = listOf(
            "sign", "song", "s&s", "s/s", "forced", "op/ed", "oped", "insert",
            "signs & songs", "signs/songs", "signs and songs"
        )
        return signsKeywords.any { kw ->
            name.contains(kw) && !name.contains("dialogue") && !name.contains("full")
        }
    }

    private fun isFullDialogue(track: PlayerTrack): Boolean {
        val name = track.name?.lowercase() ?: ""
        return listOf("dialogue", "dialog", "full", "complete", "main", "subs", "subtitles").any { name.contains(it) }
    }

    /**
     * Score for an embedded subtitle track. The language tier dominates; within a tier
     * full dialogue beats signs/songs and forced tracks, and the default flag breaks ties.
     */
    fun scoreEmbedded(track: PlayerTrack, preferred: String?): Int {
        var score = tier(track.language, track.name, preferred) * 10_000
        if (isFullDialogue(track)) score += 200
        if (isSignsOrSongs(track)) score -= 600
        if (track.forced) score -= 400
        if (track.default) score += 50
        return score
    }

    /**
     * Automatic embedded subtitle: the saved per-episode pick when it still exists, else the
     * best track for [saved] (when it names a language) or [fallbackLang] (Settings), then
     * English, then a non-signs track. Null for an explicit Off ("None").
     */
    fun bestEmbedded(tracks: List<PlayerTrack>, saved: String?, fallbackLang: String?): PlayerTrack? {
        val subTracks = tracks.filter { it.type == TrackType.SUBTITLE }
        if (subTracks.isEmpty()) return null
        if (saved != null && saved.equals("None", ignoreCase = true)) return null
        var preferred = if (isLanguageChoice(saved)) saved else fallbackLang
        val key = parseEmbeddedKey(saved)
        if (key != null) {
            restoreEmbedded(subTracks.map { EmbeddedRef(it.id, it.language, it.name) }, key)
                ?.let { id -> return subTracks.first { it.id == id } }
            // The saved track is gone (another release): fall back to its language.
            if (!key.language.isNullOrBlank()) preferred = key.language
        }
        val best = subTracks
            .map { it to scoreEmbedded(it, preferred) }
            .sortedWith(compareByDescending<Pair<PlayerTrack, Int>> { it.second }.thenBy { it.first.id })
            .firstOrNull()
        if (best != null && best.second > -300) return best.first
        return subTracks.firstOrNull { it.default && !isSignsOrSongs(it) }
            ?: subTracks.firstOrNull { !isSignsOrSongs(it) }
            ?: subTracks.firstOrNull { !it.forced }
            ?: subTracks.firstOrNull()
    }

    // ---- saved embedded picks ----

    data class EmbeddedRef(val id: Int, val language: String?, val name: String?)

    /**
     * A saved embedded pick. New values are `Embedded:#<id>|<language>|<title>`; values from
     * older builds are `Embedded:<language>` or `Embedded:<id>` and set [legacy].
     */
    data class EmbeddedKey(
        val id: Int?,
        val language: String?,
        val title: String?,
        val legacy: String? = null,
    )

    private const val EMBEDDED_PREFIX = "Embedded:"

    fun embeddedKey(id: Int, language: String?, title: String?): String =
        "$EMBEDDED_PREFIX#$id|${language.orEmpty().replace("|", " ")}|${title.orEmpty()}"

    fun parseEmbeddedKey(saved: String?): EmbeddedKey? {
        if (saved == null || !saved.startsWith(EMBEDDED_PREFIX, ignoreCase = true)) return null
        val body = saved.substring(EMBEDDED_PREFIX.length).trim()
        if (body.startsWith("#")) {
            val parts = body.substring(1).split('|', limit = 3)
            return EmbeddedKey(
                id = parts.getOrNull(0)?.toIntOrNull(),
                language = parts.getOrNull(1)?.ifBlank { null },
                title = parts.getOrNull(2)?.ifBlank { null },
            )
        }
        return EmbeddedKey(id = body.toIntOrNull(), language = null, title = null, legacy = body)
    }

    /**
     * Track id for a saved embedded pick, or null when nothing fits. New keys match id and
     * title, then the title alone, then id and language; legacy keys match the id, then the
     * language code or a title containing it.
     */
    fun restoreEmbedded(tracks: List<EmbeddedRef>, key: EmbeddedKey): Int? {
        if (key.legacy != null) {
            key.id?.let { id -> tracks.firstOrNull { it.id == id }?.let { return it.id } }
            val target = key.legacy
            return tracks.firstOrNull {
                it.language?.equals(target, ignoreCase = true) == true ||
                    it.name?.contains(target, ignoreCase = true) == true
            }?.id
        }
        val title = key.title
        if (title != null) {
            tracks.firstOrNull { it.id == key.id && it.name == title }?.let { return it.id }
            tracks.firstOrNull { it.name == title && (key.language == null || it.language == key.language) }
                ?.let { return it.id }
            return null
        }
        return tracks.firstOrNull { it.id == key.id && it.language == key.language }?.id
    }

    /** Whether the subtitle dialog shows a track as the saved pick. */
    fun isSavedEmbedded(saved: String?, track: EmbeddedRef): Boolean {
        val key = parseEmbeddedKey(saved) ?: return false
        if (key.legacy != null) return false
        return restoreEmbedded(listOf(track), key) == track.id && key.id == track.id
    }

    /** Text for the active-subtitle line: the title, else the language, else the id. */
    fun embeddedLabel(saved: String): String {
        val key = parseEmbeddedKey(saved) ?: return saved
        return key.legacy ?: key.title ?: key.language ?: "#${key.id}"
    }
}
