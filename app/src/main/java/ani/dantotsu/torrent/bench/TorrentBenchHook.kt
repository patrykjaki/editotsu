package ani.dantotsu.torrent.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import ani.dantotsu.torrent.TorrentCacheManager
import ani.dantotsu.torrent.TorrentServerManager
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

/**
 * Torrent bench hook (task 7 rig). Compiled in only when the build sets
 * `-Peditotsu.torrentBench=true` (BuildConfig.TORRENT_BENCH); never in normal builds.
 *
 * The receiver requires android.permission.DUMP, which the adb shell holds and
 * ordinary apps do not, so only `adb shell am broadcast` can drive it.
 *
 *   add:    --es cmd add --es uri_b64 <base64 magnet/url> [--ei index N]
 *   remove: --es cmd remove --es hash <infohash>
 *
 * Output goes to logcat tag EditotsuBench as `key=value` pairs. Torrent and file
 * names are never logged (owner's private magnets); only hash, index and sizes.
 */
object TorrentBenchHook {
    const val ACTION = "ani.editotsu.bench.TORRENT"
    private const val TAG = "EditotsuBench"
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TorrentBench") }

    fun install(context: Context) {
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val pending = goAsync()
                    worker.execute {
                        try {
                            handle(app, intent)
                        } catch (t: Throwable) {
                            Log.i(TAG, "event=error type=${t.javaClass.simpleName} msg=${t.message?.take(200)}")
                        } finally {
                            pending.finish()
                        }
                    }
                }
            },
            IntentFilter(ACTION),
            android.Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.i(TAG, "event=installed libtorrentArm64=${ani.dantotsu.BuildConfig.LIBTORRENT_ARM64}")
    }

    private fun handle(app: Context, intent: Intent) {
        val manager: TorrentServerManager = Injekt.get()
        when (intent.getStringExtra("cmd")) {
            "add" -> {
                val b64 = intent.getStringExtra("uri_b64")
                if (b64 == null) {
                    Log.i(TAG, "event=error msg=missing_uri")
                    return
                }
                val uri = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
                val t0 = SystemClock.elapsedRealtime()
                Log.i(TAG, "event=add_start")
                val torrent = manager.addTorrent(uri, title = "bench")
                val metaMs = SystemClock.elapsedRealtime() - t0
                val files = torrent.file_stats.orEmpty()
                val requested = intent.getIntExtra("index", -1)
                val file = if (requested >= 0) {
                    files.firstOrNull { it.id == requested }
                } else {
                    files.maxByOrNull { it.length }
                }
                val hash = torrent.hash
                if (hash == null || file == null) {
                    Log.i(TAG, "event=add_failed metaMs=$metaMs files=${files.size}")
                    return
                }
                val index = file.id ?: files.indexOf(file)
                Log.i(
                    TAG,
                    "event=added hash=$hash index=$index len=${file.length} files=${files.size} " +
                        "metaMs=$metaMs url=${manager.getLink(hash, index)}"
                )
                startStatusLog(manager, hash, t0)
            }
            "remove" -> {
                val hash = intent.getStringExtra("hash") ?: return
                statusTask?.cancel(false)
                statusTask = null
                // keepPayload=true mirrors the app (removeTorrent keeps the retained cache);
                // false deletes it so the next add starts cold.
                val keep = intent.getBooleanExtra("keepPayload", false)
                val t0 = SystemClock.elapsedRealtime()
                manager.removeTorrent(hash)
                val removeMs = SystemClock.elapsedRealtime() - t0
                var deleted = false
                var attempts = 0
                if (!keep) {
                    // removeTorrent releases the cache lease asynchronously; deletion is refused
                    // while the entry is still ACTIVE, so retry for up to 30 s.
                    val cache = TorrentCacheManager.getInstance(app)
                    while (!deleted && attempts < 30) {
                        attempts++
                        deleted = runBlocking { cache.deleteTorrentPayload(hash) }
                        if (!deleted) Thread.sleep(1000)
                    }
                }
                Log.i(
                    TAG,
                    "event=removed hash=$hash removeMs=$removeMs keepPayload=$keep " +
                        "payloadDeleted=$deleted deleteAttempts=$attempts " +
                        "totalMs=${SystemClock.elapsedRealtime() - t0}"
                )
            }
            else -> Log.i(TAG, "event=error msg=unknown_cmd")
        }
    }

    private val statusTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "TorrentBenchStatus") }
    @Volatile private var statusTask: java.util.concurrent.ScheduledFuture<*>? = null

    /** Logs an engine snapshot every 2 s until the torrent is removed or gone. */
    private fun startStatusLog(manager: TorrentServerManager, hash: String, t0: Long) {
        statusTask?.cancel(false)
        statusTask = statusTimer.scheduleWithFixedDelay({
            val snapshot = manager.benchStatus(hash)
            val ms = SystemClock.elapsedRealtime() - t0
            if (snapshot == null) {
                Log.i(TAG, "event=status ms=$ms gone=true")
                statusTask?.cancel(false)
            } else {
                Log.i(TAG, "event=status ms=$ms $snapshot")
            }
        }, 0, 2, java.util.concurrent.TimeUnit.SECONDS)
    }
}
