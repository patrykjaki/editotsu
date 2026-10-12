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
                val savedSubsDir = File(context.filesDir, SAVED_SUBS_DIR).apply { mkdirs() }
                val ext = sourceFile.extension.ifBlank { "srt" }
                val destFile = File(savedSubsDir, stagedSubtitleFileName(mediaId, episodeNumber, sub.id, ext))
                sourceFile.copyTo(destFile, overwrite = true)
                persistentPath = destFile.absolutePath
            }
            // HOLD1 item 3: replacement cleanup — retire whatever this save
            // supersedes, but only canonical owned files (validated below).
            val superseded = staleOwnedFile(
                getSavedSubtitle(mediaId, episodeNumber)?.filePath, persistentPath
            )
            val record = sub.copy(filePath = persistentPath)
            val jsonStr = json.encodeToString(record)
            PrefManager.setCustomVal(getPrefKey(mediaId, episodeNumber), jsonStr)
            if (superseded != null) {
                val ownedDir = File(context.filesDir, SAVED_SUBS_DIR)
                if (isOwnedSubtitleFile(ownedDir, superseded)) {
                    runCatching { File(superseded).delete() }
                }
            }
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
        // HOLD1 item 3: delete only canonical owned files; a tampered or
        // foreign path drops its record without touching the filesystem.
        val ownedDir = File(context.filesDir, SAVED_SUBS_DIR)
        saved?.filePath?.let { path ->
            if (isOwnedSubtitleFile(ownedDir, path)) {
                runCatching { File(path).delete() }
            }
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

/** Winner of per-episode subtitle-source precedence (HOLD1 item 4/5). */
enum class SubtitleSourceWin {
    /** A live newer choice is attached — it always wins. */
    ACTIVE_ONLINE,
    /** A persisted record exists and nothing newer overrides it. */
    SAVED_RECORD,
    /** Legacy `subLang_` / server state decides (unchanged paths). */
    LEGACY,
    /** Explicit Off: null/None language with no selection. */
    OFF,
}

/**
 * Single precedence rule shared by badge, list, highlight and restore
 * (HOLD1 items 1+4): live active beats saved record beats legacy state,
 * and Off requires both no-online and no-legacy selection. Pure, tested.
 */
fun resolveSubtitleSource(
    activeId: String?,
    savedPresent: Boolean,
    savedLang: String?,
    selectedSubtitle: Int?,
): SubtitleSourceWin = when {
    activeId != null -> SubtitleSourceWin.ACTIVE_ONLINE
    savedPresent -> SubtitleSourceWin.SAVED_RECORD
    savedLang == "None" || (savedLang == null && selectedSubtitle == null) ->
        SubtitleSourceWin.OFF
    else -> SubtitleSourceWin.LEGACY
}

/** Canonical app-owned subtitle staging directory name. */
const val SAVED_SUBS_DIR = "saved_online_subs"

/** Allowed subtitle file extensions for staged copies. */
private val STAGED_SUB_EXTS = setOf("srt", "vtt", "ass", "ssa", "ttml")

/**
 * Ownership gate for destructive file operations (HOLD1 item 3): a stored
 * path may be deleted only when it canonicalizes inside [baseDir] AND its
 * filename matches the staged shape `sub_<media>_<ep>_<hash>.<ext>`.
 * Tampered, absolute-escape (`..`), or foreign files are never touched.
 */
fun isOwnedSubtitleFile(baseDir: java.io.File, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return try {
        val base = baseDir.canonicalFile
        val file = java.io.File(path).canonicalFile
        // Canonical prefix blocks `..` escapes, symlinks out, and any
        // foreign absolute path in one comparison.
        if (!file.path.startsWith(base.path + java.io.File.separator)) return false
        val name = file.name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        if (name.substring(dot + 1).lowercase() !in STAGED_SUB_EXTS) return false
        name.substring(0, dot).startsWith("sub_")
    } catch (_: Exception) {
        false
    }
}

/**
 * Replacement cleanup (HOLD1 item 3): returns the previously stored file
 * for deletion when a successful save supersedes it, else null. Caller
 * deletes only via [isOwnedSubtitleFile].
 */
fun staleOwnedFile(
    previousPath: String?,
    replacementPath: String?,
): String? {
    if (previousPath.isNullOrBlank()) return null
    if (previousPath == replacementPath) return null
    return previousPath
}

/** Outcome for one completed async subtitle fetch (HOLD1 item 2). */
enum class PersistDecision {
    /** Current episode, current generation: persist record and attach. */
    PERSIST_AND_ATTACH,
    /** Moved on: persist under the captured episode, do not attach. */
    PERSIST_ONLY,
    /** Superseded choice or dead player: drop entirely. */
    DROP,
}

/**
 * Async persistence ownership (HOLD1 item 2): a completion carries the
 * (generation, media, episode) captured at request start. A newer request
 * (generation mismatch) drops it — a superseded choice never resurrects.
 * An episode move persists under the correct (captured) identity without
 * attaching a stale file to the new player.
 */
fun shouldPersistCompletedRequest(
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedMediaId: Int,
    requestedEpisode: String,
    currentMediaId: Int?,
    currentEpisode: String?,
): PersistDecision {
    if (requestedGeneration != currentGeneration) return PersistDecision.DROP
    if (currentMediaId == null || currentEpisode == null) return PersistDecision.PERSIST_ONLY
    return if (currentMediaId == requestedMediaId && currentEpisode == requestedEpisode) {
        PersistDecision.PERSIST_AND_ATTACH
    } else {
        PersistDecision.PERSIST_ONLY
    }
}

/**
 * Main-thread attach gate (HOLD2 blocker 2): revalidates generation AND
 * episode identity at the actual attach boundary, after the IO decision.
 * A selection that landed between fetch completion and Main dispatch
 * (Off, embedded, newer online) drops the stale attach; an episode move
 * drops it too (its record was already persisted under the right key).
 */
fun shouldAttachCompletedFetch(
    requestedGeneration: Long,
    gateCurrent: Long,
    requestedMediaId: Int,
    requestedEpisode: String,
    currentMediaId: Int?,
    currentEpisode: String?,
): Boolean {
    if (requestedGeneration != gateCurrent) return false
    if (currentMediaId == null || currentEpisode == null) return false
    return currentMediaId == requestedMediaId && currentEpisode == requestedEpisode
}
