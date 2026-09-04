package ani.dantotsu.connections.discord.rpc

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Stateless Discord RPC utilities shared across the tokenless transport.
 */
object DiscordRpcUtils {
    /**
     * Returns true if adult media should be suppressed from Discord Rich Presence.
     */
    fun shouldSuppressForAdultMedia(isAdultMedia: Boolean): Boolean {
        return isAdultMedia && PrefManager.getVal(PrefName.DiscordRPCDisableAdultMedia, false)
    }
}
