package ani.dantotsu.torrent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.data.torrentServer.model.FileStat
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import org.libtorrent4j.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class StreamingPiecePlan(
    val startupPieces: List<Int>,
    val opportunisticTail: Int?
)

fun computeStreamingPiecePlan(
    fileOffset: Long,
    fileSize: Long,
    pieceLength: Long,
    totalPieces: Int
): StreamingPiecePlan {
    if (fileOffset < 0 || fileSize <= 0 || pieceLength <= 0 || totalPieces <= 0) {
        return StreamingPiecePlan(emptyList(), null)
    }
    val firstPiece = (fileOffset / pieceLength).toInt()
    val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

    if (firstPiece < 0 || firstPiece >= totalPieces || lastPiece < 0 || lastPiece >= totalPieces || firstPiece > lastPiece) {
        return StreamingPiecePlan(emptyList(), null)
    }

    val candidates = listOf(firstPiece, firstPiece + 1, firstPiece + 2, firstPiece + 3)
    val boundedStartup = candidates.filter { it <= lastPiece && it < totalPieces }.distinct()
    val tail = if (lastPiece !in boundedStartup) lastPiece else null

    return StreamingPiecePlan(boundedStartup, tail)
}

interface TorrentDeadlineAdapter {
    val isValid: Boolean
    fun setPieceDeadline(index: Int, deadline: Int)
    fun resetPieceDeadline(index: Int)
}

class LibtorrentDeadlineAdapter(private val handle: TorrentHandle) : TorrentDeadlineAdapter {
    override val isValid: Boolean get() = handle.isValid
    override fun setPieceDeadline(index: Int, deadline: Int) {
        handle.setPieceDeadline(index, deadline)
    }
    override fun resetPieceDeadline(index: Int) {
        handle.resetPieceDeadline(index)
    }
}

class TorrentDeadlineRegistry(
    val torrentHash: String,
    private val adapter: TorrentDeadlineAdapter,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    constructor(torrentHash: String, handle: TorrentHandle) : this(torrentHash, LibtorrentDeadlineAdapter(handle))

    private val pieceDeadlines = mutableMapOf<Int, MutableMap<String, Long>>()
    private var isDisposed = false

    fun registerDeadline(pieceIndex: Int, ownerId: String, delayMs: Long) {
        synchronized(this) {
            if (isDisposed || !adapter.isValid) return
            val map = pieceDeadlines.getOrPut(pieceIndex) { mutableMapOf() }
            val targetAbsolute = timeProvider() + delayMs
            map[ownerId] = targetAbsolute
            updatePieceDeadlineLocked(pieceIndex, map)
        }
    }

    fun focusPieceWindow(ownerId: String, startPiece: Int, count: Int, totalPieces: Int) {
        synchronized(this) {
            if (isDisposed || !adapter.isValid) return
            val windowEnd = minOf(startPiece + count, totalPieces)
            val iterator = pieceDeadlines.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val piece = entry.key
                if (piece < startPiece || piece >= windowEnd) {
                    val map = entry.value
                    if (map.remove(ownerId) != null) {
                        if (map.isEmpty()) {
                            iterator.remove()
                            try {
                                adapter.resetPieceDeadline(piece)
                            } catch (_: Exception) {}
                        } else {
                            updatePieceDeadlineLocked(piece, map)
                        }
                    }
                }
            }
        }
    }

    fun unregisterOwner(ownerId: String) {
        synchronized(this) {
            if (isDisposed || !adapter.isValid) return
            val iterator = pieceDeadlines.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val pieceIndex = entry.key
                val map = entry.value
                if (map.remove(ownerId) != null) {
                    if (map.isEmpty()) {
                        iterator.remove()
                        try {
                            adapter.resetPieceDeadline(pieceIndex)
                        } catch (_: Exception) {}
                    } else {
                        updatePieceDeadlineLocked(pieceIndex, map)
                    }
                }
            }
        }
    }

    private fun updatePieceDeadlineLocked(pieceIndex: Int, map: Map<String, Long>) {
        val minTarget = map.values.minOrNull() ?: return
        val remainingMs = (minTarget - timeProvider()).coerceIn(0L, Int.MAX_VALUE.toLong())
        try {
            adapter.setPieceDeadline(pieceIndex, remainingMs.toInt())
        } catch (_: Exception) {}
    }

    fun dispose() {
        synchronized(this) {
            if (isDisposed) return
            isDisposed = true
            pieceDeadlines.keys.forEach { piece ->
                try {
                    if (adapter.isValid) {
                        adapter.resetPieceDeadline(piece)
                    }
                } catch (_: Exception) {}
            }
            pieceDeadlines.clear()
        }
    }
}


