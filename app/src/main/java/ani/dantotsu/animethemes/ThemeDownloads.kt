package ani.dantotsu.animethemes

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ani.dantotsu.Mapper
import ani.dantotsu.defaultHeaders
import ani.dantotsu.util.Logger
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

/**
 * One saved theme file: a DownloadManager download, or (when [downloadId] is -1) a file
 * found in the folder after the index was lost, e.g. after a reinstall.
 */
@Serializable
internal data class ThemeDownloadRecord(val downloadId: Long = -1L, val fileName: String, val uri: String? = null)

/** Index lookups, kept pure for tests. */
internal object ThemeDownloadIndex {
    /** Index key of a file found in the folder, by file name. */
    fun fileKey(name: String) = "file:$name"

    /** Records for files found in the folder; only theme files count. */
    fun fromFolder(found: Map<String, String>): Map<String, ThemeDownloadRecord> =
        found.filterKeys { it.endsWith(".webm") || it.endsWith(".ogg") }
            .map { (name, uri) -> fileKey(name) to ThemeDownloadRecord(fileName = name, uri = uri) }
            .toMap()
}

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

    /** How many of [tracks] have no file of [kind]; "download all" skips them. */
    fun missingFiles(tracks: List<ThemeTrack>, kind: ThemeFileKind): Int = tracks.count { url(it, kind) == null }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ").trim().trimEnd('.')
}

/**
 * Saved copies of favourite themes in Downloads/Editotsu/AnimeThemes, through the
 * system DownloadManager: the files show in the file manager and can be shared,
 * and they stay after Editotsu is uninstalled. Only the id index lives in app storage;
 * when it is missing, it is rebuilt from the files in the folder.
 */
class ThemeDownloads(context: Context) {
    private val app = context.applicationContext
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val indexFile = File(app.filesDir, "animethemes_downloads.json")
    private val serializer = MapSerializer(String.serializer(), ThemeDownloadRecord.serializer())

    private fun read(): Map<String, ThemeDownloadRecord> = synchronized(LOCK) {
        if (!indexFile.exists()) {
            // The files outlive the app; without the index a new download would make "name (1).webm".
            val rebuilt = ThemeDownloadIndex.fromFolder(scanFolder())
            write(rebuilt)
            return rebuilt
        }
        runCatching { Mapper.json.decodeFromString(serializer, indexFile.readText()) }.getOrDefault(emptyMap())
    }

    /**
     * File name to uri of what is in the themes folder and visible to this app. On Android 10+
     * files saved by an earlier install are only visible while storage access allows it.
     */
    private fun scanFolder(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            runCatching {
                app.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${ThemeDownloadNames.FOLDER}/"),
                    null,
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        found[c.getString(name)] = ContentUris.withAppendedId(collection, c.getLong(id)).toString()
                    }
                }
            }.onFailure { Logger.log("ThemeDownloads: folder query failed: ${it.message}") }
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ThemeDownloadNames.FOLDER)
        runCatching { dir.listFiles() }.getOrNull()?.forEach { f ->
            if (f.isFile && f.name !in found) found[f.name] = Uri.fromFile(f).toString()
        }
        return found
    }

    private fun record(track: ThemeTrack, kind: ThemeFileKind): Pair<String, ThemeDownloadRecord>? {
        val index = read()
        val key = ThemeDownloadNames.key(track, kind)
        index[key]?.let { return key to it }
        val fileKey = ThemeDownloadIndex.fileKey(ThemeDownloadNames.fileName(track, kind))
        return index[fileKey]?.let { fileKey to it }
    }

    private fun stateOf(record: ThemeDownloadRecord): ThemeDownloadState? {
        if (record.downloadId >= 0) return query(record.downloadId)
        val uri = record.uri?.let(Uri::parse) ?: return null
        val size = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: return null
        return ThemeDownloadState(ThemeDownloadState.Status.SAVED, 100, uri, size.coerceAtLeast(0L))
    }

    private fun write(map: Map<String, ThemeDownloadRecord>) = synchronized(LOCK) {
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(Mapper.json.encodeToString(serializer, map))
        tmp.renameTo(indexFile)
    }

    fun state(track: ThemeTrack, kind: ThemeFileKind): ThemeDownloadState {
        val (_, record) = record(track, kind) ?: return ThemeDownloadState(ThemeDownloadState.Status.NONE)
        return stateOf(record) ?: ThemeDownloadState(ThemeDownloadState.Status.NONE)
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
        record(track, kind)?.let { (oldKey, old) ->
            if (stateOf(old)?.status in listOf(ThemeDownloadState.Status.RUNNING, ThemeDownloadState.Status.SAVED)) return true
            if (old.downloadId >= 0) dm.remove(old.downloadId)
            write(read() - oldKey)
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
        val (key, record) = record(track, kind) ?: return
        if (record.downloadId >= 0) {
            dm.remove(record.downloadId)
        } else {
            record.uri?.let { uri ->
                runCatching {
                    val parsed = Uri.parse(uri)
                    if (parsed.scheme == "file") File(parsed.path.orEmpty()).delete()
                    else app.contentResolver.delete(parsed, null, null)
                }.onFailure { Logger.log("ThemeDownloads: could not delete ${record.fileName}: ${it.message}") }
            }
        }
        write(read() - key)
    }

    /** Saved copy of [track] of [kind], null when there is none. */
    fun savedUri(track: ThemeTrack, kind: ThemeFileKind): Uri? =
        state(track, kind).takeIf { it.status == ThemeDownloadState.Status.SAVED }?.uri

    /** Bytes of every saved theme file. */
    fun totalBytes(): Long = read().values.sumOf { r ->
        stateOf(r)?.takeIf { it.status == ThemeDownloadState.Status.SAVED }?.bytes ?: 0L
    }

    private companion object {
        val LOCK = Any()
    }
}
