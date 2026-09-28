package ani.dantotsu.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import ani.dantotsu.isWifiConnected
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * MAX MERGE M3 HOLD (Blocker 6): Wi-Fi → cellular transition guard.
 *
 * One-time entry/start guards cannot cover a transport change mid-transfer.
 * Services register this monitor; when the allowed transport disappears
 * while `DownloadWifiOnly` is on, [onBlocked] fires EXACTLY ONCE per
 * transition (edge-triggered) so the service can stop active transfers
 * through its existing safe cancellation path. [onRestored] fires when an
 * allowed transport returns — services must NOT auto-resume (manual retry
 * only), so the default is a no-op hook.
 *
 * The transition decision itself is pure ([nextWifiGateAction]) and
 * unit-proved (WifiGateTransitionTest).
 */
enum class WifiGateAction {
    BLOCK_NOW,
    ALREADY_BLOCKED,
    ALLOWED,
}

internal fun nextWifiGateAction(
    prefOn: Boolean,
    satisfied: Boolean,
    wasBlocked: Boolean,
): WifiGateAction {
    if (!prefOn || satisfied) return WifiGateAction.ALLOWED
    return if (wasBlocked) WifiGateAction.ALREADY_BLOCKED else WifiGateAction.BLOCK_NOW
}

class WifiLossMonitor(
    private val context: Context,
    private val onBlocked: () -> Unit,
    private val onRestored: () -> Unit = {},
) {
    private var wasBlocked = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun connectivityManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun ensure() {
        if (callback != null) return
        val manager = connectivityManager() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate()
            override fun onLost(network: Network) = evaluate()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = evaluate()
        }
        callback = cb
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                cb
            )
        } catch (_: Exception) {
            callback = null
        }
        evaluate()
    }

    fun release() {
        val cb = callback ?: return
        callback = null
        try {
            connectivityManager()?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun evaluate() {
        val prefOn: Boolean = try {
            PrefManager.getVal(PrefName.DownloadWifiOnly)
        } catch (_: Exception) {
            false
        }
        when (nextWifiGateAction(prefOn, isWifiConnected(context), wasBlocked)) {
            WifiGateAction.BLOCK_NOW -> {
                wasBlocked = true
                onBlocked()
            }
            WifiGateAction.ALLOWED -> {
                if (wasBlocked) {
                    wasBlocked = false
                    onRestored()
                }
            }
            WifiGateAction.ALREADY_BLOCKED -> {}
        }
    }
}