class PrebufferLease(
    val sessionId: String,
    val torrentHash: String,
    val fileIndex: Int,
    private val onClose: (PrebufferLease) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onClose(this)
        }
    }
}

data class PrebufferResult(
    val ready: Boolean,
    val lease: PrebufferLease?
)

data class PrebufferProgress(
    val downloadRateBytes: Long = 0L,
    val numPeers: Int = 0,
    val numSeeds: Int = 0,
    val piecesReady: Int = 0,
    val totalPieces: Int = 0,
    val progressPercent: Float = 0f,
    val stateDescription: String = ""
)

class TorrentServerManager(private val context: Context) {
    private val sessionManager by lazy { SessionManager() }
    private var httpServer: TorrentHttpServer? = null
    var activeTorrentHash: String? = null
    var serverPort: Int = 8090
        private set

    private val registries = ConcurrentHashMap<String, TorrentDeadlineRegistry>()
    private val activePrebufferLeases = ConcurrentHashMap<Pair<String, Int>, PrebufferLease>()
    private val scheduledTtls = ConcurrentHashMap<String, Runnable>()
    private val ttlHandler = Handler(Looper.getMainLooper())
    private val leaseCounter = AtomicLong(0L)

    fun start() {
        if (sessionManager.isRunning) return
        Logger.log("Starting built-in TorrentServerManager...")
        try {
            val settings = SettingsPack()
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            settings.setString(
                org.libtorrent4j.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401,dht.aelitis.com:6881"
            )
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_downloads.swigValue(), 20)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_seeds.swigValue(), 20)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_limit.swigValue(), 100)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), 300)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.unchoke_slots_limit.swigValue(), 64)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 2000)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.torrent_connect_boost.swigValue(), 50)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_peerlist_size.swigValue(), 1000)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_paused_peerlist_size.swigValue(), 500)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.request_timeout.swigValue(), 5)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_connect_timeout.swigValue(), 5)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.aio_threads.swigValue(), 2)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.hashing_threads.swigValue(), 1)

            // Disable UDP (uTP) if configured
            val disableUtp = PrefManager.getVal<Boolean>(PrefName.TorrentDisableUtp)
            if (disableUtp) {
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
            } else {
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
                settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            }

            // Strict Encryption Mode
            val encryption = PrefManager.getVal<Boolean>(PrefName.TorrentEncryption)
            if (encryption) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_forced.swigValue())
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_forced.swigValue())
            } else {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_enabled.swigValue())
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), org.libtorrent4j.swig.settings_pack.enc_policy.pe_enabled.swigValue())
            }

            // WiFi Only
            val wifiOnly = PrefManager.getVal<Boolean>(PrefName.TorrentWifiOnly)
            if (wifiOnly) {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.outgoing_interfaces.swigValue(), "wlan0")
            }

            // Download/Upload Limits (KB/s to B/s)
            val downloadSpeedLimit = PrefManager.getVal<Int>(PrefName.TorrentDownloadSpeedLimit)
            if (downloadSpeedLimit > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.download_rate_limit.swigValue(), downloadSpeedLimit * 1024)
            }
            val uploadSpeedLimit = PrefManager.getVal<Int>(PrefName.TorrentUploadSpeedLimit)
            if (uploadSpeedLimit > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.upload_rate_limit.swigValue(), uploadSpeedLimit * 1024)
            }

            // Connection Limit
            val maxConnections = PrefManager.getVal<Int>(PrefName.TorrentMaxConnections)
            if (maxConnections > 0) {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), maxConnections)
            }

            // Port configuration
            val customPort = PrefManager.getVal<Int>(PrefName.TorrentPort)
            if (customPort > 0) {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$customPort,[::]:$customPort")
            } else {
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
            }

            // Socks5 Proxy config
            if (PrefManager.getVal<Boolean>(PrefName.EnableSocks5Proxy)) {
                val proxyHost = PrefManager.getVal<String>(PrefName.Socks5ProxyHost)
                val proxyPortStr = PrefManager.getVal<String>(PrefName.Socks5ProxyPort)
                val proxyPort = proxyPortStr.toIntOrNull() ?: 1080
                
                settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_hostname.swigValue(), proxyHost)
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_port.swigValue(), proxyPort)
                
                val authEnabled = PrefManager.getVal<Boolean>(PrefName.ProxyAuthEnabled)
                if (authEnabled) {
                    val proxyUsername = PrefManager.getVal<String>(PrefName.Socks5ProxyUsername)
                    val proxyPassword = PrefManager.getVal<String>(PrefName.Socks5ProxyPassword)
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_username.swigValue(), proxyUsername)
                    settings.setString(org.libtorrent4j.swig.settings_pack.string_types.proxy_password.swigValue(), proxyPassword)
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.socks5_pw.swigValue())
                } else {
                    settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.socks5.swigValue())
                }
            } else {
                settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.proxy_type.swigValue(), org.libtorrent4j.swig.settings_pack.proxy_type_t.none.swigValue())
            }

            val params = SessionParams(settings)
            sessionManager.start(params)
            sessionManager.startDht()

            serverPort = findFreePort(8090)
            httpServer = TorrentHttpServer(
                serverPort,
                { hash ->
                    try {
                        sessionManager.find(Sha1Hash.parseHex(hash))
                    } catch (e: Exception) {
                        null
                    }
                },
                {
                    getTorrentCacheDir().absolutePath
                },
                { hash ->
                    getDeadlineRegistry(hash)
                }
            )
            httpServer?.start()
            Logger.log("TorrentServerManager started. Port: $serverPort")
        } catch (e: Exception) {
            Logger.log("Failed to start TorrentServerManager: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getDeadlineRegistry(hash: String): TorrentDeadlineRegistry? {
        val normalized = hash.uppercase()
        registries[normalized]?.let { return it }
        return try {
            val sha1 = Sha1Hash.parseHex(normalized)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
                val reg = TorrentDeadlineRegistry(normalized, handle)
                registries.putIfAbsent(normalized, reg) ?: reg
            } else null
        } catch (_: Exception) {
            null
        }
    }


    private fun isBatteryLowAndNotCharging(): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.TorrentBatterySaving)) return false
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter) ?: return false

        val status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = level / scale.toFloat()

        return !isCharging && batteryPct < 0.20f
    }

    fun stop() {
        Logger.log("Stopping built-in TorrentServerManager...")
        httpServer?.stop()
        httpServer = null
        registries.values.forEach { it.dispose() }
        registries.clear()
        activePrebufferLeases.values.forEach { it.close() }
        activePrebufferLeases.clear()
        scheduledTtls.values.forEach { ttlHandler.removeCallbacks(it) }
        scheduledTtls.clear()
        if (sessionManager.isRunning) {
            sessionManager.stop()
        }
    }

    fun isRunning(): Boolean {
        return sessionManager.isRunning
    }

    fun isAvailable(andEnabled: Boolean = true): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        return if (andEnabled) {
            PrefManager.getVal(PrefName.TorrentEnabled)
        } else {
            true
        }
    }

    fun addTorrent(
        url: String,
        title: String,
        poster: String = "",
        data: String = "",
        save: Boolean = false
    ): Torrent {
        if (isBatteryLowAndNotCharging()) {
            throw Exception("Battery low and not charging")
        }
        start() // Ensure running

        val cacheDir = getTorrentCacheDir()
        var handle: TorrentHandle? = null

        val defaultTrackers = listOf(
            "http://nyaa.tracker.wf:7777/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://uploads.gamehub.live:6969/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "https://tracker.tamersunion.org:443/announce"
        )

        if (url.startsWith("magnet:")) {
            val infoHash = parseMagnetHash(url)
            val sha1 = Sha1Hash.parseHex(infoHash)
            handle = sessionManager.find(sha1)
            if (handle == null) {
                sessionManager.download(url, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(sha1)
            }

            if (handle != null) {
                defaultTrackers.forEach { trk ->
                    try { handle.addTracker(AnnounceEntry(trk)) } catch (_: Exception) {}
                }
                try { handle.forceReannounce() } catch (_: Exception) {}
            }

            // Fast polling for metadata (up to 20 seconds, reannouncing every second)
            var waitTime = 0
            while ((handle == null || handle.torrentFile() == null) && waitTime < 400) {
                Thread.sleep(50)
                handle = sessionManager.find(sha1)
                if (handle != null && waitTime % 20 == 0) {
                    try { handle.forceReannounce() } catch (_: Exception) {}
                }
                waitTime++
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            val tempFile = downloadTorrentFile(url)
            if (tempFile != null) {
                val ti = TorrentInfo(tempFile)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sessionManager.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(ti.infoHash())
            }
        } else {
            val file = File(url)
            if (file.exists()) {
                val ti = TorrentInfo(file)
                val p = Priority.array(Priority.IGNORE, ti.numFiles())
                sessionManager.download(ti, cacheDir, null, p, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handle = sessionManager.find(ti.infoHash())
            }
        }

        if (handle == null) {
            throw Exception("Failed to add torrent: $url")
        }

        // Explicitly resume to ensure downloading starts
        handle.resume()

        defaultTrackers.forEach { trk ->
            try {
                handle.addTracker(AnnounceEntry(trk))
            } catch (_: Exception) {}
        }
        try {
            handle.forceReannounce()
        } catch (_: Exception) {}

        val infoHash = handle.infoHash().toHex()
        val name = handle.getName() ?: title
        val size = handle.torrentFile()?.totalSize() ?: 0L

        val fileStats = handle.torrentFile()?.files()?.let { fileStorage ->
            List(fileStorage.numFiles()) { i ->
                FileStat(
                    id = i,
                    path = fileStorage.filePath(i),
                    length = fileStorage.fileSize(i)
                )
            }
        } ?: emptyList()

        return Torrent(
            title = title,
            name = name,
            hash = infoHash,
            torrent_size = size,
            file_stats = fileStats
        )
    }

    fun prebuffer(torrentHash: String, fileIndex: Int): Boolean {
        return prebufferWithResult(torrentHash, fileIndex).ready
    }

    fun prebufferWithResult(
        torrentHash: String,
        fileIndex: Int,
        onProgress: ((PrebufferProgress) -> Boolean)? = null
    ): PrebufferResult {
        try {
            val normHash = torrentHash.uppercase()
            val sha1 = Sha1Hash.parseHex(normHash)
            var handle = sessionManager.find(sha1) ?: return PrebufferResult(false, null)
            
            // Wait for metadata if not loaded yet
            var waitTime = 0
            while ((!handle.isValid || handle.torrentFile() == null) && waitTime < 300) {
                if (!sessionManager.isRunning) return PrebufferResult(false, null)
                if (onProgress != null) {
                    val status = try { if (handle.isValid) handle.status() else null } catch (_: Throwable) { null }
                    val shouldContinue = onProgress(
                        PrebufferProgress(
                            downloadRateBytes = status?.downloadPayloadRate()?.toLong() ?: 0L,
                            numPeers = status?.numPeers() ?: 0,
                            numSeeds = status?.numSeeds() ?: 0,
                            piecesReady = 0,
                            totalPieces = 4,
                            progressPercent = 0f,
                            stateDescription = "Fetching torrent metadata…"
                        )
                    )
                    if (!shouldContinue) return PrebufferResult(false, null)
                }
                Thread.sleep(100)
                handle = sessionManager.find(sha1) ?: return PrebufferResult(false, null)
                waitTime++
            }
            
            val torrentInfo = handle.torrentFile() ?: return PrebufferResult(false, null)
            val fileStorage = torrentInfo.files()

            if (fileIndex < 0 || fileIndex >= fileStorage.numFiles()) return PrebufferResult(false, null)

            val registry = getDeadlineRegistry(normHash) ?: TorrentDeadlineRegistry(normHash, handle).also { registries[normHash] = it }
            val fileOffset = fileStorage.fileOffset(fileIndex)
            val fileSize = fileStorage.fileSize(fileIndex)
            val pieceLength = torrentInfo.pieceLength().toLong()
            val totalPieces = torrentInfo.numPieces()

            // Prioritize ONLY the selected file in the torrent to avoid wasting bandwidth on other files
            val numFiles = fileStorage.numFiles()
            val filePriorities = Priority.array(Priority.IGNORE, numFiles)
            if (fileIndex in 0 until numFiles) {
                filePriorities[fileIndex] = Priority.TOP_PRIORITY
            }
            try {
                handle.prioritizeFiles(filePriorities)
            } catch (_: Exception) {}

            val plan = computeStreamingPiecePlan(fileOffset, fileSize, pieceLength, totalPieces)
            if (plan.startupPieces.isEmpty()) return PrebufferResult(false, null)

            val sessionId = "prebuffer_${normHash}_${fileIndex}_${leaseCounter.incrementAndGet()}"
            val key = Pair(normHash, fileIndex)

            // Atomically replace existing slot if any
            val existing = activePrebufferLeases[key]
            existing?.close()

            val lease = PrebufferLease(sessionId, normHash, fileIndex) { closedLease ->
                registry.unregisterOwner(closedLease.sessionId)
                activePrebufferLeases.remove(key, closedLease)
                cancelTtl(closedLease.sessionId)
            }
            activePrebufferLeases[key] = lease

            // Schedule manager TTL
            val ttlRunnable = Runnable {
                lease.close()
            }
            scheduledTtls[sessionId] = ttlRunnable
            ttlHandler.postDelayed(ttlRunnable, 30_000L)

            // Explicitly set TOP priority in piece picker for startup and tail pieces
            plan.startupPieces.forEach { piece ->
                try { handle.piecePriority(piece, Priority.TOP_PRIORITY) } catch (_: Exception) {}
            }
            plan.opportunisticTail?.let { tail ->
                try { handle.piecePriority(tail, Priority.TOP_PRIORITY) } catch (_: Exception) {}
                if (tail > 0) {
                    try { handle.piecePriority(tail - 1, Priority.TOP_PRIORITY) } catch (_: Exception) {}
                }
            }

            // Register piece deadlines for plan (highest priority for first and tail piece for MKV/MP4 header indexing)
            plan.startupPieces.forEachIndexed { i, piece ->
                registry.registerDeadline(piece, sessionId, (100 + i * 200).toLong())
            }
            plan.opportunisticTail?.let { tail ->
                registry.registerDeadline(tail, sessionId, 100L)
                if (tail > 0) {
                    registry.registerDeadline(tail - 1, sessionId, 300L)
                }
            }

            Logger.log("TorrentServerManager: Pre-buffering piece plan: startup=${plan.startupPieces}, tail=${plan.opportunisticTail} for file $fileIndex")

            // Wait up to 45 seconds for startup pieces (and tail piece if present)
            val tailPiece = plan.opportunisticTail
            val totalStartupCount = plan.startupPieces.size + (if (tailPiece != null) 1 else 0)
            var waitCount = 0
            while (waitCount < 450) {
                if (!sessionManager.isRunning || !handle.isValid) break
                val startupReadyCount = plan.startupPieces.count { handle.havePiece(it) }
                val tailReady = tailPiece == null || handle.havePiece(tailPiece)
                val readyCount = startupReadyCount + (if (tailPiece != null && tailReady) 1 else 0)
                val progressFraction = if (totalStartupCount > 0) readyCount.toFloat() / totalStartupCount else 0f

                if (onProgress != null) {
                    val status = try { if (handle.isValid) handle.status() else null } catch (_: Throwable) { null }
                    val dlRate = status?.downloadPayloadRate()?.toLong() ?: 0L
                    val peers = status?.numPeers() ?: 0
                    val seeds = status?.numSeeds() ?: 0

                    val desc = when {
                        peers == 0 -> "Finding peers & seeders…"
                        readyCount == 0 -> "Connecting to swarm…"
                        readyCount < totalStartupCount -> "Buffering runway ($readyCount/$totalStartupCount pieces)…"
                        else -> "Ready to play!"
                    }

                    val shouldContinue = onProgress(
                        PrebufferProgress(
                            downloadRateBytes = dlRate,
                            numPeers = peers,
                            numSeeds = seeds,
                            piecesReady = readyCount,
                            totalPieces = totalStartupCount,
                            progressPercent = progressFraction,
                            stateDescription = desc
                        )
                    )
                    if (!shouldContinue) {
                        lease.close()
                        return PrebufferResult(false, null)
                    }
                }

                val hasStartup = plan.startupPieces.all { handle.havePiece(it) }
                val hasTail = tailPiece == null || handle.havePiece(tailPiece)
                if (hasStartup && hasTail) break
                Thread.sleep(100)
                waitCount++
            }
            val success = plan.startupPieces.all { handle.havePiece(it) } && (tailPiece == null || handle.havePiece(tailPiece))
            Logger.log("TorrentServerManager: Pre-buffering success = $success (startupPieces=${plan.startupPieces.map { handle.havePiece(it) }}, tail=${tailPiece?.let { handle.havePiece(it) }})")
            return PrebufferResult(success, if (success) lease else null)
        } catch (e: Exception) {
            e.printStackTrace()
            return PrebufferResult(false, null)
        }
    }

    fun adoptPrebufferLease(torrentHash: String, fileIndex: Int): PrebufferLease? {
        val key = Pair(torrentHash.uppercase(), fileIndex)
        val lease = activePrebufferLeases.remove(key)
        if (lease != null && !lease.isClosed) {
            cancelTtl(lease.sessionId)
            return lease
        }
        return null
    }

    private fun cancelTtl(sessionId: String) {
        val runnable = scheduledTtls.remove(sessionId)
        if (runnable != null) {
            ttlHandler.removeCallbacks(runnable)
        }
    }

    fun getLink(torrent: Torrent, fileIndex: Int): String {
        return "http://127.0.0.1:$serverPort/stream?hash=${torrent.hash}&index=$fileIndex"
    }

    fun getLink(torrentHash: String, fileIndex: Int): String {
        return "http://127.0.0.1:$serverPort/stream?hash=$torrentHash&index=$fileIndex"
    }

    fun removeTorrent(torrentHash: String) {
        try {
            val normHash = torrentHash.uppercase()
            registries.remove(normHash)?.dispose()
            activePrebufferLeases.entries.removeIf { entry ->
                if (entry.key.first == normHash) {
                    entry.value.close()
                    true
                } else false
            }
            val sha1 = Sha1Hash.parseHex(normHash)
            val handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid) {
                sessionManager.remove(handle)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTorrentCacheDir(): File {
        val dir = File(context.cacheDir, "torrent_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < 65535) {
            try {
                java.net.ServerSocket(port).use {
                    return port
                }
            } catch (e: java.io.IOException) {
                port++
            }
        }
        return startPort
    }

    private fun parseMagnetHash(url: String): String {
        val xtIndex = url.indexOf("xt=urn:btih:")
        if (xtIndex != -1) {
            var hash = url.substring(xtIndex + 12)
            val ampersandIndex = hash.indexOf("&")
            if (ampersandIndex != -1) {
                hash = hash.substring(0, ampersandIndex)
            }
            return hash.uppercase()
        }
        throw IllegalArgumentException("Invalid magnet link")
    }

    private fun downloadTorrentFile(url: String): File? {
        try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: return null
                    val tempFile = File.createTempFile("temp", ".torrent", context.cacheDir)
                    tempFile.writeBytes(bytes)
                    return tempFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
