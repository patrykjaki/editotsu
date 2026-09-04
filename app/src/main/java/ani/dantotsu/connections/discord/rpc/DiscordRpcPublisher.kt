package ani.dantotsu.connections.discord.rpc

import android.content.Context
import android.util.Log
import ani.dantotsu.R
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Phase 2 tokenless presence publisher (Android glue): routes real Editotsu anime/manga state through the
 * approved clean-room [DiscordRpcClient].
 *
 * - A **single** [DiscordRpcClient] session is shared; ownership is keyed by per-instance [ ] tokens so a
 *   stale screen (same kind or cross kind) cannot clear/overwrite a newer presence (review Blocker 2).
 * - The [DiscordRpcSessionController] is the pure routing/lifecycle brain; this object only builds the
 *   client + scheduler and recreates the session after a teardown (review Blocker 4: a fresh owner may
 *   bind normally).
 * - The user-facing [DiscordRichPresenceEnabled] opt-in is the sole enable gate for presence publishing.
 */
object DiscordRpcPublisher {

    private val ownership = DiscordPresenceOwnership()
    private val lock = Any()

    @Volatile private var client: DiscordRpcClient? = null
    @Volatile private var controller: DiscordRpcSessionController? = null

    init {
        if (ani.dantotsu.BuildConfig.DEBUG) {
            DiscordRpcCodec.logger = object : DiscordRpcLogger {
                override fun log(message: String) { Log.d("DiscordRpcCodec", message) }
                override fun warn(message: String) { Log.w("DiscordRpcCodec", message) }
            }
        }
    }

    fun createAnimeToken(): DiscordPresenceOwnership.OwnerToken =
        ownership.createToken(DiscordPresenceOwnership.OwnerKind.ANIME)

    fun createMangaToken(): DiscordPresenceOwnership.OwnerToken =
        ownership.createToken(DiscordPresenceOwnership.OwnerKind.MANGA)

    // The user-facing opt-in (DiscordRichPresenceEnabled) is the sole enable gate. When false, the
    // controller tears the session down immediately (clear + unbind).
    private fun publishingEnabled(): Boolean =
        PrefManager.getVal(PrefName.DiscordRichPresenceEnabled)

    private fun blocked(context: Context, media: Media?): Boolean {
        if (media == null) return true
        if (!PrefManager.getVal<Boolean>(PrefName.DiscordRichPresenceEnabled)) return true
        if (PrefManager.getVal<Boolean>(PrefName.OfflineMode)) return true
        if (PrefManager.getVal<Boolean>(PrefName.Incognito)) return true
        if (DiscordRpcUtils.shouldSuppressForAdultMedia(media.isAdult)) return true
        return false
    }

    private fun ensureController(context: Context): DiscordRpcSessionController {
        synchronized(lock) {
            val existing = controller
            if (existing != null && client != null) return existing
            val app = context.applicationContext
            val newClient = DiscordRpcClient(app)
            val transport = object : PresenceTransport {
                override fun setPresence(presence: DiscordPresence) = newClient.setPresence(presence)
                override fun clearPresence() = newClient.clearPresence()
                override fun shutdown() = newClient.shutdown()
            }
            val coordinator = DiscordPresenceCoordinator(
                sender = transport::setPresence,
                clock = { System.currentTimeMillis() },
                scheduler = AndroidPresenceScheduler(),
            )
            val ctrl = DiscordRpcSessionController(
                transport = transport,
                ownership = ownership,
                coordinator = coordinator,
                isEnabled = { publishingEnabled() },
            )
            client = newClient
            controller = ctrl
            return ctrl
        }
    }

    private fun dropController() {
        synchronized(lock) {
            client = null
            controller = null
        }
    }

