package ani.dantotsu.media

import ani.dantotsu.client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Hand-written watch orders for big franchises, with an AniList id on every entry and the
 * optional ones marked by people, not guessed. One JSON file, refreshed upstream every day;
 * kept on disk for a day and used stale when the network fails.
 */
object WatchOrderWiki {
    private const val URL =
        "https://raw.githubusercontent.com/Bas1874/Anime-Watch-Order-Api/main/data/watch_order_api.json"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val lock = Mutex()
    @Volatile private var index: Map<Int, List<WikiSeries>>? = null
    @Volatile private var loadedAt = 0L

    /** The orders for the franchise holding [anilistId]; empty when it isn't covered, null when the file couldn't be had. */
    suspend fun ordersFor(anilistId: Int, cacheDir: File): List<WikiOrder>? = withContext(Dispatchers.IO) {
        val idx = load(cacheDir) ?: return@withContext null
        watchOrderWikiPick(idx, anilistId)
    }

    private suspend fun load(cacheDir: File): Map<Int, List<WikiSeries>>? = lock.withLock {
        val now = System.currentTimeMillis()
        index?.takeIf { now - loadedAt < MAX_AGE_MS }?.let { return@withLock it }
        val file = File(cacheDir, "watch_order_wiki.json")
        val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
        val text = (if (fresh) file.readText() else null)
            ?: runCatching { download() }.onSuccess { body ->
                runCatching { file.writeText(body) }
            }.getOrNull()
            ?: file.takeIf { it.exists() }?.readText()
            ?: return@withLock index
        val parsed = runCatching { watchOrderWikiIndex(text, json) }.getOrNull() ?: return@withLock index
        index = parsed
        loadedAt = now
        parsed
    }

    private suspend fun download(): String {
        val res = client.get(URL)
        if (res.code !in 200..299) throw IOException("watch order list: HTTP ${res.code}")
        return res.text
    }
}

@Serializable
internal data class WikiFile(val data: List<WikiSeries> = emptyList())

@Serializable
data class WikiSeries(
    val title: String = "",
    @SerialName("watch_orders") val orders: List<WikiOrder> = emptyList(),
)

@Serializable
data class WikiOrder(val name: String = "", val description: String? = null, val steps: List<WikiStep> = emptyList())

@Serializable
data class WikiStep(
    @SerialName("step_title") val title: String = "",
    @SerialName("is_optional") val optional: Boolean = false,
    val media: WikiMedia? = null,
)

@Serializable
data class WikiMedia(
    val id: Int = 0,
    val idMal: Int? = null,
    val title: WikiTitle? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val seasonYear: Int? = null,
    val coverImage: WikiCover? = null,
)

@Serializable
data class WikiTitle(val userPreferred: String? = null, val romaji: String? = null)

@Serializable
data class WikiCover(val large: String? = null, val extraLarge: String? = null)

/** AniList id -> every franchise that lists it. */
internal fun watchOrderWikiIndex(text: String, json: Json = Json { ignoreUnknownKeys = true }): Map<Int, List<WikiSeries>> {
    val file = json.decodeFromString(WikiFile.serializer(), text)
    val out = HashMap<Int, MutableList<WikiSeries>>()
    for (series in file.data) {
        series.orders.flatMap { it.steps }.mapNotNull { it.media?.id?.takeIf { id -> id > 0 } }.toSet()
            .forEach { out.getOrPut(it) { mutableListOf() }.add(series) }
    }
    return out
}

/** The franchise whose orders hold the id most often (a crossover listed in two keeps its own), orders with entries only. */
internal fun watchOrderWikiPick(index: Map<Int, List<WikiSeries>>, anilistId: Int): List<WikiOrder> {
    val series = index[anilistId].orEmpty().maxByOrNull { s ->
        s.orders.sumOf { o -> o.steps.count { it.media?.id == anilistId } }
    } ?: return emptyList()
    return series.orders.filter { o -> o.steps.any { it.media != null } }
}

/** AniList's format enum in the words the sheet already uses. */
internal fun watchOrderFormat(format: String?): String = when (format) {
    null -> ""
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * The titles of the order's own "A -> B -> C" chain at the top of its text. Every link on the wiki page
 * becomes a step, so recap movies and side stories named in the notes after the chain come through as
 * steps too, and none of them carry an optional flag; the chain is what the page says to watch.
 */
internal fun watchOrderWikiChain(description: String?): Set<String> {
    val lines = description.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 3 || lines[1] != "->") return emptySet()
    val chain = mutableListOf(lines[0])
    var i = 1
    while (i + 1 < lines.size && lines[i] == "->") {
        chain += lines[i + 1]
        i += 2
    }
    return chain.map { watchOrderWikiKey(it) }.toSet()
}

private fun watchOrderWikiKey(title: String) = title.lowercase().replace(Regex("""\s*\[[^]]*]\s*"""), "").replace(Regex("""\s+"""), " ").trim()

internal fun watchOrderWikiItems(order: WikiOrder, currentId: Int): List<MediaDetailsViewModel.WatchOrderItem> {
    var currentSeen = false
    val chain = watchOrderWikiChain(order.description)
    // trust the chain only when it names at least two of the steps
    val useChain = order.steps.count { watchOrderWikiKey(it.title) in chain } >= 2
    return order.steps.mapNotNull { step ->
        val m = step.media?.takeIf { it.id > 0 } ?: return@mapNotNull null
        val current = !currentSeen && m.id == currentId
        if (current) currentSeen = true
        MediaDetailsViewModel.WatchOrderItem(
            id = (m.idMal ?: 0).toString(),
            anilistId = m.id.toString(),
            image = m.coverImage?.large ?: m.coverImage?.extraLarge ?: "",
            name = m.title?.userPreferred ?: m.title?.romaji ?: step.title,
            relationType = "",
            isCurrent = current,
            airDate = m.seasonYear?.toString() ?: "",
            mediaType = watchOrderFormat(m.format),
            episodes = m.episodes?.let { "$it eps" } ?: "",
            mainStory = if (useChain) watchOrderWikiKey(step.title) in chain && !step.optional else !step.optional,
        )
    }
}
