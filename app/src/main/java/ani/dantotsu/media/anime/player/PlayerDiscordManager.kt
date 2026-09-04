package ani.dantotsu.media.anime.player

import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.connections.discord.rpc.DiscordPresenceOwnership
import ani.dantotsu.connections.discord.rpc.DiscordRpcPublisher
import ani.dantotsu.connections.discord.rpc.DiscordRpcUtils
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

class PlayerDiscordManager(
    private val activity: AppCompatActivity
) {
    // Unique per-instance owner token: protects against a stale player manager (recreation / backgrounding)
    // clearing a newer anime presence (review Blocker 2 — same-kind ownership).
    private val token: DiscordPresenceOwnership.OwnerToken = DiscordRpcPublisher.createAnimeToken()

    fun updatePresence(
        media: Media?,
        episode: Episode?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        activity.runOnUiThread {
            if (media == null || episode == null) {
                DiscordRpcPublisher.clearAnime(token)
                return@runOnUiThread
            }
            val context = activity
            val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode)
            val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
            val richPresenceEnabled = PrefManager.getVal<Boolean>(PrefName.DiscordRichPresenceEnabled)

            // Adult-media suppression: clear and do not publish.
            if (DiscordRpcUtils.shouldSuppressForAdultMedia(media.isAdult)) {
                DiscordRpcPublisher.clearAnime(token)
                return@runOnUiThread
            }
            // Discord Rich Presence disabled / offline / incognito: clear any existing presence, do not publish.
            if (!(isOnline(context) && !offline && !incognito && richPresenceEnabled)) {
                DiscordRpcPublisher.clearAnime(token)
                return@runOnUiThread
            }
            // Tokenless transport: the legacy Discord.token requirement is intentionally dropped.
            DiscordRpcPublisher.publishAnime(token, context, media, episode, positionMs, durationMs, isPlaying)
        }
    }

    fun clear() {
        activity.runOnUiThread {
            DiscordRpcPublisher.clearAnime(token)
        }
    }
}