    fun publishAnime(
        token: DiscordPresenceOwnership.OwnerToken,
        context: Context,
        media: Media?,
        episode: Episode?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        if (media == null || episode == null) {
            synchronized(lock) { controller?.suppressClear(token) }
            return
        }
        if (!publishingEnabled()) {
            teardown()
            return
        }
        if (blocked(context, media)) {
            synchronized(lock) { controller?.suppressClear(token) }
            return
        }
        val ctx = context.applicationContext
        val details = episode.title?.takeIf { it.isNotEmpty() }
            ?: ctx.getString(R.string.episode_num, episode.number)
        val total = media.anime?.totalEpisodes ?: "??"
        val episodeState = "Episode : ${episode.number}/$total"
        val state = if (!isPlaying) "Paused - $episodeState" else episodeState

        val (start, end) = if (isPlaying && durationMs > 0) {
            val now = System.currentTimeMillis()
            val pos = if (positionMs > 0) positionMs else 0L
            (now - pos) to (now - pos) + durationMs
        } else null to null

        val presence = DiscordPresenceMapper.animePresence(
            name = media.userPreferredName,
            details = details,
            state = state,
            largeImageKey = DiscordCoverResolver.resolveLargeImage(media.cover),
            largeImageText = media.userPreferredName,
            smallImageKey = if (isPlaying) DiscordPresenceMapper.ANIME_SMALL_IMAGE_URL
                else DiscordPresenceMapper.ANIME_PAUSED_SMALL_IMAGE_URL,
            smallImageText = if (isPlaying) DiscordPresenceMapper.ANIME_SMALL_TEXT
                else DiscordPresenceMapper.ANIME_PAUSED_SMALL_TEXT,
            startTimestamp = start,
            endTimestamp = end,
            buttons = DiscordButtonBuilder.mediaButtons(media.id, "anime", media.idMAL),
        )
        if (ani.dantotsu.BuildConfig.DEBUG) {
            Log.d(
                "DiscordRpcPublisher",
                "publish source anime name='${media.userPreferredName}' ep='${episode.number}' " +
                    "title='${episode.title}' posMs=$positionMs durMs=$durationMs playing=$isPlaying " +
                    "cover='${redactUrlForLog(media.cover)}' resolvedCover='${redactUrlForLog(presence.largeImageKey)}' " +
                    "smallImageKey='${presence.smallImageKey}' adult=${media.isAdult}",
            )
        }
        ensureController(ctx).publish(token, presence)
    }

    fun publishManga(
        token: DiscordPresenceOwnership.OwnerToken,
        context: Context,
        media: Media?,
        chapter: MangaChapter?,
    ) {
        if (media == null || chapter == null) {
            synchronized(lock) { controller?.suppressClear(token) }
            return
        }
        if (!publishingEnabled()) {
            teardown()
            return
        }
        if (blocked(context, media)) {
            synchronized(lock) { controller?.suppressClear(token) }
            return
        }
        val ctx = context.applicationContext
        val details = chapter.title?.takeIf { it.isNotEmpty() }
            ?: ctx.getString(R.string.chapter_num, chapter.number)
        val total = media.manga?.totalChapters ?: "??"
        val state = "Chapter : ${chapter.number}/$total"

        val presence = DiscordPresenceMapper.mangaPresence(
            name = media.userPreferredName,
            details = details,
            state = state,
            largeImageKey = DiscordCoverResolver.resolveLargeImage(media.cover),
            largeImageText = media.userPreferredName,
            smallImageKey = DiscordPresenceMapper.MANGA_SMALL_IMAGE_URL,
            smallImageText = DiscordPresenceMapper.MANGA_SMALL_TEXT,
            buttons = DiscordButtonBuilder.mediaButtons(media.id, "manga", media.idMAL),
        )
        if (ani.dantotsu.BuildConfig.DEBUG) {
            Log.d(
                "DiscordRpcPublisher",
                "publish source manga name='${media.userPreferredName}' chap='${chapter.number}' " +
                    "title='${chapter.title}' cover='${redactUrlForLog(media.cover)}' resolvedCover='${redactUrlForLog(presence.largeImageKey)}' " +
                    "smallImageKey='${presence.smallImageKey}' adult=${media.isAdult}",
            )
        }
        ensureController(ctx).publish(token, presence)
    }

    fun clearAnime(token: DiscordPresenceOwnership.OwnerToken) {
        if (!publishingEnabled()) {
            teardown()
            return
        }
        val released = synchronized(lock) { controller?.clear(token) } ?: false
        if (released) dropController()
    }

    fun clearManga(token: DiscordPresenceOwnership.OwnerToken) {
        if (!publishingEnabled()) {
            teardown()
            return
        }
        val released = synchronized(lock) { controller?.clear(token) } ?: false
        if (released) dropController()
    }

    /** User-facing Rich Presence master switch turned OFF (or routing disabled): clear + unbind now. */
    fun teardown() {
        synchronized(lock) { controller?.teardown() }
        dropController()
    }

    fun shutdown() {
        if (!publishingEnabled()) {
            teardown()
            return
        }
        synchronized(lock) { controller?.shutdown() }
        dropController()
    }

    /**
     * Redact query-string / fragment from a URL before it reaches a log line, so signed CDN query values are
     * never retained in on-device logs (review v2 non-blocking follow-up). Host + path are kept for debugging.
     */
    private fun redactUrlForLog(url: String?): String {
        if (url.isNullOrEmpty()) return url ?: "null"
        val q = url.indexOf('?')
        val h = url.indexOf('#')
        val cut = if (q >= 0 && h >= 0) minOf(q, h) else maxOf(q, h)
        return if (cut >= 0) url.substring(0, cut) + "<redacted>" else url
    }
}
