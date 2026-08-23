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
    val opportunisticTail: Int?,
    val tailPieces: List<Int> = opportunisticTail?.let { listOf(it) } ?: emptyList()
)

fun computeStreamingPiecePlan(
    fileOffset: Long,
    fileSize: Long,
    pieceLength: Long,
    totalPieces: Int
): StreamingPiecePlan {
    if (fileOffset < 0 || fileSize <= 0 || pieceLength <= 0 || totalPieces <= 0) {
        return StreamingPiecePlan(emptyList(), null, emptyList())
    }
    val firstPiece = (fileOffset / pieceLength).toInt()
    val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

    if (firstPiece < 0 || firstPiece >= totalPieces || lastPiece < 0 || lastPiece >= totalPieces || firstPiece > lastPiece) {
        return StreamingPiecePlan(emptyList(), null, emptyList())
    }

    val candidates = listOf(firstPiece, firstPiece + 1, firstPiece + 2, firstPiece + 3)
    val boundedStartup = candidates.filter { it <= lastPiece && it < totalPieces }.distinct()
    val tailStart = maxOf(firstPiece, lastPiece - 2)
    val tailCandidates = (tailStart..lastPiece).filter { it !in boundedStartup && it < totalPieces }.distinct()
    val tail = if (lastPiece !in boundedStartup) lastPiece else null

    return StreamingPiecePlan(boundedStartup, tail, tailCandidates)
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

    fun focusPieceWindow(
        ownerId: String,
        startPiece: Int,
        count: Int,
        totalPieces: Int,
        protectedPieces: Set<Int> = emptySet()
    ) {
        synchronized(this) {
            if (isDisposed || !adapter.isValid) return
            val windowEnd = minOf(startPiece + count, totalPieces)
            val iterator = pieceDeadlines.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val piece = entry.key
                if ((piece < startPiece || piece >= windowEnd) && piece !in protectedPieces) {
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
            // 1. Connectivity & NAT Traversal
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)

            // 2. High-Speed Torrent Swarm & Pipelining Limits
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            settings.setString(
                org.libtorrent4j.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401,dht.aelitis.com:6881,dht.ip2location.io:6881"
            )
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_downloads.swigValue(), 10)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_seeds.swigValue(), 10)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_limit.swigValue(), 50)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), 80)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.unchoke_slots_limit.swigValue(), 32)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connection_speed.swigValue(), 30)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.torrent_connect_boost.swigValue(), 30)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(), 1000)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 500)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_peerlist_size.swigValue(), 500)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.max_paused_peerlist_size.swigValue(), 200)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.request_timeout.swigValue(), 10)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.peer_connect_timeout.swigValue(), 15)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.piece_timeout.swigValue(), 5)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.aio_threads.swigValue(), 4)
            settings.setInteger(org.libtorrent4j.swig.settings_pack.int_types.hashing_threads.swigValue(), 2)

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

    private fun buildEnrichedMagnetUri(rawMagnet: String, trackers: List<String>): String {
        val existingTrackers = Regex("""[&?]tr=([^&]+)""").findAll(rawMagnet).map {
            try { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (_: Exception) { it.groupValues[1] }
        }.toSet()
        val builder = StringBuilder(rawMagnet)
        trackers.forEach { trk ->
            if (trk !in existingTrackers) {
                val encoded = try { java.net.URLEncoder.encode(trk, "UTF-8") } catch (_: Exception) { trk }
                builder.append("&tr=").append(encoded)
            }
        }
        return builder.toString()
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
            "https://tracker.tamersunion.org:443/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://tracker.leechers-paradise.org:6969/announce",
            "udp://movies.zsw.ca:6969/announce",
            "udp://tracker.cyberia.is:6969/announce",
            "udp://retracker.lanta-net.ru:2710/announce",
            "udp://tracker.theoks.net:6969/announce",
            "udp://tracker-udp.gbitt.info:80/announce",
            "http://tracker.renfed.com:80/announce",
            "udp://ttk2.n1ed.com:6969/announce",
            "udp://tracker.altrosky.nl:6969/announce",
            "udp://tracker.bittor.pw:1337/announce"
        )

        if (url.startsWith("magnet:")) {
            val infoHash = parseMagnetHash(url)
            val sha1 = Sha1Hash.parseHex(infoHash)
            handle = sessionManager.find(sha1)
            if (handle == null) {
                val enrichedUrl = buildEnrichedMagnetUri(url, defaultTrackers)
                sessionManager.download(enrichedUrl, cacheDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
                if (handle != null && waitTime % 15 == 0) {
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
        var lease: PrebufferLease? = null
        var transferredToResult = false

        try {
            val normHash = torrentHash.uppercase()
            val sha1 = Sha1Hash.parseHex(normHash)
            var handle = sessionManager.find(sha1) ?: return PrebufferResult(false, null)

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
                            totalPieces = 1,
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

            val numFiles = fileStorage.numFiles()
            val filePriorities = Priority.array(Priority.IGNORE, numFiles)
            if (fileIndex in 0 until numFiles) {
                filePriorities[fileIndex] = Priority.TOP_PRIORITY
            }
            try { handle.prioritizeFiles(filePriorities) } catch (_: Exception) {}

            val plan = computeStreamingPiecePlan(fileOffset, fileSize, pieceLength, totalPieces)
            if (plan.startupPieces.isEmpty()) return PrebufferResult(false, null)

            val sessionId = "prebuffer_${normHash}_${fileIndex}_${leaseCounter.incrementAndGet()}"
            val key = Pair(normHash, fileIndex)

            val existing = activePrebufferLeases[key]
            existing?.close()

            val createdLease = PrebufferLease(sessionId, normHash, fileIndex) { closedLease ->
                registry.unregisterOwner(closedLease.sessionId)
                activePrebufferLeases.remove(key, closedLease)
                cancelTtl(closedLease.sessionId)
            }
            lease = createdLease
            activePrebufferLeases[key] = createdLease

            val ttlRunnable = Runnable { createdLease.close() }
            scheduledTtls[sessionId] = ttlRunnable
            ttlHandler.postDelayed(ttlRunnable, 30_000L)

            // Prioritize head pieces
            val firstPiece = plan.startupPieces.first()
            try { handle.piecePriority(firstPiece, Priority.TOP_PRIORITY) } catch (_: Exception) {}
            registry.registerDeadline(firstPiece, sessionId, 0L)

            plan.startupPieces.drop(1).forEachIndexed { idx, piece ->
                val prio = if (idx < 3) Priority.TOP_PRIORITY else Priority.SIX
                try { handle.piecePriority(piece, prio) } catch (_: Exception) {}
                registry.registerDeadline(piece, sessionId, (150L + idx * 100L))
            }

            // Prioritize tail pieces (critical for MKV Cues and MP4 Moov container index)
            val tailRunway = if (plan.tailPieces.isNotEmpty()) plan.tailPieces else plan.opportunisticTail?.let { listOf(it) } ?: emptyList()
            tailRunway.forEachIndexed { idx, tailPiece ->
                try { handle.piecePriority(tailPiece, Priority.TOP_PRIORITY) } catch (_: Exception) {}
                registry.registerDeadline(tailPiece, sessionId, (50L + idx * 100L))
            }

            // Fast start: wait up to 15s for the first requested piece
            var waitCount = 0
            while (waitCount < 150) {
                if (!sessionManager.isRunning || !handle.isValid) break
                val isFirstPieceReady = handle.havePiece(firstPiece)

                if (onProgress != null) {
                    val status = try { if (handle.isValid) handle.status() else null } catch (_: Throwable) { null }
                    val dlRate = status?.downloadPayloadRate()?.toLong() ?: 0L
                    val peers = status?.numPeers() ?: 0
                    val seeds = status?.numSeeds() ?: 0

                    val shouldContinue = onProgress(
                        PrebufferProgress(
                            downloadRateBytes = dlRate,
                            numPeers = peers,
                            numSeeds = seeds,
                            piecesReady = if (isFirstPieceReady) 1 else 0,
                            totalPieces = 1,
                            progressPercent = if (isFirstPieceReady) 1f else 0f,
                            stateDescription = if (isFirstPieceReady) "Ready to play!" else "Buffering stream ($peers peers)…"
                        )
                    )
                    if (!shouldContinue) {
                        return PrebufferResult(false, null)
                    }
                }

                if (isFirstPieceReady) {
                    transferredToResult = true
                    return PrebufferResult(true, createdLease)
                }
                Thread.sleep(100)
                waitCount++
            }

            val success = handle.havePiece(firstPiece)
            if (success) {
                transferredToResult = true
                return PrebufferResult(true, createdLease)
            }
            return PrebufferResult(false, null)
        } catch (e: Exception) {
            return PrebufferResult(false, null)
        } finally {
            if (!transferredToResult) {
                lease?.close()
            }
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

    private fun base32ToHex(base32: String): String {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = base32.uppercase().trim()
        var bits = 0L
        var bitCount = 0
        val bytes = ArrayList<Byte>()
        for (c in clean) {
            val valIndex = base32Chars.indexOf(c)
            if (valIndex == -1) continue
            bits = (bits shl 5) or valIndex.toLong()
            bitCount += 5
            if (bitCount >= 8) {
                bitCount -= 8
                bytes.add(((bits shr bitCount) and 0xFF).toByte())
            }
        }
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun parseMagnetHash(url: String): String {
        val decoded = try { java.net.URLDecoder.decode(url, "UTF-8") } catch (_: Exception) { url }
        val xtIndex = decoded.indexOf("xt=urn:btih:", ignoreCase = true)
        if (xtIndex != -1) {
            var hash = decoded.substring(xtIndex + 12)
            val ampersandIndex = hash.indexOf("&")
            if (ampersandIndex != -1) {
                hash = hash.substring(0, ampersandIndex)
            }
            hash = hash.trim().uppercase()
            if (hash.length == 32) {
                return base32ToHex(hash)
            }
            return hash
        }
        val btmhIndex = decoded.indexOf("xt=urn:btmh:", ignoreCase = true)
        if (btmhIndex != -1) {
            var hash = decoded.substring(btmhIndex + 12)
            val ampersandIndex = hash.indexOf("&")
            if (ampersandIndex != -1) {
                hash = hash.substring(0, ampersandIndex)
            }
            return hash.trim().uppercase()
        }
        val clean = decoded.trim().uppercase()
        if (clean.length == 40 && clean.all { it in "0123456789ABCDEF" }) {
            return clean
        }
        if (clean.length == 32 && clean.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }) {
            return base32ToHex(clean)
        }
        throw IllegalArgumentException("Invalid magnet link: $url")
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
