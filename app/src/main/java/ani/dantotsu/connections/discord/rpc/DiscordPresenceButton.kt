package ani.dantotsu.connections.discord.rpc

/**
 * A single rich-presence button (max two per activity).
 */
data class DiscordPresenceButton(
    val label: String,
    val url: String,
)
