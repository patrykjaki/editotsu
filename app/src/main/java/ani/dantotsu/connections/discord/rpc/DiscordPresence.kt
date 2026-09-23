package ani.dantotsu.connections.discord.rpc

/**
 * Immutable generic Discord Rich Presence payload — **user/media activity data only**.
 *
 * This is the transport-agnostic model for the clean-room Binder route. It intentionally
 * carries no OAuth token, no WebView, and no network state. The Discord application id is
 * NOT part of this model: it is pinned once at the transport/controller boundary
 * ([DiscordRpcClient.connectAppId] / [DiscordRpcController.appId]) so the media/player layer
 * never chooses or even knows the app id (see review Gap 6).
 *
 * Field-length bounds and control-character sanitization are applied defensively at encode
 * time by [DiscordRpcCodec] (graceful truncation, never a crash). This model only enforces
 * structural invariants that cannot be safely normalized: non-blank name and ≤2 buttons.
 *
 * @param name Activity name (required, non-blank).
 * @param type Activity type; defaults to [DiscordActivityType.WATCHING].
 * @param details First line of the presence body (optional, bounded+snipped at encode).
 * @param state Second line of the presence body (optional, bounded+snipped at encode).
 * @param largeImageKey / [largeImageText] Large asset key + hover text (optional, bounded).
 * @param smallImageKey / [smallImageText] Small asset key + hover text (optional, bounded).
 * @param startTimestamp / [endTimestamp] Epoch-millisecond timestamps (optional).
 * @param buttons Up to two outbound links (optional).
 */
data class DiscordPresence(
    val name: String,
    val type: DiscordActivityType = DiscordActivityType.WATCHING,
    val details: String? = null,
    val state: String? = null,
    val largeImageKey: String? = null,
    val largeImageText: String? = null,
    val smallImageKey: String? = null,
    val smallImageText: String? = null,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val buttons: List<DiscordPresenceButton> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Discord presence name must not be blank" }
        require(buttons.size <= 2) { "Discord allows at most 2 buttons (got ${buttons.size})" }
    }
}
