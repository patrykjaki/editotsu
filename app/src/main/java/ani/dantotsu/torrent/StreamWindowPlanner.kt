package ani.dantotsu.torrent

/**
 * Piece window for one streaming owner (pure, JVM-tested).
 *
 * Fast torrent streaming (TorrServer, Stremio, WebTorrent) keeps a short,
 * strictly ordered window ahead of the read position and moves it when the
 * player reads or seeks. This class decides that window; the relay applies
 * the result to libtorrent.
 *
 * - The window covers [windowBytes] ahead of the playhead (at least
 *   [MIN_PIECES], at most [MAX_PIECES]) and stops at the file's last piece.
 * - Deadlines rise with distance, so libtorrent's time-critical picker asks
 *   for the nearest piece first.
 * - Protected pieces (container tail: mkv cues / mp4 moov) stay raised.
 * - Moving the window demotes every piece it raised before and no longer
 *   covers, so earlier positions stop competing with the current one.
 */
class StreamWindowPlanner(
    private val fileFirstPiece: Int,
    private val fileLastPiece: Int,
    pieceLength: Long,
    windowBytes: Long = DEFAULT_WINDOW_BYTES,
    val protectedPieces: Set<Int> = emptySet(),
) {
    data class Plan(
        /** Pieces to raise, nearest first, with the deadline (ms) for each. */
        val raise: List<Pair<Int, Int>>,
        /** Pieces raised earlier that the window no longer covers. */
        val demote: List<Int>,
    ) {
        /**
         * The pieces that get a libtorrent deadline: the nearest [CRITICAL_PIECES] and the
         * protected tail. libtorrent serves deadline pieces through its time-critical path
         * (mostly its fastest peers); the rest of the window stays TOP priority without a
         * deadline so the normal picker spreads it over every peer, like the read-ahead of
         * WebTorrent and anacrolix/torrent (TorrServer pulled 2x our sustained rate).
         */
        fun deadlined(protectedPieces: Set<Int>): List<Pair<Int, Int>> =
            raise.filterIndexed { i, (piece, _) -> i < CRITICAL_PIECES || piece in protectedPieces }
    }

    /**
     * Protected pieces also raised in head-first mode: the last ones covering about
     * [HEAD_FIRST_TAIL_BYTES] (+1 for a short last piece). mpv reads the mkv cues right
     * after the head and waits for them before the first frame; fetched only after the
     * head, they cost a second serial wait (5.6 s on the owner's phone, r10).
     */
    private val headFirstTail: List<Int> = run {
        val n = if (pieceLength > 0) ((HEAD_FIRST_TAIL_BYTES + pieceLength - 1) / pieceLength).toInt() + 1 else 1
        protectedPieces.filter { it in fileFirstPiece..fileLastPiece }.sortedDescending().take(n).sorted()
    }

    val windowPieces: Int = run {
        val byBytes = if (pieceLength > 0) ((windowBytes + pieceLength - 1) / pieceLength).toInt() else MIN_PIECES
        byBytes.coerceIn(MIN_PIECES, MAX_PIECES)
    }

    private val raised = LinkedHashSet<Int>()

    /** Currently raised pieces (other relay threads read this; hence synchronized). */
    @Synchronized
    fun raisedPieces(): Set<Int> = raised.toSet()

    /**
     * Window for a read position at [playheadPiece] (clamped into the file).
     *
     * [headFirst]: the read is still waiting for its first piece. Peers unchoke us slowly
     * and the first seconds bring only a few hundred KB/s, so only the playhead piece, the
     * next [HEAD_FIRST_PIECES] - 1 and the last tail pieces (the container index, deadline
     * right after the head) are raised until it lands; the full window follows.
     */
    @Synchronized
    fun plan(playheadPiece: Int, headFirst: Boolean = false): Plan {
        val start = playheadPiece.coerceIn(fileFirstPiece, fileLastPiece)
        val span = if (headFirst) minOf(HEAD_FIRST_PIECES, windowPieces) else windowPieces
        val end = minOf(start + span - 1, fileLastPiece)
        val raise = ArrayList<Pair<Int, Int>>(end - start + 1 + protectedPieces.size)
        for ((i, piece) in (start..end).withIndex()) {
            raise += piece to deadlineFor(i)
        }
        if (headFirst) {
            for (piece in headFirstTail) {
                if (piece !in start..end) raise += piece to deadlineFor(span)
            }
        } else for (piece in protectedPieces) {
            if (piece in fileFirstPiece..fileLastPiece && piece !in start..end) {
                raise += piece to PROTECTED_DEADLINE_MS
            }
        }
        val now = raise.mapTo(HashSet()) { it.first }
        val demote = raised.filter { it !in now }
        raised.clear()
        raised.addAll(now)
        return Plan(raise, demote)
    }

    /** Forget all raised pieces (owner released); returns the pieces to demote. */
    @Synchronized
    fun release(): List<Int> {
        val out = raised.toList()
        raised.clear()
        return out
    }

    companion object {
        /** Reads starting this close to the end of the file count as container-tail reads. */
        const val TAIL_SIDE_READ_BYTES = 8L * 1024 * 1024

        /**
         * True when a GET is a side read: it starts in the container tail (the protected
         * tail pieces or the last [TAIL_SIDE_READ_BYTES]) while another request already
         * owns the stream. mpv/ffmpeg probes the mkv cues / mp4 moov this way right after
         * reading the head and then seeks back; the probe must not take over the window.
         * Without an owner, a tail read is an ordinary read (somebody has to own the stream).
         */
        fun isTailSideRead(
            rangeStart: Long,
            fileSize: Long,
            firstPiece: Int,
            protectedPieces: Set<Int>,
            streamHasOwner: Boolean,
        ): Boolean {
            if (!streamHasOwner || fileSize <= 0) return false
            if (firstPiece in protectedPieces) return true
            return rangeStart >= fileSize - TAIL_SIDE_READ_BYTES && rangeStart > 0
        }

        /** Nearest window pieces that get a deadline (see [Plan.deadlined]). */
        const val CRITICAL_PIECES = 8

        /** Pieces raised while a read waits for its first piece. */
        const val HEAD_FIRST_PIECES = 3

        /** Bytes at the end of the file fetched with the head (mkv cues are usually smaller). */
        const val HEAD_FIRST_TAIL_BYTES = 1L * 1024 * 1024

        const val MIN_PIECES = 8
        const val MAX_PIECES = 128
        /**
         * ~64 MB ahead (about a minute of 1080p). The window is the only part of the file
         * that downloads, so it is also the read-ahead buffer beyond mpv's cache.
         */
        const val DEFAULT_WINDOW_BYTES = 64L * 1024 * 1024
        const val PROTECTED_DEADLINE_MS = 1_500

        /** 0 ms for the playhead piece, then 120 ms steps for the next 8, then 250 ms steps. */
        fun deadlineFor(distance: Int): Int = when {
            distance <= 0 -> 0
            distance <= 8 -> distance * 120
            else -> 8 * 120 + (distance - 8) * 250
        }
    }
}

/**
 * Rate limit for forced tracker re-announces (pure). Trackers throttle or ban
 * clients that re-announce every few seconds; ask at most once per
 * [minIntervalMs] per torrent, and only after the read has been stalled for
 * [stallBeforeMs].
 */
class ReannounceLimiter(
    private val minIntervalMs: Long = 30_000,
    private val stallBeforeMs: Long = 5_000,
) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun shouldReannounce(hash: String, stalledForMs: Long, nowMs: Long): Boolean {
        if (stalledForMs < stallBeforeMs) return false
        val prev = last[hash]
        if (prev != null && nowMs - prev < minIntervalMs) return false
        last[hash] = nowMs
        return true
    }
}
