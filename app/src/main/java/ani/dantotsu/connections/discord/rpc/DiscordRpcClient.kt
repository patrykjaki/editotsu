package ani.dantotsu.connections.discord.rpc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import ani.dantotsu.util.Logger
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import java.util.concurrent.atomic.AtomicReference

/**
 * Android adapter for the Discord Social SDK RPC transport.
 *
 * This class performs ONLY platform mechanics — resolving/binding the Discord service, adapting the
 * AIDL connection into a [DiscordRpcConnection], and serializing every Binder / timeout / recovery
 * callback onto the main Looper before handing it to [DiscordRpcClientCoordinator]. All bind
 * registration, run identity, and recovery decisions live in the coordinator (review Blocker 1/2/3
 * and the v3 "REQUIRED TEST GAP").
 */
open class DiscordRpcClient(
    private val context: Context,
    private val connectAppId: Long = EDITOTSU_APP_ID,
) : DiscordRpcBindDelegate, DiscordRpcScheduler {

    companion object {
        const val ACTION: String = "com.discord.socialsdk.rpc.IDiscordRpcService"
        const val TARGET_PACKAGE: String = "com.discord"
        const val SERVICE_CLASS: String = "com.discord.socialrpc.DiscordRpcService"
        const val EDITOTSU_APP_ID: Long = 1540568327712153743L
        /**
         * The RPC handshake `version` string, pinned to the PREP v5 live/render proof (empty string).
         * Do NOT substitute the process PID, app `versionName`, a build identity, or a Social-SDK
         * version guess here — the PID belongs in `SET_ACTIVITY.args.pid`, not in the handshake.
         */
        internal const val CONNECT_VERSION: String = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pid: Int = Process.myPid()

    private val controller = DiscordRpcController(
        appId = connectAppId,
        logger = object : DiscordRpcLogger {
            override fun log(message: String) = Logger.log(message)
            override fun warn(message: String) = Logger.log(message)
        },
        onStateChanged = ::onControllerStateChanged,
    )

    private val coordinator = DiscordRpcClientCoordinator(
        pid = pid,
        controller = controller,
        scheduler = this,
        logger = object : DiscordRpcLogger {
            override fun log(message: String) = Logger.log(message)
            override fun warn(message: String) = Logger.log(message)
        },
        binder = this,
    )

    // The currently-bound platform connection (one at a time). Each Holder also owns the RPC
    // connection established for its exact run — there is NO process-global RPC slot (review Blocker 2).
    private val activeServiceConn = AtomicReference<Holder?>(null)

    private data class Holder(
        val gen: Int,
        val conn: ServiceConnection,
        val callback: IDiscordRpcCallback,
    )

    // ── Public API ──────────────────────────────────────────────────────────────────

    fun setPresence(presence: DiscordPresence) = coordinator.requestPresence(presence)
    fun clearPresence() = coordinator.clearPresence()
    fun shutdown() = coordinator.shutdown()

    private fun onControllerStateChanged(state: DiscordRpcState) {
        Logger.log("rpc: state -> $state")
    }

    // ── DiscordRpcBindDelegate ───────────────────────────────────────────────────────

    override fun bind(runGen: Int): Boolean {
        val ctx = context.applicationContext
        val intent = Intent(ACTION).setPackage(TARGET_PACKAGE).setComponent(ComponentName(TARGET_PACKAGE, SERVICE_CLASS))
        if (!resolveService(intent)) {
            Logger.log("rpc: Discord RPC service unavailable (Discord not installed?) — graceful skip")
            return false
        }
        val callback = object : IDiscordRpcCallback.Stub() {
            override fun onFrame(frame: String) {
                handler.post { coordinator.onFrame(runGen, frame) }
            }
            override fun onClose(code: Int, reason: String) {
                handler.post { coordinator.onClose(runGen) }
            }
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, svc: IBinder) {
                // Defense in depth (review v4 Blocker 2): only open an RPC session if this
                // ServiceConnection still belongs to the currently published run. A late callback for a
                // retired run is not connected at all, so nothing can leak; the coordinator additionally
                // disconnects any stale connection it is handed (fail-closed).
                val holder = activeServiceConn.get()
                if (holder == null || holder.gen != runGen) return
                // Establish the RPC connection for THIS run and deliver it bound to this run's generation.
                // A connect failure yields a null handle, which the coordinator treats as a loss.
                val rpc = runCatching {
                    IDiscordRpcService.Stub.asInterface(svc).connect(connectAppId, CONNECT_VERSION, callback)
                }.getOrNull()
                val wrapped = rpc?.let { c ->
                    object : DiscordRpcConnection {
                        // The AIDL sendFrame is void; translate a thrown dead-Binder failure into false
                        // so the coordinator/controller never record an unacknowledged nonce (review B3).
                        override fun sendFrame(frame: String): Boolean =
                            runCatching { c.sendFrame(frame); true }.getOrDefault(false)
                        override fun disconnect() { runCatching { c.disconnect() } }
                    }
                }
                handler.post { coordinator.onServiceConnected(runGen, wrapped) }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                handler.post { coordinator.onServiceDisconnected(runGen) }
            }
            override fun onBindingDied(name: ComponentName) {
                handler.post { coordinator.onBindingDied(runGen) }
            }
            override fun onNullBinding(name: ComponentName) {
                Logger.log("rpc: null binding from Discord RPC service")
                handler.post { coordinator.onNullBinding(runGen) }
            }
        }
        val holder = Holder(runGen, conn, callback)
        activeServiceConn.set(holder)
        val accepted = runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        // If registration was never accepted, drop the unpublished holder so adapter ownership state
        // stays accurate and no stale callback can reason about a non-existent binding (review v4 hardening).
        if (!accepted) activeServiceConn.compareAndSet(holder, null)
        return accepted
    }

    override fun unbind(runGen: Int) {
        val holder = activeServiceConn.get()
        // Only tear down the connection that belongs to this run; a newer run's reference is left
        // intact (review Blocker 1: per-run registration must not be cross-consumed).
        if (holder != null && holder.gen == runGen) {
            if (activeServiceConn.compareAndSet(holder, null)) {
                runCatching { context.applicationContext.unbindService(holder.conn) }
            }
            // The AIDL disconnect is performed by the coordinator via the run's DiscordRpcConnection.
        }
    }

    // ── DiscordRpcScheduler ───────────────────────────────────────────────────────────

    private data class AndroidTask(val runnable: Runnable) : DiscordRpcScheduler.Task

    override fun postDelayed(delayMs: Long, block: () -> Unit): DiscordRpcScheduler.Task {
        val r = Runnable { block() }
        handler.postDelayed(r, delayMs)
        return AndroidTask(r)
    }

    override fun cancel(task: DiscordRpcScheduler.Task) {
        val r = (task as? AndroidTask)?.runnable ?: return
        handler.removeCallbacks(r)
    }

    // ── Test/diagnostic seam ──────────────────────────────────────────────────────────

    protected open fun resolveService(intent: Intent): Boolean =
        context.applicationContext.packageManager.queryIntentServices(intent, 0).isNotEmpty()
}
