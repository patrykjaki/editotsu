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

private val wikiArrow = Regex("""^(?:\(([^)]*)\)\s*)?(?:-{1,2}>|→|>)\s*(.*)$""")
private val wikiTag = Regex("""^\(([^)]*)\)$""")

/**
 * The order's own "A -> B -> C" chain in its text: title key -> true when the chain tags it
 * ("(Optional) ->", "(Spinoff) ->", a closing "(Optional)"), false for a plain link. Every link on the wiki
 * page becomes a step and the scraped optional flags are unreliable, so the chain is what the page says
 * to watch. It may start after an "Alternative Titles" line; arrows are ->, -->, > or →.
 */
internal fun watchOrderWikiChain(description: String?): Map<String, Boolean> {
    val lines = description.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val start = (0 until lines.size - 1).firstOrNull { !wikiArrow.matches(lines[it]) && wikiArrow.matches(lines[it + 1]) }
        ?: return emptyMap()
    val items = mutableListOf(lines[start])
    val tagged = mutableSetOf<Int>()
    var j = start + 1
    while (j < lines.size) {
        val arrow = wikiArrow.matchEntire(lines[j])
        if (arrow != null) {
            if (arrow.groupValues[1].isNotEmpty()) tagged += items.lastIndex
            val rest = arrow.groupValues[2]
            if (rest.isNotEmpty()) { items += rest; j++; continue }
            if (j + 1 < lines.size && !wikiArrow.matches(lines[j + 1])) { items += lines[j + 1]; j += 2; continue }
            break
        }
        if (wikiTag.matches(lines[j])) tagged += items.lastIndex
        break
    }
    val out = LinkedHashMap<String, Boolean>()
    items.forEachIndexed { i, t -> out.putIfAbsent(watchOrderWikiKey(t), i in tagged) }
    return out
}

// the [TV] / [OVA] tags stay: "Amagami SS [TV]" and "Amagami SS [OVA]" are different links
private fun watchOrderWikiKey(title: String) = title.lowercase().replace(Regex("""\s+"""), " ").trim()

internal fun watchOrderWikiItems(order: WikiOrder, currentId: Int): List<MediaDetailsViewModel.WatchOrderItem> {
    var currentSeen = false
    val chain = watchOrderWikiChain(order.description)
    // trust the chain only when it names at least two of the steps
    val useChain = order.steps.count { watchOrderWikiKey(it.title) in chain } >= 2
    // flags set on every step say nothing
    val flagsMean = !order.steps.all { it.optional }
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
            placed = !useChain || watchOrderWikiKey(step.title) in chain,
            mainStory = when {
                "(optional" in step.title.lowercase() -> false
                useChain -> chain[watchOrderWikiKey(step.title)] == false
                else -> !flagsMean || !step.optional
            },
        )
    }
}
