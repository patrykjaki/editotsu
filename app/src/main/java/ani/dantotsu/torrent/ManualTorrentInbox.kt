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

    /**
     * Claims a staging token for one async file-stage task. The completed
     * copy must be published via [offerStaged], never [offer], so a slow
     * stage cannot overwrite a newer receipt.
     */
    fun beginStage(): Long = holder.beginStage()

    /**
     * Publishes one staged copy iff [token] is still current; a stale
     * completion's file is deleted and `false` returned. Only
     * [ManualTorrentPayload.StagedFile] is accepted.
     */
    fun offerStaged(token: Long, staged: ManualTorrentPayload.StagedFile): Boolean =
        holder.offerStaged(token, staged)

    fun peekLive(): ManualTorrentPayload? = holder.peekLive()

    fun consume(generation: Long): ManualTorrentPayload? = holder.consume(generation)

    fun currentGeneration(): Long = holder.currentGeneration()

    fun clear() = holder.clear()

    /**
     * Copies a content-URI `.torrent` document into private cache, bounded
     * by [ManualTorrentEntry.MAX_TORRENT_FILE_BYTES]. Returns the absolute
     * staged path, or null when the document is missing, empty, oversized,
     * or unreadable. Call off the main thread. Never logs URI contents.
     * Every failure path deletes the partial temp (exactly-once ownership:
     * on success the caller owns the path).
     */
    fun stageContentUri(context: Context, uri: Uri): String? {
        var owned: File? = null
        try {
            val resolver = context.contentResolver
            val tmp = File.createTempFile("manual_", ".torrent", context.cacheDir)
            owned = tmp
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val total = copyBounded(
                        input, output, ManualTorrentEntry.MAX_TORRENT_FILE_BYTES.toLong()
                    ) ?: run {
                        // Over-cap: delete partial and report unusable.
                        runCatching { tmp.delete() }
                        owned = null
                        return null
                    }
                    if (total == 0L) {
                        runCatching { tmp.delete() }
                        owned = null
                        return null
                    }
                }
            } ?: run {
                runCatching { tmp.delete() }
                owned = null
                return null
            }
            owned = null // ownership transfers to the caller on success
            return tmp.absolutePath
        } catch (_: Exception) {
            // Mid-stream I/O failure: no temp may survive here.
            owned?.let { runCatching { it.delete() } }
            return null
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
