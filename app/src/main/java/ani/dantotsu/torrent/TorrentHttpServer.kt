package ani.dantotsu.torrent

import ani.dantotsu.util.Logger
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TorrentHttpServer(
    private val port: Int,
    private val getTorrentHandle: (String) -> TorrentHandle?,
    private val getSavePath: (String) -> String,
    private val getTorrentDeadlineRegistry: (String) -> TorrentDeadlineRegistry? = { null },
    private val getTorrentLock: (String) -> Any = { it }
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private val streamSessionCounter = AtomicLong(0L)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeStreamOwners = ConcurrentHashMap<Pair<String, Int>, String>()
    /** Window planner of the current owner per stream, so a superseded owner keeps its pieces. */
    private val activePlanners = ConcurrentHashMap<Pair<String, Int>, StreamWindowPlanner>()
    private val reannounceLimiter = ReannounceLimiter()
    /** Owner request per stream while it waits for a missing piece (see [releaseBlockedRead]). */
    private val blockedOwners = ConcurrentHashMap<Pair<String, Int>, Socket>()
    private val executor = ThreadPoolExecutor(
        8, 32, 60L, TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        ThreadPoolExecutor.AbortPolicy()
    )

    fun start() {
        if (isRunning) return
        isRunning = true
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            Thread({
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        if (!isRunning) {
                            try { client.close() } catch (_: Exception) {}
                            break
                        }
                        client.soTimeout = 15_000
                        activeSockets.add(client)
                        try {
                            executor.execute { handleClient(client) }
                        } catch (_: RejectedExecutionException) {
                            activeSockets.remove(client)
                            send503(client)
                        }
                    } catch (_: Exception) { break }
                }
            }, "TorrentHttpServer-Acceptor").start()
            Logger.log("TorrentHttpServer: Started on 127.0.0.1:$port")
        } catch (e: Exception) {
            Logger.log("TorrentHttpServer: Failed to start: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val clientSessionId = "http_${streamSessionCounter.incrementAndGet()}"
        var currentHash: String? = null
        var currentFileIndex: Int? = null
        var deadlineRegistry: TorrentDeadlineRegistry? = null

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val reqUri = parts[1]

            if (method != "GET" && method != "HEAD") {
                sendError(socket, 405, "Method Not Allowed")
                return
            }

            var rangeHeader: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            val queryStartIndex = reqUri.indexOf('?')
            if (queryStartIndex == -1) {
                sendError(socket, 400, "Bad Request")
                return
            }
            val query = reqUri.substring(queryStartIndex + 1)
            val params = query.split("&").associate {
                val pair = it.split("=")
                (pair.getOrNull(0) ?: "") to (if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else "")
            }

            val hash = params["hash"] ?: params["link"] ?: ""
            val indexStr = params["index"] ?: ""
            if (hash.isEmpty() || indexStr.isEmpty()) {
                sendError(socket, 400, "Missing parameters")
                return
            }

            val fileIndex = indexStr.toIntOrNull() ?: 0
            currentFileIndex = fileIndex
            var handle = getTorrentHandle(hash)
            if (handle == null) {
                var waitHandle = 0
                while (handle == null && waitHandle < 100 && isRunning && !socket.isClosed) {
                    Thread.sleep(50)
                    handle = getTorrentHandle(hash)
                    waitHandle++
                }
            }
            if (handle == null || !handle.isValid) {
                sendError(socket, 404, "Torrent Not Found")
                return
            }

            val lock = getTorrentLock(hash)
            currentHash = hash
            deadlineRegistry = getTorrentDeadlineRegistry(hash)

            var torrentInfo = synchronized(lock) { if (handle.isValid) handle.torrentFile() else null }
            if (torrentInfo == null) {
                var waitMeta = 0
                while (waitMeta < 600 && isRunning && !socket.isClosed) {
                    val currentInfo = synchronized(lock) { if (handle.isValid) handle.torrentFile() else null }
                    if (currentInfo != null) {
                        torrentInfo = currentInfo
                        break
                    }
                    Thread.sleep(50)
                    waitMeta++
                    if (reannounceLimiter.shouldReannounce(hash.uppercase(), waitMeta * 50L, System.currentTimeMillis())) {
                        try { synchronized(lock) { if (handle.isValid) { handle.resume(); handle.forceReannounce() } } } catch (_: Exception) {}
                    }
                }
            }
            if (torrentInfo == null) {
                send504(socket)
                return
            }

            val fileStorage = torrentInfo.files()
            if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) {
                sendError(socket, 404, "File Index Out Of Range")
                return
            }

            // Window-only download: files keep their (non-zero) priority, every piece starts
            // at IGNORE (addTorrent), and only the stream window and the container tail are
            // raised to TOP with deadlines. Nothing else competes for peers' request queues,
            // so the piece the player is waiting on comes first. Seeds stay connected while
            // the window is complete (close_redundant_connections=false).
            val fileKey = hash.uppercase()
            val fileSize = fileStorage.fileSize(fileIndex)
            val fileOffset = fileStorage.fileOffset(fileIndex)
            val pieceLen = torrentInfo.pieceLength().toLong()
            val totalPieces = torrentInfo.numPieces()
            val fileFirstPiece = (fileOffset / pieceLen).toInt()
            val fileLastPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()
            // A file at IGNORE would get its data written to the part file, unreadable here.
            try { synchronized(lock) { if (handle.isValid) ensureFileStreamable(handle, fileIndex, fileFirstPiece, fileLastPiece) } } catch (_: Exception) {}
            // Container tail (MKV Cues / MP4 moov) stays raised for every window.
            val tailStart = maxOf(fileFirstPiece, fileLastPiece - 2)
            val protectedTail = (tailStart..fileLastPiece).filter { it > fileFirstPiece + 3 }.toSet()
            var rangeStart = 0L
            var rangeEnd = fileSize - 1
            var isPartial = false
            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                if (rangeSpec.contains(",")) {
                    send416(socket, fileSize)
                    return
                }
                val dashIdx = rangeSpec.indexOf('-')
                if (dashIdx == -1) {
                    send416(socket, fileSize)
                    return
                }
                val startStr = rangeSpec.substring(0, dashIdx).trim()
                val endStr = rangeSpec.substring(dashIdx + 1).trim()
                if (startStr.isEmpty() && endStr.isEmpty()) {
                    send416(socket, fileSize)
                    return
                }
                if (startStr.isEmpty()) {
                    val suffixLen = endStr.toLongOrNull() ?: run { send416(socket, fileSize); return }
                    if (suffixLen <= 0) { send416(socket, fileSize); return }
                    rangeStart = maxOf(0L, fileSize - suffixLen)
                    rangeEnd = fileSize - 1
                } else if (endStr.isEmpty()) {
                    rangeStart = startStr.toLongOrNull() ?: run { send416(socket, fileSize); return }
                    rangeEnd = fileSize - 1
                } else {
                    rangeStart = startStr.toLongOrNull() ?: run { send416(socket, fileSize); return }
                    rangeEnd = endStr.toLongOrNull() ?: run { send416(socket, fileSize); return }
                }
                if (rangeStart < 0 || rangeStart >= fileSize || rangeEnd < rangeStart || rangeEnd >= fileSize) {
                    send416(socket, fileSize)
                    return
                }
                isPartial = true
            }
            val contentLength = rangeEnd - rangeStart + 1
            val firstPiece = ((fileOffset + rangeStart) / pieceLen).toInt()
            val lastPiece = ((fileOffset + rangeEnd) / pieceLen).toInt()
            val contentType = getMimeType(fileStorage.filePath(fileIndex))
            if (method == "HEAD") {
                val headResp = if (isPartial) {
                    "HTTP/1.1 206 Partial Content\r\nContent-Type: $contentType\r\nContent-Length: $contentLength\r\nContent-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: $fileSize\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                }
                socket.getOutputStream().write(headResp.toByteArray())
                socket.getOutputStream().flush()
                return
            }
            // A new GET for the same file supersedes the previous one (mpv reopens on seek),
            // except a container-tail read (mkv cues / mp4 moov) while another request owns
            // the stream: that is a side read. It raises only its own pieces and never takes
            // ownership, so the head window (and its deadlines) survives the cues probe.
            val streamKey = Pair(fileKey, fileIndex)
            val sideRead = StreamWindowPlanner.isTailSideRead(
                rangeStart = rangeStart,
                fileSize = fileSize,
                firstPiece = firstPiece,
                protectedPieces = protectedTail,
                streamHasOwner = activeStreamOwners[streamKey] != null,
            )
            if (!sideRead) {
                val oldOwnerId = activeStreamOwners.put(streamKey, clientSessionId)
                if (oldOwnerId != null && oldOwnerId != clientSessionId) {
                    deadlineRegistry?.unregisterOwner(oldOwnerId)
                }
            }
            fun isOwner() = !sideRead && activeStreamOwners[streamKey] == clientSessionId
            fun superseded() = !sideRead && activeStreamOwners[streamKey] != clientSessionId
            try { synchronized(lock) { if (handle.isValid) handle.resume() } } catch (_: Exception) {}
            val planner = StreamWindowPlanner(fileFirstPiece, fileLastPiece, pieceLen, protectedPieces = protectedTail)
            val sideRaised = HashSet<Int>()
            fun applySideWindow(playhead: Int) {
                // Tail pieces stay raised for the owner anyway, so a side read never demotes;
                // its deadlines go with unregisterOwner(clientSessionId) when it ends.
                val end = minOf(lastPiece, playhead + StreamWindowPlanner.MIN_PIECES - 1)
                try {
                    synchronized(lock) {
                        if (!handle.isValid) return
                        for (piece in playhead..end) {
                            if (sideRaised.add(piece)) handle.piecePriority(piece, Priority.TOP_PRIORITY)
                        }
                    }
                } catch (_: Exception) {}
                for ((i, piece) in (playhead..end).withIndex()) {
                    deadlineRegistry?.registerDeadline(piece, clientSessionId, StreamWindowPlanner.deadlineFor(i).toLong())
                }
            }
            if (!sideRead) activePlanners[streamKey] = planner
            fun applyWindow(playhead: Int, headFirst: Boolean = false) {
                if (sideRead) {
                    applySideWindow(playhead)
                    return
                }
                if (!isOwner()) return
                val plan = planner.plan(playhead, headFirst)
                try {
                    synchronized(lock) {
                        if (!handle.isValid) return
                        plan.demote.forEach { handle.piecePriority(it, Priority.IGNORE) }
                        plan.raise.forEach { (piece, _) -> handle.piecePriority(piece, Priority.TOP_PRIORITY) }
                    }
                } catch (_: Exception) {}
                deadlineRegistry?.focusPieceWindow(
                    clientSessionId, planner.raisedPieces().minOrNull() ?: playhead,
                    planner.windowPieces, totalPieces, protectedTail
                )
                plan.deadlined(protectedTail).forEach { (piece, deadline) ->
                    deadlineRegistry?.registerDeadline(piece, clientSessionId, deadline.toLong())
                }
            }
            // Head-first: until the read has its first piece, only that piece, the next two
            // and the last tail pieces (mkv cues, read before the first frame) are raised;
            // the full window follows once data flows.
            var firstPieceIn = synchronized(lock) { if (handle.isValid) handle.havePiece(firstPiece) else false }
            applyWindow(firstPiece, headFirst = !firstPieceIn)
            // Headers go out at once: mpv waits on the body (network-timeout) instead of
            // getting a 504 after a silent 15 s wait. Bytes stream as pieces arrive.
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 1024 * 1024
            } catch (_: Exception) {}
            val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            // Connection: close — the relay serves one request per socket. Without it ffmpeg
            // reused the closed socket for the seek back from the cues ("Error reading HTTP
            // response: End of file") and re-probed the file from 0.
            val headers = if (isPartial) {
                "HTTP/1.1 206 Partial Content\r\nContent-Type: $contentType\r\nContent-Length: $contentLength\r\nContent-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
            } else {
                "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: $fileSize\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
            }
            out.write(headers.toByteArray())
            out.flush()
            val savePath = getSavePath(fileKey)
            val diskFile = File(fileStorage.filePath(fileIndex, savePath))
            var fileChannel: RandomAccessFile? = null
            var currentPosition = rangeStart
            var bytesRemaining = contentLength
            val buffer = ByteArray(256 * 1024)
            try {
                for (p in firstPiece..lastPiece) {
                    if (!isRunning || socket.isClosed || bytesRemaining <= 0) break
                    if (p != firstPiece || firstPieceIn) applyWindow(p)
                    var waitedMs = 0L
                    var ready = synchronized(lock) { if (handle.isValid) handle.havePiece(p) else false }
                    if (!ready && isOwner()) blockedOwners[streamKey] = socket
                    while (!ready && isRunning && !socket.isClosed) {
                        // Superseded by a newer request (seek) or the player went away:
                        // stop instead of holding deadlines for a position nobody reads.
                        if (superseded()) break
                        if (waitedMs >= STALL_GIVE_UP_MS) break
                        if (waitedMs > 0 && waitedMs % CLIENT_CHECK_MS == 0L && clientGone(socket)) break
                        Thread.sleep(PIECE_POLL_MS)
                        waitedMs += PIECE_POLL_MS
                        // Re-assert the window while waiting: a prioritizeFiles call for another
                        // file of this torrent resets every piece priority, and this piece would
                        // otherwise sit at IGNORE until the stall give-up.
                        if (waitedMs % REASSERT_MS == 0L) applyWindow(p, headFirst = p == firstPiece && !firstPieceIn)
                        if (reannounceLimiter.shouldReannounce(fileKey, waitedMs, System.currentTimeMillis())) {
                            try { synchronized(lock) { if (handle.isValid) handle.forceReannounce() } } catch (_: Exception) {}
                        }
                        ready = synchronized(lock) { if (handle.isValid) handle.havePiece(p) else false }
                    }
                    blockedOwners.remove(streamKey, socket)
                    if (!ready) break
                    if (p == firstPiece && !firstPieceIn) {
                        firstPieceIn = true
                        applyWindow(p)
                    }
                    val pieceStartOffset = maxOf(rangeStart, p * pieceLen - fileOffset)
                    val pieceEndOffset = minOf(rangeEnd, (p + 1) * pieceLen - 1 - fileOffset)
                    var pieceBytesToRead = pieceEndOffset - pieceStartOffset + 1
                    while (pieceBytesToRead > 0 && bytesRemaining > 0 && isRunning && !socket.isClosed) {
                        val toRead = minOf(buffer.size.toLong(), minOf(pieceBytesToRead, bytesRemaining)).toInt()
                        // Defensive disk read with bounded retry loop
                        var bytesRead = -1
                        var readAttempts = 0
                        while (readAttempts < 100 && isRunning && !socket.isClosed) {
                            try {
                                if (diskFile.exists()) {
                                    if (fileChannel == null) {
                                        fileChannel = RandomAccessFile(diskFile, "r")
                                    }
                                    fileChannel.seek(currentPosition)
                                    bytesRead = fileChannel.read(buffer, 0, toRead)
                                    if (bytesRead > 0) break
                                }
                            } catch (_: Exception) {
                                try { fileChannel?.close() } catch (_: Exception) {}
                                fileChannel = null
                            }
                            Thread.sleep(20)
                            readAttempts++
                        }
                        if (bytesRead <= 0) {
                            Logger.log("TorrentHttpServer: Failed to read from disk at position $currentPosition after $readAttempts attempts")
                            break
                        }
                        out.write(buffer, 0, bytesRead)
                        pieceBytesToRead -= bytesRead
                        bytesRemaining -= bytesRead
                        currentPosition += bytesRead
                    }
                    out.flush()
                }
            } finally {
                try { fileChannel?.close() } catch (_: Exception) {}
                // Drop this request's pieces, except the ones the current owner (a newer
                // request after a seek) also covers. Before, a superseded owner left its
                // whole window at TOP for good, competing with the new position.
                if (!sideRead) {
                    activePlanners.remove(streamKey, planner)
                    val keep = activePlanners[streamKey]?.raisedPieces() ?: emptySet()
                    val demote = planner.release().filter { it !in protectedTail && it !in keep }
                    try {
                        synchronized(lock) {
                            if (handle.isValid) demote.forEach { handle.piecePriority(it, Priority.IGNORE) }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            activeSockets.remove(socket)
            if (currentHash != null && currentFileIndex != null) {
                activeStreamOwners.remove(Pair(currentHash.uppercase(), currentFileIndex), clientSessionId)
                blockedOwners.remove(Pair(currentHash.uppercase(), currentFileIndex), socket)
            }
            deadlineRegistry?.unregisterOwner(clientSessionId)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * True when the client closed its side. mpv sends nothing after the request,
     * so a 1 ms read either times out (still connected) or returns -1 (closed).
     */
    private fun clientGone(socket: Socket): Boolean {
        val previous = try { socket.soTimeout } catch (_: Exception) { return true }
        return try {
            socket.soTimeout = 1
            socket.getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: Exception) {
            true
        } finally {
            try { socket.soTimeout = previous } catch (_: Exception) {}
        }
    }

    private companion object {
        const val PIECE_POLL_MS = 25L
        const val CLIENT_CHECK_MS = 500L
        const val REASSERT_MS = 1_000L
        /** mpv gives up after network-timeout=30; stop well after that. */
        const val STALL_GIVE_UP_MS = 90_000L
    }

    private fun send503(socket: Socket) {
        try {
            socket.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
            socket.close()
        } catch (_: Exception) {}
    }

    private fun send504(socket: Socket) {
        try {
            socket.getOutputStream().write("HTTP/1.1 504 Gateway Timeout\r\nConnection: close\r\n\r\n".toByteArray())
            socket.close()
        } catch (_: Exception) {}
    }

    private fun send416(socket: Socket, fileSize: Long) {
        try {
            socket.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\nConnection: close\r\n\r\n".toByteArray())
            socket.close()
        } catch (_: Exception) {}
    }

    private fun sendError(socket: Socket, code: Int, msg: String) {
        try {
            socket.getOutputStream().write("HTTP/1.1 $code $msg\r\nContent-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg".toByteArray())
            socket.close()
        } catch (_: Exception) {}
    }

    /**
     * The player sought outside its cache: end the stream's request if it is still
     * waiting for a missing piece, so mpv's blocked read returns and the seek opens
     * a new request at once. 100 ms later, so mpv has queued the seek first. A
     * request that is sending data is left alone.
     */
    fun releaseBlockedRead(hash: String, fileIndex: Int) {
        val key = Pair(hash.uppercase(), fileIndex)
        val socket = blockedOwners[key] ?: return
        Thread({
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
            if (blockedOwners.remove(key, socket)) {
                Logger.log("TorrentHttpServer: released a blocked read for a seek")
                try { socket.close() } catch (_: Exception) {}
            }
        }, "TorrentHttpServer-SeekRelease").apply { isDaemon = true }.start()
    }

    /** Torrents with a request being served right now (player stream or download). */
    fun busyHashes(): Set<String> = activeStreamOwners.keys.map { it.first }.toSet()

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
        activeSockets.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        Logger.log("TorrentHttpServer: Stopped and all active sockets closed")
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "srt" -> "text/srt"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

