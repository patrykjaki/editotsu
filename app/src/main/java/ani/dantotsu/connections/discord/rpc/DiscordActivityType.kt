package ani.dantotsu.connections.discord.rpc

/**
 * Discord Rich Presence activity types (RPC `type` field values).
 *
 * Phase 1 ships the full vocabulary — matching the current Discord Social SDK wire enum
 * exactly (including [CUSTOM_STATUS] = 4 and [HANG_STATUS] = 6) — but the production
 * integration targets [WATCHING] (value 3) per the Editotsu presence spec.
 */
enum class DiscordActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    CUSTOM_STATUS(4),
    COMPETING(5),
    HANG_STATUS(6),
}
