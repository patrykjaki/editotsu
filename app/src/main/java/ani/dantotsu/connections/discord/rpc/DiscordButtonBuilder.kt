package ani.dantotsu.connections.discord.rpc

/**
 * Pure (Android-free, JVM-testable) construction of the Rich Presence deep-link buttons.
 *
 * Discord allows at most two buttons. The final pair is **AniList + MyAnimeList**, both fail-closed:
 *  - AniList button is emitted only when the AniList media id is valid (`> 0`), so we never build a
 *    `https://anilist.co/.../0/` URL.
 *  - MyAnimeList button is emitted only when a valid MAL id (`> 0`) is known.
 * This avoids synthesizing invalid `/0` profile links (review v2, product polish).
 */
object DiscordButtonBuilder {

    fun mediaButtons(anilistId: Int, kind: String, malId: Int?): List<DiscordPresenceButton> {
        val buttons = mutableListOf<DiscordPresenceButton>()
        if (anilistId > 0) {
            buttons.add(DiscordPresenceButton("View on AniList", "https://anilist.co/$kind/$anilistId/"))
        }
        val mal = malId
        if (mal != null && mal > 0) {
            buttons.add(DiscordPresenceButton("View on MyAnimeList", "https://myanimelist.net/$kind/$mal"))
        }
        return buttons
    }
}
