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
     * Borrowed third-party URI string (`content:`/`file:`) exactly as
     * received. NEVER owned: must be staged into private cache before use,
     * and MUST NEVER be passed to file deletion. The holder rejects it
     * structurally ([PendingManualTorrent.offer] throws).
     */
    data class ReceivedFile(val uriString: String) : ManualTorrentPayload

    /**
     * App-owned private-cache `.torrent` copy (absolute path) produced by
     * bounded staging. The ONLY payload type the cleanup paths may delete.
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
        // Generic octet-stream is deliberately rejected (too broad). The
        // result is BORROWED ([ManualTorrentPayload.ReceivedFile]) — staging
        // into an app-owned copy happens before any offer or resolve.
        if ((scheme == "content" || scheme == "file") && mimeType == ManualTorrentEntry.TORRENT_MIME) {
            return ManualTorrentPayload.ReceivedFile(dataString)
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

    /**
     * Async staging generation. File receipts call this at receipt START;
     * slow completions offer via [offerStaged], which only wins when no
     * newer receipt (magnet offer or newer stage) has since arrived.
     */
    private var stagingGeneration = 0L

    /**
     * Offers a receipt. Only [ManualTorrentPayload.Magnet] and
     * [ManualTorrentPayload.StagedFile] are accepted — a borrowed
     * [ManualTorrentPayload.ReceivedFile] throws, so an original
     * third-party file can never enter cleanup-owning state. Offering
     * also invalidates older in-flight stages (see [offerStaged]).
     */
    @Synchronized
    fun offer(incoming: ManualTorrentPayload): Long {
        require(incoming !is ManualTorrentPayload.ReceivedFile) {
            "borrowed file URI must be staged into private cache before offer"
        }
        stagingGeneration += 1
        return offerLocked(incoming)
    }

    /** Claims a staging token for one async file-stage task. */
    @Synchronized
    fun beginStage(): Long {
        stagingGeneration += 1
        return stagingGeneration
    }

    /**
     * Completes one async stage. Returns true and offers only when [token]
     * is still the latest staging generation; a stale completion is
     * disposed via [onDrop] (deleting its app-owned copy) and returns
     * false, so a newer receipt is never overwritten by obsolete work.
     */
    @Synchronized
    fun offerStaged(token: Long, staged: ManualTorrentPayload.StagedFile): Boolean {
        if (token != stagingGeneration) {
            onDrop(staged)
            return false
        }
        offerLocked(staged)
        return true
    }

    private fun offerLocked(incoming: ManualTorrentPayload): Long {
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
        // HOLD2: clearing must also invalidate in-flight stages, or a late
        // stage completion would resurrect an entry after clear.
        stagingGeneration += 1
        payload?.let { onDrop(it) }
        payload = null
    }
}

/**
 * Bounded stream copy core used by `.torrent` staging. Pure JVM seam:
 * copies at most [capBytes] (+1 probe byte to detect overflow).
 *
 * @return bytes copied, or null when the input exceeds the cap. Partial
 * output on over-cap/throw is the CALLER's to delete (see
 * [ManualTorrentInbox.stageContentUri]).
 * @throws java.io.IOException on mid-stream read/write failure.
 */
fun copyBounded(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    capBytes: Long,
): Long? {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        total += read
        if (total > capBytes) return null
        output.write(buf, 0, read)
    }
    return total
}

/**
 * Attaches a manually entered torrent file to an episode exactly the way
 * the player consumes it (`ExoplayerView`: extractor by
 * `selectedExtractor`, video by `selectedVideo`, URL from the video).
 *
 * Replaces any same-named manual extractor, selects the single video, and
 * returns the player-consumed [ani.dantotsu.parsers.Video]. Pure seam over
 * the real model classes — the JVM matrix asserts selected index → link →
 * consumed video, and that unrelated episodes are untouched.
 */
fun attachManualVideo(
    episode: ani.dantotsu.media.anime.Episode,
    serverName: String,
    rawUrl: String,
    link: String,
): ani.dantotsu.parsers.Video {
    val list = episode.extractors ?: mutableListOf<ani.dantotsu.parsers.VideoExtractor>()
        .also { episode.extractors = it }
    list.removeAll { it.server.name == serverName }
    val video = ani.dantotsu.parsers.Video(null, ani.dantotsu.parsers.VideoType.CONTAINER, rawUrl)
    video.file.url = link
    list.add(ManualTorrentExtractor(serverName, listOf(video)))
    episode.selectedExtractor = serverName
    episode.selectedVideo = 0
    return video
}

/** Outcome of one staged-copy publication attempt. */
enum class StagedPublishResult {
    /** Copy is now owned by the holder. Caller must NOT delete it. */
    PUBLISHED,
    /** Token stale: [PendingManualTorrent.offerStaged] disposed the copy. */
    STALE_CLEANED,
    /** Nothing was staged (failure upstream); nothing to publish. */
    NOTHING_STAGED,
}

/**
 * Publishes one completed stage copy into the holder (HOLD2-A/C).
 * Returns [StagedPublishResult.PUBLISHED] only when the token is current;
 * a stale token's copy is disposed by [offerStaged]. The caller pairs this
 * with a `finally` that deletes [staged] unless the result is PUBLISHED —
 * that covers coroutine cancellation between copy completion and Main
 * publication without swallowing `CancellationException`.
 */
fun publishStagedReceipt(
    token: Long,
    staged: String?,
    offerStaged: (Long, ManualTorrentPayload.StagedFile) -> Boolean,
): StagedPublishResult {
    if (staged == null) return StagedPublishResult.NOTHING_STAGED
    return if (offerStaged(token, ManualTorrentPayload.StagedFile(staged))) {
        StagedPublishResult.PUBLISHED
    } else {
        StagedPublishResult.STALE_CLEANED
    }
}

/** Resolved ownership after one successful resolution transition. */
data class OwnershipTransition(
    /** Pointer the sheet must retain (null when the new resolution stages nothing). */
    val retained: String?,
    /** Previously owned temps to delete exactly once each. */
    val deleted: List<String>,
)

/**
 * Settles temp ownership on EVERY successful resolution (HOLD3): the new
 * resolution supersedes both the previously resolved staged file AND any
 * still-owned retry file from a failed attempt ([ownedTempPath]). Same-path
 * transfer deletes nothing; a failed new resolution never reaches here, so
 * the previous resolved file and retry file stay intact. Deletion happens
 * only after the new `addTorrent` returned, never while it reads.
 */
fun settleOwnership(
    staged: String?,
    owned: String?,
    current: String?,
): OwnershipTransition {
    val deleted = mutableListOf<String>()
    listOf(staged, owned).forEach { prev ->
        if (prev != null && prev != current && prev !in deleted) {
            deleted.add(prev)
        }
    }
    return OwnershipTransition(current, deleted)
}
