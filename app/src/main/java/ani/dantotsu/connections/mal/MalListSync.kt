package ani.dantotsu.connections.mal

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.ScoreFormats
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.delay

/**
 * Makes one list follow the other. The user picks which list is the main one; every entry of
 * the main list that the other list lacks, or holds with another status, progress, score or
 * date, is written to the other list. Entries that exist only on the other list are left
 * alone, and the main list is never changed.
 */
object MalListSync {
    enum class Side { ANILIST, MAL }

    /** a list entry in one vocabulary, whichever service it came from */
    data class Entry(
        val idMal: Int,
        val title: String,
        /** AniList words: CURRENT, PLANNING, COMPLETED, PAUSED, DROPPED, REPEATING */
        val status: String,
        val progress: Int,
        /** whole points, 0-10; 0 = not scored */
        val score: Int,
        val start: FuzzyDate = FuzzyDate(),
        val end: FuzzyDate = FuzzyDate(),
        /** episodes or chapters the title has, when the service knows */
        val total: Int? = null,
        val anilistId: Int? = null,
        val repeat: Int = 0,
        val cover: String? = null,
    )

    enum class What { STATUS, PROGRESS, SCORE, START, END }

    data class Field(val what: What, val from: String, val to: String)

    /**
     * One entry to write. [target] is what the other list holds now (null: it lacks the entry),
     * [fields] what differs, [progress] the progress to write.
     */
    data class Change(val isAnime: Boolean, val main: Entry, val target: Entry?, val fields: List<Field>, val progress: Int)

    fun fromMalStatus(status: String?, repeating: Boolean): String = when {
        repeating -> "REPEATING"
        status == "watching" || status == "reading" -> "CURRENT"
        status == "plan_to_watch" || status == "plan_to_read" -> "PLANNING"
        status == "completed" -> "COMPLETED"
        status == "on_hold" -> "PAUSED"
        status == "dropped" -> "DROPPED"
        else -> "CURRENT"
    }

    /** "2026-11-14", "2026-11" or "2026", as MyAnimeList writes dates */
    fun parseDate(s: String?): FuzzyDate {
        val parts = s.orEmpty().split('-').map { it.toIntOrNull() }
        return FuzzyDate(parts.getOrNull(0), parts.getOrNull(1), parts.getOrNull(2))
    }

    /**
     * The progress the other list should show. A completed title counts as fully seen there
     * even when the two services disagree on its episode count.
     */
    fun wantedProgress(main: Entry, target: Entry?): Int {
        val total = target?.total?.takeIf { it > 0 }
        return if (main.status == "COMPLETED" && total != null) total else main.progress
    }

    fun diff(main: Entry, target: Entry): List<Field> = buildList {
        if (main.status != target.status) add(Field(What.STATUS, target.status, main.status))
        val progress = wantedProgress(main, target)
        if (progress != target.progress) add(Field(What.PROGRESS, target.progress.toString(), progress.toString()))
        if (main.score != target.score) add(Field(What.SCORE, target.score.toString(), main.score.toString()))
        // a date is copied, never erased: an empty one on the main list leaves the other alone
        if (!main.start.isEmpty() && main.start != target.start) add(Field(What.START, target.start.toString(), main.start.toString()))
        if (!main.end.isEmpty() && main.end != target.end) add(Field(What.END, target.end.toString(), main.end.toString()))
    }

    /** what to write so [target] follows [main]; a title listed twice on the main list counts once */
    fun changes(isAnime: Boolean, main: List<Entry>, target: List<Entry>): List<Change> {
        val held = target.associateBy { it.idMal }
        return main.distinctBy { it.idMal }.mapNotNull { m ->
            val t = held[m.idMal]
            val fields = if (t == null) emptyList() else diff(m, t)
            if (t != null && fields.isEmpty()) null else Change(isAnime, m, t, fields, wantedProgress(m, t))
        }
    }

    /**
     * Both lists of one kind, or null when either could not be read in full: a half-read list
     * would make every entry missing from it look new.
     */
    suspend fun load(isAnime: Boolean, mainSide: Side): List<Change>? {
        val aniList = aniListEntries(isAnime) ?: return null
        val mal = malEntries(isAnime) ?: return null
        return if (mainSide == Side.ANILIST) changes(isAnime, aniList, mal) else changes(isAnime, mal, aniList)
    }

    private suspend fun aniListEntries(isAnime: Boolean): List<Entry>? {
        val userId = Anilist.userid
            ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull()
            ?: return null
        val lists = runCatching { Anilist.query.getMediaLists(isAnime, userId) }.getOrNull() ?: return null
        return lists.filterKeys { it != "Favourites" }.values.flatten().mapNotNull { m ->
            val id = m.idMAL ?: return@mapNotNull null
            val status = m.userStatus ?: return@mapNotNull null
            Entry(
                id, m.userPreferredName, status, m.userProgress ?: 0, ScoreFormats.toMal(m.userScore),
                m.userStartedAt, m.userCompletedAt,
                total = if (isAnime) m.anime?.totalEpisodes else m.manga?.totalChapters,
                anilistId = m.id, repeat = m.userRepeat, cover = m.cover,
            )
        }
    }

    private suspend fun malEntries(isAnime: Boolean): List<Entry>? {
        val out = ArrayList<Entry>()
        var offset = 0
        while (true) {
            val page = (
                if (isAnime) MAL.query.getUserAnimeList(limit = PAGE, offset = offset)
                else MAL.query.getUserMangaList(limit = PAGE, offset = offset)
                ) ?: return null
            page.data.forEach { e ->
                val s = e.listStatus ?: return@forEach
                out += Entry(
                    e.node.id, e.node.title,
                    fromMalStatus(s.status, if (isAnime) s.isRewatching else s.isRereading),
                    (if (isAnime) s.numEpisodesWatched else s.numChaptersRead) ?: 0,
                    s.score, parseDate(s.startDate), parseDate(s.finishDate),
                    total = if (isAnime) e.node.numEpisodes else e.node.numChapters,
                    cover = e.node.mainPicture?.medium,
                )
            }
            if (page.paging?.next == null) break
            offset += PAGE
        }
        return out
    }

    /** Writes one change to the list that follows; false when the service did not accept it. */
    suspend fun write(change: Change, mainSide: Side): Boolean {
        val m = change.main
        return if (mainSide == Side.ANILIST) {
            val ok = MAL.query.editList(
                m.idMal, change.isAnime, change.progress, m.score * 10, m.status,
                m.repeat.takeIf { it > 0 }, m.start, m.end,
            )
            delay(MAL_GAP_MS)
            ok
        } else {
            val id = change.target?.anilistId
                ?: runCatching { Anilist.query.getMedia(m.idMal, mal = true, type = if (change.isAnime) "ANIME" else "MANGA")?.id }.getOrNull()
                ?: return false
            val ok = runCatching {
                Anilist.mutation.editList(
                    id, progress = change.progress, score = m.score * 10, status = m.status,
                    startedAt = m.start.takeIf { !it.isEmpty() }, completedAt = m.end.takeIf { !it.isEmpty() },
                )
            }.getOrDefault(false)
            delay(ANILIST_GAP_MS)
            ok
        }
    }

    private const val PAGE = 100
    private const val MAL_GAP_MS = 400L
    // AniList allows 30 requests a minute while its limit is reduced
    private const val ANILIST_GAP_MS = 2100L
}
