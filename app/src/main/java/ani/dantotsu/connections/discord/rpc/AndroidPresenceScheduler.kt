package ani.dantotsu.connections.discord.rpc

import android.os.Handler
import android.os.Looper

/**
 * Production [PresenceScheduler]: posts the coalescing flush onto the main Looper. The [DiscordRpcClient]
 * Binder session is owned/created on the UI thread, so deliveries must also run there.
 */
class AndroidPresenceScheduler : PresenceScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit): PresenceScheduler.PresenceTask {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return object : PresenceScheduler.PresenceTask {
            override fun cancel() = handler.removeCallbacks(runnable)
        }
    }
}
