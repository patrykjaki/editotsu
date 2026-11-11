package ani.dantotsu.connections.anilist

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The AniList login returns through `editotsu://anilist#access_token=…` (or `dantotsu://`).
 * Any app or web page can open that link, so a token is accepted only while a login started
 * from this app is pending: [start] is called when the login page is opened, the callback
 * [consume]s it once, and it expires after [TIMEOUT_MS].
 */
object AnilistLoginGate {
    const val TIMEOUT_MS: Long = 10 * 60 * 1000L

    private val TOKEN_REGEX = Regex("""(?<=access_token=)[^&#]+(?=&token_type)""")

    /** Token from the callback url, or null when it has none. */
    fun parseToken(url: String?): String? =
        url?.let { TOKEN_REGEX.find(it)?.value }?.takeIf { it.isNotBlank() }

    /** True when a login started at [startedAt] may still be completed at [now]. */
    fun isPending(startedAt: Long, now: Long): Boolean =
        startedAt > 0 && now >= startedAt && now - startedAt <= TIMEOUT_MS

    fun start(now: Long = System.currentTimeMillis()) {
        PrefManager.setVal(PrefName.AnilistLoginStartedAt, now)
    }

    /** Clears the pending login and returns whether one was pending. */
    fun consume(now: Long = System.currentTimeMillis()): Boolean {
        val startedAt = PrefManager.getVal<Long>(PrefName.AnilistLoginStartedAt)
        PrefManager.removeVal(PrefName.AnilistLoginStartedAt)
        return isPending(startedAt, now)
    }
}
