package ani.dantotsu.media.anime.selector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Raw release facts a source puts in aniyomi `Video.internalData` (contract version 1, sent by
 * the Editotsu Torrents extension):
 *
 * `{"editotsu":1,"infoHash","fileIdx","torrentName","fileName","sizeBytes","sizeApprox",
 *   "seeders","leechers","tracker","anidbEid","numFiles", ...}`
 *
 * Numbers and names are taken as they are; the picker doesn't parse them out of text. Release
 * tags (group, codec, audio, censorship, ...) exist only in the names, so [SourceRowClassifier]
 * still parses [torrentName] and [fileName] for those (one parser for all sources).
 *
 * Every key is optional: a missing or wrongly typed key is null and that field falls back to
 * text parsing. Anything that isn't a version-1 object (missing, not JSON, other extensions'
 * own strings) gives null for the whole row, and the picker parses the text as before.
 * [resolution] is not in the JSON: it is aniyomi's `Video.resolution`, see [of].
 */
data class EditotsuInternalData(
    val torrentName: String? = null,
    val fileName: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sizeBytes: Long? = null,
    val sizeApprox: Boolean = false,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val tracker: String? = null,
    val anidbEid: Int? = null,
    val numFiles: Int? = null,
    val resolution: Int? = null,
    /** what the file contains (`"from":"mediainfo"`) or what its names state (`"from":"name"`) */
    val media: Media? = null,
    /** facts only names carry, read once by the source */
    val tags: Tags? = null,
) {
    data class Track(val lang: String?, val codec: String?, val channels: Int?, val forced: Boolean, val title: String?)

    data class Media(
        /** true = MediaInfo of the file itself; false = stated by the release names */
        val fromFile: Boolean,
        val codec: String?,
        val bitDepth: Int?,
        val hdr: String?,
        val audio: List<Track>,
        val subtitles: List<Track>,
        /** name-derived only: "dual", "dub", "multi" */
        val audioKind: String?,
        val multiSubs: Boolean,
    )

    data class Tags(
        val group: String?,
        val source: String?,
        val service: String?,
        val revision: String?,
        val censorship: String?,
    )

    /** Sub / Dub / Dual from the audio tracks (or the source's name reading) */
    fun audioKind(): AudioKind? {
        val m = media ?: return null
        if (m.fromFile) {
            val langs = m.audio.mapNotNull { it.lang?.substringBefore('-')?.lowercase(Locale.ROOT) }.toSet()
            if (langs.isEmpty()) return null
            val original = langs.any { it in ORIGINAL_LANGS }
            val other = langs.any { it !in ORIGINAL_LANGS }
            return when {
                original && other -> AudioKind.DUAL
                other -> AudioKind.DUB
                else -> AudioKind.SUB
            }
        }
        return when (m.audioKind) {
            "dual", "multi" -> AudioKind.DUAL
            "dub" -> AudioKind.DUB
            else -> null
        }
    }

    /** picker language code for a BCP-47 tag ("pt-BR" -> "PT-BR", "es-419" -> "ES-LA", "zh-Hant" -> "CHT") */
    fun pickerLang(tag: String): String {
        val t = tag.trim()
        val lang = t.substringBefore('-').uppercase(Locale.ROOT)
        val region = t.substringAfter('-', "").uppercase(Locale.ROOT)
        return when {
            lang == "ES" && region == "419" -> "ES-LA"
            lang == "ZH" && (region == "HANT" || region == "TW" || region == "HK") -> "CHT"
            lang == "PT" && region == "BR" -> "PT-BR"
            else -> lang
        }
    }

    /** true for a file picked out of a multi-file torrent */
    val isPack: Boolean get() = (numFiles ?: 1) > 1

    /** "1.39 GB" / "350.2 MB" (binary units, Torrentio's labels); "~" when the source rounded it */
    fun sizeText(): String? {
        val bytes = sizeBytes?.takeIf { it > 0 } ?: return null
        val units = listOf("KB" to (1L shl 10), "MB" to (1L shl 20), "GB" to (1L shl 30), "TB" to (1L shl 40))
        val (unit, div) = units.lastOrNull { bytes >= it.second } ?: return "$bytes B"
        val v = bytes.toDouble() / div
        val text = String.format(Locale.ROOT, if (v >= 100) "%.1f %s" else "%.2f %s", v, unit)
        return if (sizeApprox) "~$text" else text
    }

    /** display name of [tracker] ("nyaa" -> "Nyaa") */
    fun trackerLabel(): String? = tracker?.let { TRACKER_LABELS[it.lowercase(Locale.ROOT)] ?: it }

    /** "1080p" style, the format [SourceRowClassifier] uses */
    fun resolutionText(): String? = resolution?.takeIf { it > 0 }?.let { "${it}p" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** original-audio languages for anime/donghua/aeni: anything else is a dub */
        private val ORIGINAL_LANGS = setOf("ja", "zh", "ko")

        private val TRACKER_LABELS = mapOf(
            "nyaa" to "Nyaa",
            "sukebei" to "Sukebei",
            "nekobt" to "nekoBT",
            "anidex" to "AniDex",
            "tokyotosho" to "TokyoTosho",
            "unknown" to "Other",
        )

        /** [parse] of the video's internalData, plus its `resolution` */
        fun of(video: eu.kanade.tachiyomi.animesource.model.Video?): EditotsuInternalData? =
            video?.let { v -> parse(v.internalData)?.copy(resolution = v.resolution) }

        fun parse(raw: String?): EditotsuInternalData? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            fun prim(key: String) = obj[key] as? JsonPrimitive
            fun num(key: String) = prim(key)?.takeIf { !it.isString }
            val version = num("editotsu")?.intOrNull
            if (version != 1) return null
            fun str(key: String): String? =
                prim(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            fun long(key: String): Long? = num(key)?.longOrNull?.takeIf { it >= 0 }
            fun int(key: String): Int? = num(key)?.intOrNull?.takeIf { it >= 0 }
            return EditotsuInternalData(
                torrentName = str("torrentName"),
                fileName = str("fileName"),
                infoHash = str("infoHash"),
                fileIdx = int("fileIdx"),
                sizeBytes = long("sizeBytes"),
                sizeApprox = num("sizeApprox")?.booleanOrNull ?: false,
                seeders = int("seeders"),
                leechers = int("leechers"),
                tracker = str("tracker"),
                anidbEid = int("anidbEid"),
                numFiles = int("numFiles"),
                media = (obj["media"] as? JsonObject)?.let(::media),
                tags = (obj["tags"] as? JsonObject)?.let(::tags),
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun tracks(arr: JsonArray?): List<Track> = arr.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Track(o.str("lang"), o.str("codec") ?: o.str("format"), o.int("channels"), o.bool("forced") ?: false, o.str("title"))
        }

        private fun media(o: JsonObject) = Media(
            fromFile = o.str("from") == "mediainfo",
            codec = o.str("codec"),
            bitDepth = o.int("bitDepth"),
            hdr = o.str("hdr"),
            audio = tracks(o["audio"] as? JsonArray),
            subtitles = tracks(o["subtitles"] as? JsonArray),
            audioKind = o.str("audioKind"),
            multiSubs = o.bool("multiSubs") ?: false,
        )

        private fun tags(o: JsonObject) = Tags(
            group = o.str("group"),
            source = o.str("source"),
            service = o.str("service"),
            revision = o.str("revision"),
            censorship = o.str("censorship"),
        )
    }
}
