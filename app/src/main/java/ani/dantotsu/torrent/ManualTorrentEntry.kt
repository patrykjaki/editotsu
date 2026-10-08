package ani.dantotsu.torrent

/**
 * MAX MERGE M7A: backend-agnostic manual torrent/magnet entry — pure intent,
 * validation, linking and pending-receipt logic.
 *
 * Everything here is platform-free (no Android imports) so the adversarial
 * matrix runs as plain JVM unit tests. Android entry points
 * ([ManualTorrentBottomSheet], `MainActivity.handleViewIntent`) are thin
 * adapters over these predicates:
 *
 * - [MagnetValidation]: strict magnet pre-gate. The engine's own
 *   `TorrentServerManager.parseMagnetHash` remains the runtime authority;
 *   this gate only rejects obvious non-magnets before any engine call.
 * - [ManualTorrentIntentDispatch]: allowlist for incoming VIEW intents —
 *   `magnet:` scheme or (`content:`/`file:` + `application/x-bittorrent`).
 *   Everything else (http(s), SEND, generic octet-stream, null data) is
 *   rejected so Editotsu never hijacks unrelated links. Manifest filters
 *   deliberately omit BROWSABLE for the same reason.
 * - [PendingManualTorrent]: memory-only holder for a torrent received via
 *   system intent while the user has no anime context open. Never
 *   persisted, TTL-bound, generation-guarded; linking to a concrete
 *   (anime, episode) happens only through explicit user action in the
 *   manual sheet, so cross-anime contamination is impossible by
 *   construction. `.torrent` payloads are staged to private cache at
 *   receipt time (content-URI grants may expire) under [MAX_TORRENT_FILE_BYTES].
 * - Linking reuses the existing download-task key
 *   (`AnimeDownloadTask.getTaskName`) and the existing remembered-URL write
 *   inside `AnimeDownloaderService`, so reopen/recover follows the exact
 *   contracts extension-provided torrents already use. Per-season linking
 *   from upstream's parser-as-source is adapted as per-episode task keys —
 *   local downloads have no season abstraction (grouped by title), and no
 *   fake one is invented here.
 */
object ManualTorrentEntry {

    /** Upper bound for a staged `.torrent` file copied from a content URI. */
    const val MAX_TORRENT_FILE_BYTES = 10 * 1024 * 1024

    /** MIME allowlist for incoming torrent-file VIEW intents. */
    const val TORRENT_MIME = "application/x-bittorrent"

    /** Pending-receipt TTL: received torrents expire if never attached. */
    const val PENDING_TTL_MS = 15 * 60 * 1000L
}

/**
 * Strict magnet pre-gate. Returns the normalized info-hash (uppercase hex)
 * or null when [raw] is not a usable magnet link.
 */
object MagnetValidation {

    fun isMagnetLink(raw: String?): Boolean = normalizeInfoHashOrNull(raw) != null

