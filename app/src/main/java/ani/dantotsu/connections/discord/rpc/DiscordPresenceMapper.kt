package ani.dantotsu.connections.discord.rpc

/**
 * Pure (Android-free, JVM-testable) mapping from resolved Editotsu media fields to the generic
 * [DiscordPresence] model used by the clean-room transport.
 *
 * All string formatting and context resolution is the caller's responsibility — this object only
 * assembles the model and pins the Phase 2 invariants:
 *  - activity type is always [DiscordActivityType.WATCHING] (never [DiscordActivityType.CUSTOM_STATUS]);
 *  - anime uses the play/video small-image asset, manga uses the book small-image asset;
 *  - the semantic small-text distinguishes "Watching anime" vs "Reading manga".
 *
 * The small-image asset keys are the keys expected to be pre-registered in the Editotsu Discord
 * Developer Portal (application id = [DiscordRpcClient.EDITOTSU_APP_ID]). If a key is NOT
 * registered, Discord simply omits the small image (no error, no fallback to a wrong key).
 */
object DiscordPresenceMapper {

    const val ANIME_SMALL_IMAGE_KEY = "anime"
    const val ANIME_PAUSED_SMALL_IMAGE_KEY = "anime_paused"
    const val MANGA_SMALL_IMAGE_KEY = "manga"
    const val ANIME_SMALL_TEXT = "Watching on Editotsu"
    const val ANIME_PAUSED_SMALL_TEXT = "Paused on Editotsu"
    const val MANGA_SMALL_TEXT = "Reading on Editotsu"

    // External URL fallbacks for small_image — Application Asset keys may not resolve
    // in headless Binder sessions. These are the Discord Developer Portal CDN URLs for
    // the exact Application Assets uploaded under app 1540568327712153743.
    const val ANIME_SMALL_IMAGE_URL =
        "https://cdn.discordapp.com/app-assets/1540568327712153743/1543452174455930981.png"
    const val ANIME_PAUSED_SMALL_IMAGE_URL =
        "https://cdn.discordapp.com/app-assets/1540568327712153743/1543452174695141426.png"
    const val MANGA_SMALL_IMAGE_URL =
        "https://cdn.discordapp.com/app-assets/1540568327712153743/1543452174363795466.png"

    fun animePresence(
        name: String,
        details: String,
        state: String,
        largeImageKey: String?,
        largeImageText: String?,
        smallImageKey: String,
        smallImageText: String,
        startTimestamp: Long?,
        endTimestamp: Long?,
        buttons: List<DiscordPresenceButton>,
    ): DiscordPresence = DiscordPresence(
        name = name,
        type = DiscordActivityType.WATCHING,
        details = details,
        state = state,
        largeImageKey = largeImageKey,
        largeImageText = largeImageText,
        smallImageKey = smallImageKey,
        smallImageText = smallImageText,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        buttons = buttons,
    )

    fun mangaPresence(
        name: String,
        details: String,
        state: String,
        largeImageKey: String?,
        largeImageText: String?,
        smallImageKey: String,
        smallImageText: String,
        buttons: List<DiscordPresenceButton>,
    ): DiscordPresence = DiscordPresence(
        name = name,
        type = DiscordActivityType.WATCHING,
        details = details,
        state = state,
        largeImageKey = largeImageKey,
        largeImageText = largeImageText,
        smallImageKey = smallImageKey,
        smallImageText = smallImageText,
        buttons = buttons,
    )
}
