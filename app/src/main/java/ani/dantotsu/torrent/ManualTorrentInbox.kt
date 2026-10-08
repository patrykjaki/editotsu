package ani.dantotsu.torrent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * MAX MERGE M7A: system-intent receipt point for manual torrents.
 *
 * Memory-only and TTL-bound (see [PendingManualTorrent]): a received
 * torrent is offered here by `MainActivity.handleViewIntent` and attached
 * to a concrete (anime, episode) only through explicit user action in
 * [ManualTorrentBottomSheet]. Staged `.torrent` files are deleted
 * deterministically — on replace/clear/expiry, or by the sheet after a
 * successful resolve — so no orphaned cache files accumulate.
 */
object ManualTorrentInbox {

    private val holder = PendingManualTorrent(
        timeProvider = { System.currentTimeMillis() },
        onDrop = { payload ->
            (payload as? ManualTorrentPayload.StagedFile)
                ?.locator?.let { runCatching { File(it).delete() } }
        }
    )

    fun offer(payload: ManualTorrentPayload): Long = holder.offer(payload)

    fun peekLive(): ManualTorrentPayload? = holder.peekLive()

    fun consume(generation: Long): ManualTorrentPayload? = holder.consume(generation)

    fun currentGeneration(): Long = holder.currentGeneration()

    fun clear() = holder.clear()

    /**
     * Copies a content-URI `.torrent` document into private cache, bounded
     * by [ManualTorrentEntry.MAX_TORRENT_FILE_BYTES]. Returns the absolute
     * staged path, or null when the document is missing, oversized, or
     * unreadable. Call off the main thread. Never logs URI contents.
     */
    fun stageContentUri(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val out = File.createTempFile("manual_", ".torrent", context.cacheDir)
            var total = 0L
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > ManualTorrentEntry.MAX_TORRENT_FILE_BYTES) {
                            runCatching { out.delete() }
                            return null
                        }
                        output.write(buf, 0, read)
                    }
                }
            } ?: run {
                runCatching { out.delete() }
                return null
            }
            if (total == 0L) {
                runCatching { out.delete() }
                return null
            }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Display name of a document URI, for the picker allowlist. */
    fun displayNameOf(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