    fun normalizeInfoHashOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        // Case-sensitive scheme, matching the engine's own detection.
        if (!trimmed.startsWith("magnet:")) return null
        val decoded = try {
            java.net.URLDecoder.decode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }
        val btih = decoded.indexOf("xt=urn:btih:", ignoreCase = true)
        if (btih != -1) {
            var hash = decoded.substring(btih + 12).substringBefore("&").trim().uppercase()
            if (hash.length == 32 && hash.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }) {
                hash = base32ToHex(hash) ?: return null
            }
            if (hash.length == 40 && hash.all { it in "0123456789ABCDEF" }) return hash
            return null
        }
        val btmh = decoded.indexOf("xt=urn:btmh:", ignoreCase = true)
        if (btmh != -1) {
            val hash = decoded.substring(btmh + 12).substringBefore("&").trim().uppercase()
            if (hash.isNotEmpty()) return hash
            return null
        }
        return null
    }

    private fun base32ToHex(base32: String): String? {
        return try {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val bytes = mutableListOf<Byte>()
            var bits = 0L
            var bitCount = 0
            for (c in base32) {
                val valIndex = alphabet.indexOf(c)
                if (valIndex == -1) return null
                bits = (bits shl 5) or valIndex.toLong()
                bitCount += 5
                if (bitCount >= 8) {
                    bitCount -= 8
                    bytes.add(((bits shr bitCount) and 0xFF).toByte())
                }
            }
            bytes.joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** A validated inbound torrent payload, ready for engine resolution. */
sealed interface ManualTorrentPayload {
    /** Raw magnet URI text (validated by [MagnetValidation]). */
    data class Magnet(val uri: String) : ManualTorrentPayload

    /**
     * Private-cache staged `.torrent` file. [locator] is the source
     * content/file URI string as received, or the private-cache absolute
     * path after staging via [ManualTorrentInbox.stageContentUri].
     */
    data class StagedFile(val locator: String) : ManualTorrentPayload
}

/**
 * Incoming-intent allowlist. Pure predicate over already-parsed intent
 * fields so the matrix tests it without Android.
 */
object ManualTorrentIntentDispatch {

    fun classify(
        action: String?,
        scheme: String?,
        mimeType: String?,
        dataString: String?,
    ): ManualTorrentPayload? {
        if (action != "android.intent.action.VIEW") return null
        if (dataString.isNullOrBlank()) return null
        if (scheme.equals("magnet", ignoreCase = true)) {
            val uri = dataString.trim()
            return if (MagnetValidation.isMagnetLink(uri)) ManualTorrentPayload.Magnet(uri) else null
        }
        // Torrent files only: content/file scheme + exact torrent MIME.
        // Generic octet-stream is deliberately rejected (too broad).
        if ((scheme == "content" || scheme == "file") && mimeType == ManualTorrentEntry.TORRENT_MIME) {
            return ManualTorrentPayload.StagedFile(dataString)
        }
        return null
    }

    /**
     * Whether a picked document is acceptable as a `.torrent` file without
     * trusting its MIME alone (providers often report octet-stream):
     * exact torrent MIME wins, otherwise the display name must end in
     * `.torrent` (case-insensitive).
     */
    fun isPickableTorrentFile(mimeType: String?, displayName: String?): Boolean {
        if (mimeType == ManualTorrentEntry.TORRENT_MIME) return true
        return displayName?.trim()?.endsWith(".torrent", ignoreCase = true) == true
    }
}

/** One enumerated file inside a resolved torrent. */
data class ManualTorrentFileEntry(
    val index: Int,
    val path: String,
    val length: Long,
) {
    val displayName: String get() = path.substringAfterLast("/").substringAfterLast("\\")
}

/**
 * Default file pick: the largest entry (no hardcoded season/episode
 * heuristics — the user confirms explicitly in the sheet).
 */
fun pickDefaultManualFile(files: List<ManualTorrentFileEntry>): ManualTorrentFileEntry? =
    files.maxByOrNull { it.length }

/**
 * Memory-only pending receipt for system-intent torrents. Generation
 * guarded: stale consumers never resurrect a replaced/expired offer.
 *
 * @param onDrop invoked exactly once when a live offer is discarded
 * without a successful [consume] (replaced, cleared, or TTL-expired) so
 * staged `.torrent` files are deleted deterministically. Successful
 * consumes are owned by the consumer (which deletes after resolution).
 */
class PendingManualTorrent(
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val onDrop: (ManualTorrentPayload) -> Unit = {},
) {
    private var generation = 0L
    private var payload: ManualTorrentPayload? = null
    private var offeredAt = 0L

    @Synchronized
    fun offer(incoming: ManualTorrentPayload): Long {
        payload?.let { onDrop(it) }
        generation += 1
        payload = incoming
        offeredAt = timeProvider()
        return generation
    }

    /** Non-destructive liveness probe for the sheet's pending row. */
    @Synchronized
    fun peekLive(): ManualTorrentPayload? {
        val current = payload ?: return null
        if (timeProvider() - offeredAt > ManualTorrentEntry.PENDING_TTL_MS) {
            payload = null
            onDrop(current)
            return null
        }
        return current
    }

    /** Destructive consume; returns null on stale generation or expiry. */
    @Synchronized
    fun consume(requestedGeneration: Long): ManualTorrentPayload? {
        if (requestedGeneration != generation) return null
        val live = peekLive() ?: return null
        payload = null
        return live
    }

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun clear() {
        payload?.let { onDrop(it) }
        payload = null
    }
}
