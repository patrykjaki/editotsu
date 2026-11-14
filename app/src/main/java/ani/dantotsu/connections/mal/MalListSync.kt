package ani.dantotsu.connections.mal

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.ScoreFormats
import ani.dantotsu.connections.anilist.api.FuzzyDate
import kotlinx.coroutines.delay

/**
 * Copies the AniList lists onto MyAnimeList: every AniList entry that MyAnimeList lacks, or
 * holds with another status, progress or score, is written there. One way only. Entries that
 * exist only on MyAnimeList are left alone, and nothing on AniList is changed.
 */
object MalListSync {
    /** an AniList list entry of a title MyAnimeList knows ([idMal]) */
    data class Local(
        val idMal: Int,
        /** AniList status: CURRENT, PLANNING, COMPLETED, PAUSED, DROPPED, REPEATING */
        val status: String,
        val progress: Int,
        /** raw AniList score, 0-100; 0 = not scored */
        val score: Int,
        val repeat: Int = 0,
        val start: FuzzyDate = FuzzyDate(),
        val end: FuzzyDate = FuzzyDate(),
    )

    /** the same title as MyAnimeList holds it */
    data class Remote(val status: String?, val progress: Int, val score: Int, val repeating: Boolean)

    /** what MyAnimeList calls an AniList status; REPEATING has no status there, it is a flag */
    fun malStatus(isAnime: Boolean, status: String): String? = when (status) {
        "CURRENT" -> if (isAnime) "watching" else "reading"
        "PLANNING" -> if (isAnime) "plan_to_watch" else "plan_to_read"
        "COMPLETED" -> "completed"
        "PAUSED" -> "on_hold"
        "DROPPED" -> "dropped"
        else -> null
    }

    fun differs(local: Local, remote: Remote?, isAnime: Boolean): Boolean {
        if (remote == null) return true
        if (local.progress != remote.progress) return true
        if (ScoreFormats.toMal(local.score) != remote.score) return true
        if (local.status == "REPEATING") return !remote.repeating
        return remote.repeating || malStatus(isAnime, local.status) != remote.status
    }

    /** the entries to write, in list order; an AniList title listed twice (custom lists) counts once */
    fun plan(locals: List<Local>, remote: Map<Int, Remote>, isAnime: Boolean): List<Local> =
        locals.distinctBy { it.idMal }.filter { differs(it, remote[it.idMal], isAnime) }

    class Loaded(val isAnime: Boolean, val toWrite: List<Local>, val onAniList: Int)

    /**
     * Reads both lists and works out what differs. Null when either side could not be read in
     * full: a half-read MyAnimeList list would make every missing entry look new.
     */
    suspend fun load(isAnime: Boolean): Loaded? {
        val userId = Anilist.userid ?: return null
        val lists = runCatching { Anilist.query.getMediaLists(isAnime, userId) }.getOrNull() ?: return null
        val locals = lists.filterKeys { it != "Favourites" }.values.flatten().mapNotNull { m ->
            val id = m.idMAL ?: return@mapNotNull null
            val status = m.userStatus ?: return@mapNotNull null
            Local(id, status, m.userProgress ?: 0, m.userScore, m.userRepeat, m.userStartedAt, m.userCompletedAt)
        }
        if (locals.isEmpty()) return null
        val remote = HashMap<Int, Remote>()
        var offset = 0
        while (true) {
            val page = (
                if (isAnime) MAL.query.getUserAnimeList(limit = PAGE, offset = offset)
                else MAL.query.getUserMangaList(limit = PAGE, offset = offset)
                ) ?: return null
            page.data.forEach { e ->
                val s = e.listStatus ?: return@forEach
                remote[e.node.id] = Remote(
                    s.status,
                    (if (isAnime) s.numEpisodesWatched else s.numChaptersRead) ?: 0,
                    s.score,
                    if (isAnime) s.isRewatching else s.isRereading,
                )
            }
            if (page.paging?.next == null) break
            offset += PAGE
        }
        return Loaded(isAnime, plan(locals, remote, isAnime), locals.distinctBy { it.idMal }.size)
    }

    /** Writes [items] one at a time; returns how many MyAnimeList did not accept. */
    suspend fun write(isAnime: Boolean, items: List<Local>, onProgress: (done: Int) -> Unit): Int {
        var failed = 0
        items.forEachIndexed { i, e ->
            val ok = MAL.query.editList(e.idMal, isAnime, e.progress, e.score, e.status, e.repeat, e.start, e.end)
            if (!ok) failed++
            onProgress(i + 1)
            delay(WRITE_GAP_MS)
        }
        return failed
    }

    private const val PAGE = 100
    private const val WRITE_GAP_MS = 350L
}
