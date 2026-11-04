package ani.dantotsu.animethemes

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import ani.dantotsu.Mapper
import ani.dantotsu.defaultHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Audio (.ogg) or video (.webm) copy of a theme. */
enum class ThemeFileKind { AUDIO, VIDEO }

data class ThemeDownloadState(
    val status: Status,
    val percent: Int = 0,
    val uri: Uri? = null,
    val bytes: Long = 0L,
) {
    enum class Status { NONE, RUNNING, SAVED, FAILED }
}

@Serializable
private data class ThemeDownloadRecord(val downloadId: Long, val fileName: String)

/** Pure naming rules, kept apart so they can be tested without Android. */
object ThemeDownloadNames {
    const val FOLDER = "Editotsu/AnimeThemes"

    /** "Bocchi the Rock! - ED 4 v2 - Korogaru Iwa.webm", without characters file systems refuse. */
    fun fileName(track: ThemeTrack, kind: ThemeFileKind): String {
        val tag = if (track.versionCount > 1 && track.tag.isNotBlank()) "${track.tag} v${track.version}" else track.tag
        val parts = listOf(track.showTitle, tag, track.songTitle.takeIf { it != track.tag })
            .map { clean(it.orEmpty()) }
            .filter { it.isNotEmpty() }
        val base = parts.joinToString(" - ").ifEmpty { "theme-${track.themeId}" }.take(120).trim()
        return base + if (kind == ThemeFileKind.AUDIO) ".ogg" else ".webm"
    }

    fun key(track: ThemeTrack, kind: ThemeFileKind): String = "${track.themeId}:${track.entryId}:${kind.name}"

    fun url(track: ThemeTrack, kind: ThemeFileKind): String? =
        if (kind == ThemeFileKind.AUDIO) track.audioUrl else track.url

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.')
}

/**
 * Saved copies of favourite themes in Downloads/Editotsu/AnimeThemes, through the
 * system DownloadManager: the files show in the file manager and can be shared,
 * and they stay after Editotsu is uninstalled. Only the id index lives in app storage.
 */
class ThemeDownloads(context: Context) {
    private val app = context.applicationContext
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val indexFile = File(app.filesDir, "animethemes_downloads.json")
    private val serializer = MapSerializer(String.serializer(), ThemeDownloadRecord.serializer())

    private fun read(): Map<String, ThemeDownloadRecord> = synchronized(LOCK) {
        if (!indexFile.exists()) return emptyMap()
        runCatching { Mapper.json.decodeFromString(serializer, indexFile.readText()) }.getOrDefault(emptyMap())
    }

    private fun write(map: Map<String, ThemeDownloadRecord>) = synchronized(LOCK) {
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(Mapper.json.encodeToString(serializer, map))
        tmp.renameTo(indexFile)
    }

    fun state(track: ThemeTrack, kind: ThemeFileKind): ThemeDownloadState {
        val record = read()[ThemeDownloadNames.key(track, kind)] ?: return ThemeDownloadState(ThemeDownloadState.Status.NONE)
        return query(record.downloadId) ?: ThemeDownloadState(ThemeDownloadState.Status.NONE)
    }

    private fun query(id: Long): ThemeDownloadState? {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = dm.getUriForDownloadedFile(id)
                    // The file may have been deleted in the file manager since.
                    val exists = uri != null && runCatching {
                        app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (exists) ThemeDownloadState(ThemeDownloadState.Status.SAVED, 100, uri, total.coerceAtLeast(done))
                    else null
                }
                DownloadManager.STATUS_FAILED -> ThemeDownloadState(ThemeDownloadState.Status.FAILED)
                else -> ThemeDownloadState(
                    ThemeDownloadState.Status.RUNNING,
                    if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0,
                )
            }
        }
        return null
    }

    /** Starts the download; false when the theme has no file of that kind. */
    fun enqueue(track: ThemeTrack, kind: ThemeFileKind): Boolean {
        val url = ThemeDownloadNames.url(track, kind) ?: return false
        val key = ThemeDownloadNames.key(track, kind)
        read()[key]?.let { old ->
            if (query(old.downloadId)?.status in listOf(ThemeDownloadState.Status.RUNNING, ThemeDownloadState.Status.SAVED)) return true
            dm.remove(old.downloadId)
        }
        val name = ThemeDownloadNames.fileName(track, kind)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${ThemeDownloadNames.FOLDER}/$name")
            .setMimeType(if (kind == ThemeFileKind.AUDIO) "audio/ogg" else "video/webm")
        defaultHeaders["User-Agent"]?.let { request.addRequestHeader("User-Agent", it) }
        val id = dm.enqueue(request)
        write(read() + (key to ThemeDownloadRecord(id, name)))
        return true
    }

    /** Deletes the saved file (or stops the download) of that kind. */
    fun remove(track: ThemeTrack, kind: ThemeFileKind) {
        val key = ThemeDownloadNames.key(track, kind)
        val record = read()[key] ?: return
        dm.remove(record.downloadId)
        write(read() - key)
    }

    /** Saved copy of [track] of [kind], null when there is none. */
    fun savedUri(track: ThemeTrack, kind: ThemeFileKind): Uri? =
        state(track, kind).takeIf { it.status == ThemeDownloadState.Status.SAVED }?.uri

    /** Bytes of every saved theme file. */
    fun totalBytes(): Long = read().values.sumOf { r ->
        query(r.downloadId)?.takeIf { it.status == ThemeDownloadState.Status.SAVED }?.bytes ?: 0L
    }

    private companion object {
        val LOCK = Any()
    }
}
