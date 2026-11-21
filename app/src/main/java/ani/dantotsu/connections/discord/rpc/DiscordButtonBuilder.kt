package ani.dantotsu.connections.discord.rpc

/**
 * Pure (Android-free, JVM-testable) construction of the Rich Presence buttons.
 *
 * Discord allows at most two: one for the show and one for the app.
 *  - The show button opens AniList when the AniList id is valid (`> 0`), otherwise MyAnimeList
 *    when a valid MAL id is known, so we never build a `/0` link.
 *  - The app button goes to the releases page, not `/releases/latest`: every 0.x tag is a
 *    pre-release, and `latest` skips pre-releases.
 */
object DiscordButtonBuilder {

    const val APP_BUTTON_LABEL = "Get Editotsu"
    const val APP_BUTTON_URL = "https://github.com/patrykjaki/editotsu/releases"

    fun mediaButtons(anilistId: Int, kind: String, malId: Int?): List<DiscordPresenceButton> {
        val noun = if (kind == "manga") "Manga" else "Anime"
        val show = when {
            anilistId > 0 -> DiscordPresenceButton("View $noun", "https://anilist.co/$kind/$anilistId/")
            malId != null && malId > 0 -> DiscordPresenceButton("View $noun", "https://myanimelist.net/$kind/$malId")
            else -> null
        }
        return listOfNotNull(show, DiscordPresenceButton(APP_BUTTON_LABEL, APP_BUTTON_URL))
    }
}
