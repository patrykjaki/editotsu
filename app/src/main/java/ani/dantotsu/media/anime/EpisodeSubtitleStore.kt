package ani.dantotsu.media.anime

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * LANE-B P1 (#72 EpisodeSubtitleStore, upstream 36d57c56): per-episode
 * persistence for explicitly chosen online subtitles. The record (and an
 * app-private copy of the subtitle file, when the source is available)
 * survives sessions; selection UI prefers the live active subtitle, then
 * this record, then legacy `subLang_` state. Clearing happens only on
 * explicit subtitle disable/switch — never on episode change or playback.
 */
object EpisodeSubtitleStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SavedEpisodeSubtitle(
        val id: String,
        val displayName: String,
        val provider: String,
        val language: String,
        val filePath: String? = null,
        val url: String? = null
    )

    private fun getPrefKey(mediaId: Int, episodeNumber: String): String =
        subtitlePrefKey(mediaId, episodeNumber)

    fun saveSubtitle(
        context: Context,
        mediaId: Int,
        episodeNumber: String,
        sub: SavedEpisodeSubtitle,
        sourceFile: File? = null
    ) {
        try {
            var persistentPath = sub.filePath
            if (sourceFile != null && sourceFile.exists()) {
                val savedSubsDir = File(context.filesDir, "saved_online_subs").apply { mkdirs() }
                val ext = sourceFile.extension.ifBlank { "srt" }
                val destFile = File(savedSubsDir, stagedSubtitleFileName(mediaId, episodeNumber, sub.id, ext))
                sourceFile.copyTo(destFile, overwrite = true)
                persistentPath = destFile.absolutePath
            }
            val record = sub.copy(filePath = persistentPath)
            val jsonStr = json.encodeToString(record)
            PrefManager.setCustomVal(getPrefKey(mediaId, episodeNumber), jsonStr)
        } catch (_: Exception) {
        }
    }

    fun getSavedSubtitle(mediaId: Int, episodeNumber: String): SavedEpisodeSubtitle? {
        val jsonStr: String = PrefManager.getNullableCustomVal(getPrefKey(mediaId, episodeNumber), null, String::class.java)
            ?: return null
        return decodeSavedSubtitle(jsonStr)
    }

    fun clearSavedSubtitle(context: Context, mediaId: Int, episodeNumber: String) {
        val saved = getSavedSubtitle(mediaId, episodeNumber)
        if (saved?.filePath != null) {
            runCatching { File(saved.filePath).delete() }
        }
        PrefManager.setCustomVal(getPrefKey(mediaId, episodeNumber), null)
    }
}

/**
 * LANE-B pure seams (JVM-tested): key/filename shaping and tolerant
 * decode for the per-episode subtitle store. Android-coupled persistence
 * (prefs/filesDir) stays in [EpisodeSubtitleStore] and is covered by
 * compile + device validation instead.
 */
internal fun subtitlePrefKey(mediaId: Int, episodeNumber: String): String =
    "saved_ep_online_sub_${mediaId}_$episodeNumber"

internal fun stagedSubtitleFileName(
    mediaId: Int,
    episodeNumber: String,
    subId: String,
    ext: String,
): String = "sub_${mediaId}_${episodeNumber}_${subId.hashCode()}.$ext"

private val subtitleStoreJson = Json { ignoreUnknownKeys = true }

internal fun decodeSavedSubtitle(
    jsonStr: String,
): EpisodeSubtitleStore.SavedEpisodeSubtitle? = try {
    subtitleStoreJson.decodeFromString<EpisodeSubtitleStore.SavedEpisodeSubtitle>(jsonStr)
} catch (_: Exception) {
    null
}
