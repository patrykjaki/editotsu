package ani.dantotsu.parsers

import ani.dantotsu.Lazier
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga

abstract class WatchSources : BaseSources() {

    override operator fun get(i: Int): AnimeParser {
        return (list.getOrNull(i) ?: list.firstOrNull())?.get?.value as? AnimeParser
            ?: EmptyAnimeParser()
    }

    fun isDownloadedSource(i: Int): Boolean {
        return get(i) is OfflineAnimeParser
    }

    /**
     * [onCached] gets the last stored list first (if any) while the extension is asked;
     * a failed or empty fetch then keeps that list instead of showing nothing.
     */
    suspend fun loadEpisodesFromMedia(
        i: Int,
        media: Media,
        onCached: ((MutableMap<String, Episode>) -> Unit)? = null
    ): MutableMap<String, Episode> {
        val parser = get(i)
        val offline = parser is OfflineAnimeParser
        val key = parser.saveName.ifBlank { parser.name }
        val cached = if (onCached != null && !offline) episodeCache.load(key, media.id) else null
        if (!cached.isNullOrEmpty()) onCached?.invoke(cached)
        // a source that never answers (a server that is not set up, a dead host) must end in
        // "not found", not in a list that loads for as long as its retries last
        val loaded = kotlinx.coroutines.withTimeoutOrNull(LIST_LOAD_LIMIT_MS) {
            tryWithSuspend(true) {
                val res = parser.autoSearch(media) ?: return@tryWithSuspend mutableMapOf()
                loadEpisodes(i, res.link, res.extra, res.sAnime)
            }
        } ?: mutableMapOf()
        if (loaded.isNotEmpty() && !offline) episodeCache.save(key, media.id, loaded)
        return if (loaded.isEmpty() && !cached.isNullOrEmpty()) cached else loaded
    }

    companion object {
        val episodeCache = ListCache<Episode> {
            ani.dantotsu.App.context?.cacheDir?.let { java.io.File(it, "episode_lists") }
        }
    }

    suspend fun loadEpisodes(
        i: Int,
        showLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime?
    ): MutableMap<String, Episode> {
        val map = mutableMapOf<String, Episode>()
        val parser = get(i)
        val actualAnime = sAnime ?: SAnime.create().apply {
            url = showLink
            title = ""
        }
        tryWithSuspend(true) {
            parser.loadEpisodes(showLink, extra, actualAnime).forEach {
                val key = if (it.sEpisode?.scanlator.isNullOrBlank()) it.number else "${it.number}-${it.sEpisode?.scanlator}"
                map[key] = Episode(
                    it.number,
                    it.link,
                    it.title,
                    it.description,
                    it.thumbnail,
                    it.isFiller,
                    extra = it.extra,
                    sEpisode = it.sEpisode
                )
            }
        }
        return map
    }

}

abstract class MangaReadSources : BaseSources() {

    override operator fun get(i: Int): MangaParser {
        return (list.getOrNull(i) ?: list.firstOrNull())?.get?.value as? MangaParser
            ?: EmptyMangaParser()
    }

    /** Same as [WatchSources.loadEpisodesFromMedia], for chapters. */
    suspend fun loadChaptersFromMedia(
        i: Int,
        media: Media,
        onCached: ((MutableMap<String, MangaChapter>) -> Unit)? = null
    ): MutableMap<String, MangaChapter> {
        val parser = get(i)
        val offline = parser is OfflineMangaParser
        val key = parser.saveName.ifBlank { parser.name }
        val cached = if (onCached != null && !offline) chapterCache.load(key, media.id) else null
        if (!cached.isNullOrEmpty()) onCached?.invoke(cached)
        val loaded = kotlinx.coroutines.withTimeoutOrNull(LIST_LOAD_LIMIT_MS) {
            tryWithSuspend(true) {
                val res = parser.autoSearch(media) ?: return@tryWithSuspend mutableMapOf()
                loadChapters(i, res)
            }
        } ?: mutableMapOf()
        if (loaded.isNotEmpty() && !offline) chapterCache.save(key, media.id, loaded)
        return if (loaded.isEmpty() && !cached.isNullOrEmpty()) cached else loaded
    }

    companion object {
        val chapterCache = ListCache<MangaChapter> {
            ani.dantotsu.App.context?.cacheDir?.let { java.io.File(it, "chapter_lists") }
        }
    }

    suspend fun loadChapters(i: Int, show: ShowResponse): MutableMap<String, MangaChapter> {
        val map = mutableMapOf<String, MangaChapter>()
        val parser = get(i)
        val sManga = show.sManga ?: SManga.create().apply {
            url = show.link
            title = show.name
            thumbnail_url = show.coverUrl.url
        }

        tryWithSuspend(true) {
            parser.loadChapters(show.link, show.extra, sManga).forEach {
                map["${it.number}-${it.scanlator}"] = MangaChapter(it)
            }
        }
        return map
    }
}

abstract class NovelReadSources : BaseSources() {
    override operator fun get(i: Int): NovelParser? {
        return if (list.isNotEmpty()) {
            (list.getOrNull(i) ?: list[0]).get.value as NovelParser
        } else {
            return EmptyNovelParser()
        }
    }

}

class EmptyNovelParser : NovelParser() {
    override val name: String = "Empty"
    override val hostUrl: String = "Empty"
    override val saveName: String = "Empty"

    override val volumeRegex: Regex = Regex("")

    override suspend fun loadBook(link: String, extra: Map<String, String>?): Book {
        return Book("", "", null, emptyList())  // Return an empty Book object or some default value
    }

    override suspend fun search(query: String): List<ShowResponse> {
        return listOf() // Return an empty list or some default value
    }
}

abstract class BaseSources {
    abstract val list: List<Lazier<BaseParser>>

    val names: List<String> get() = list.map { it.name }

    fun flushText() {
        list.forEach {
            if (it.get.isInitialized())
                it.get.value?.showUserText = ""
        }
    }

    open operator fun get(i: Int): BaseParser? {
        return list[i].get.value
    }

    fun saveResponse(i: Int, mediaId: Int, response: ShowResponse) {
        get(i)?.saveShowResponse(mediaId, response, true)
    }
}

/** How long a source gets to find a title and list its episodes or chapters. */
internal const val LIST_LOAD_LIMIT_MS = 40_000L
