package ani.dantotsu.connections.subtitles

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * Languages for online subtitle search (Settings > Player > Online subtitles).
 *
 * The pref holds ISO 639-1 codes ("en"), but its default and old installs hold the English
 * name ("English"). Everything reads through [codes] so both forms mean the same language;
 * the stored value is not rewritten, so backups stay as they were.
 */
object OnlineSubtitleLanguages {
    /** Offered in the picker, in this order. */
    val CODES = listOf("en", "ar", "pt", "es", "id", "fr", "ru", "zh", "ja", "tr", "it", "de", "pl", "th", "vi", "ko")

    fun englishName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)

    /** Stored values as codes: names of offered languages map to their code, unknown values pass through. */
    fun codes(stored: Set<String>): Set<String> = stored.mapTo(LinkedHashSet()) { value ->
        CODES.firstOrNull { it.equals(value, true) || englishName(it).equals(value, true) } ?: value
    }

    fun codes(): Set<String> = codes(PrefManager.getVal(PrefName.OnlineSubtitleLanguages))

    /** Wyzie takes codes: "en,ja". */
    fun forWyzie(): String = codes().joinToString(",")

    /** SubSource takes lowercase English names: "english,japanese" (what the "English" default sent). */
    fun forSubSource(): String = codes().joinToString(",") { englishName(it).lowercase(Locale.ROOT) }
}
