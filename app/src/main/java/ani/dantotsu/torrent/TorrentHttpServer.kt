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
    /** Last file prioritised per torrent, so prioritizeFiles runs only on change. */
    private val prioritizedFile = ConcurrentHashMap<String, Int>()
    private val reannounceLimiter = ReannounceLimiter()
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

            // Only the requested file is wanted. Its pieces get NORMAL priority so the
            // stream window (TOP + deadlines) stands out; re-prioritising files on every
            // request made libtorrent rebuild its picker each time, so only do it on change.
            val numFiles = fileStorage.numFiles()
            val fileKey = hash.uppercase()
            if (prioritizedFile[fileKey] != fileIndex) {
                val filePriorities = Priority.array(Priority.IGNORE, numFiles)
                if (fileIndex in 0 until numFiles) filePriorities[fileIndex] = Priority.DEFAULT
                try { synchronized(lock) { if (handle.isValid) handle.prioritizeFiles(filePriorities) } } catch (_: Exception) {}
                prioritizedFile[fileKey] = fileIndex
            }
            val fileSize = fileStorage.fileSize(fileIndex)
            val fileOffset = fileStorage.fileOffset(fileIndex)
            val pieceLen = torrentInfo.pieceLength().toLong()
            val totalPieces = torrentInfo.numPieces()
            val fileFirstPiece = (fileOffset / pieceLen).toInt()
            val fileLastPiece = ((fileOffset + fileSize - 1) / pieceLen).toInt()
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
            // A new GET for the same file supersedes the previous one (mpv reopens on seek).
            val streamKey = Pair(fileKey, fileIndex)
            val oldOwnerId = activeStreamOwners.put(streamKey, clientSessionId)
            if (oldOwnerId != null && oldOwnerId != clientSessionId) {
                deadlineRegistry?.unregisterOwner(oldOwnerId)
            }
            fun isOwner() = activeStreamOwners[streamKey] == clientSessionId
            try { synchronized(lock) { if (handle.isValid) handle.resume() } } catch (_: Exception) {}
            val planner = StreamWindowPlanner(fileFirstPiece, fileLastPiece, pieceLen, protectedPieces = protectedTail)
            fun applyWindow(playhead: Int) {
                if (!isOwner()) return
                val plan = planner.plan(playhead)
                try {
                    synchronized(lock) {
                        if (!handle.isValid) return
                        plan.demote.forEach { handle.piecePriority(it, Priority.DEFAULT) }
                        plan.raise.forEach { (piece, _) -> handle.piecePriority(piece, Priority.TOP_PRIORITY) }
                    }
                } catch (_: Exception) {}
                deadlineRegistry?.focusPieceWindow(
                    clientSessionId, planner.raisedPieces().minOrNull() ?: playhead,
                    planner.windowPieces, totalPieces, protectedTail
                )
                plan.raise.forEach { (piece, deadline) ->
                    deadlineRegistry?.registerDeadline(piece, clientSessionId, deadline.toLong())
                }
            }
            applyWindow(firstPiece)
            // Headers go out at once: mpv waits on the body (network-timeout) instead of
            // getting a 504 after a silent 15 s wait. Bytes stream as pieces arrive.
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 1024 * 1024
            } catch (_: Exception) {}
            val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
            val headers = if (isPartial) {
                "HTTP/1.1 206 Partial Content\r\nContent-Type: $contentType\r\nContent-Length: $contentLength\r\nContent-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\nAccept-Ranges: bytes\r\n\r\n"
            } else {
                "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: $fileSize\r\nAccept-Ranges: bytes\r\n\r\n"
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
                    if (p != firstPiece) applyWindow(p)
                    var waitedMs = 0L
                    var ready = synchronized(lock) { if (handle.isValid) handle.havePiece(p) else false }
                    while (!ready && isRunning && !socket.isClosed) {
                        // Superseded by a newer request (seek) or the player went away:
                        // stop instead of holding deadlines for a position nobody reads.
                        if (!isOwner()) break
                        if (waitedMs >= STALL_GIVE_UP_MS) break
                        if (waitedMs > 0 && waitedMs % CLIENT_CHECK_MS == 0L && clientGone(socket)) break
                        Thread.sleep(PIECE_POLL_MS)
                        waitedMs += PIECE_POLL_MS
                        if (reannounceLimiter.shouldReannounce(fileKey, waitedMs, System.currentTimeMillis())) {
                            try { synchronized(lock) { if (handle.isValid) handle.forceReannounce() } } catch (_: Exception) {}
                        }
                        ready = synchronized(lock) { if (handle.isValid) handle.havePiece(p) else false }
                    }
                    if (!ready) break
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
                if (isOwner()) {
                    val demote = planner.release()
                    try {
                        synchronized(lock) {
                            if (handle.isValid) demote.filter { it !in protectedTail }.forEach {
                                handle.piecePriority(it, Priority.DEFAULT)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            activeSockets.remove(socket)
            if (currentHash != null && currentFileIndex != null) {
                activeStreamOwners.remove(Pair(currentHash.uppercase(), currentFileIndex), clientSessionId)
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

