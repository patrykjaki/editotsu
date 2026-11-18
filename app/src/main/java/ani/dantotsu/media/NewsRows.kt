package ani.dantotsu.media

import java.util.Calendar
import java.util.Date

/** What a news link opens: a video gets its thumbnail, the rest an icon. */
internal enum class NewsKind { VIDEO, POST, ARTICLE }

internal fun newsHost(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
        .lowercase().removePrefix("www.").removePrefix("m.")

internal fun newsKind(url: String): NewsKind = when (newsHost(url)) {
    "youtube.com", "youtu.be", "music.youtube.com" -> NewsKind.VIDEO
    "x.com", "twitter.com", "bsky.app", "threads.net" -> NewsKind.POST
    else -> NewsKind.ARTICLE
}

/** Site name under the title; the two common ones by their own name, others by host. */
internal fun newsSite(url: String): String = when (val host = newsHost(url)) {
    "youtube.com", "youtu.be", "music.youtube.com" -> "YouTube"
    "x.com", "twitter.com" -> "X"
    else -> host
}

/** The video id of a watch, short or youtu.be link; null for anything else. */
internal fun youtubeId(url: String): String? {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    val host = newsHost(url)
    val path = uri.path.orEmpty()
    val id = when {
        host == "youtu.be" -> path.trim('/').substringBefore('/')
        host.endsWith("youtube.com") && path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
        host.endsWith("youtube.com") && path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
        host.endsWith("youtube.com") -> uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        else -> null
    }
    return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
}

internal sealed interface NewsRow {
    /** [month] is null for "this week", -1 with year -1 for undated items. */
    data class Header(val year: Int, val month: Int?) : NewsRow
    data class Entry(val item: MediaDetailsViewModel.NewsItem) : NewsRow
}

/**
 * Newest first, under headers: the last 7 days, then one per month, then undated items.
 */
internal fun newsRows(items: List<MediaDetailsViewModel.NewsItem>, nowMs: Long): List<NewsRow> {
    val weekAgo = nowMs - 7L * 24 * 60 * 60 * 1000
    val dated = items.filter { it.date != null }.sortedByDescending { it.date }
    val undated = items.filter { it.date == null }
    val rows = mutableListOf<NewsRow>()
    var last: NewsRow.Header? = null
    val cal = Calendar.getInstance()
    for (item in dated) {
        val t = item.date!!.time
        val header = if (t >= weekAgo) {
            NewsRow.Header(0, null)
        } else {
            cal.time = Date(t)
            NewsRow.Header(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
        }
        if (header != last) {
            rows += header
            last = header
        }
        rows += NewsRow.Entry(item)
    }
    if (undated.isNotEmpty()) {
        if (rows.isNotEmpty()) rows += NewsRow.Header(-1, -1)
        undated.forEach { rows += NewsRow.Entry(it) }
    }
    return rows
}

private fun lettersOnly(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/**
 * Drops a leading quoted show name ("[OSHI NO KO]" Season 4 ...) when it is the show the sheet is
 * for; the sheet already says which show. Anything else is left as it is.
 */
internal fun newsTitleWithoutShow(title: String, showNames: List<String>): String {
    val t = title.trim()
    val open = t.firstOrNull() ?: return title
    val close = when (open) { '"' -> '"'; '\u201C' -> '\u201D'; '\u300C' -> '\u300D'; else -> return title }
    val end = t.indexOf(close, 1).takeIf { it in 1..80 } ?: return title
    val inner = lettersOnly(t.substring(1, end))
    if (inner.isEmpty()) return title
    val matches = showNames.map(::lettersOnly).filter { it.isNotEmpty() }
        .any { it.startsWith(inner) || inner.startsWith(it) }
    if (!matches) return title
    val rest = t.substring(end + 1).trim().trimStart(':', '-', '\u2013', '\u2014').trim()
    return rest.ifEmpty { title }.replaceFirstChar { it.titlecase() }
}

/** The page's own preview picture (og:image, then twitter:image), resolved against [pageUrl]. */
internal fun newsPreviewImage(html: String, pageUrl: String): String? {
    val metas = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
    fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)
    for (key in listOf("og:image", "og:image:url", "twitter:image", "twitter:image:src")) {
        val tag = metas.firstOrNull { m ->
            (attr(m, "property") ?: attr(m, "name"))?.equals(key, ignoreCase = true) == true
        } ?: continue
        val raw = attr(tag, "content")?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: continue
        val abs = runCatching { java.net.URI(pageUrl).resolve(raw).toString() }.getOrNull() ?: continue
        if (abs.startsWith("https://") || abs.startsWith("http://")) return abs
    }
    return null
}

/** Fetches and remembers preview pictures for the news sheet; "" means the page has none. */
internal object NewsPreviews {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun cached(url: String): String? = cache[url]

    /** Reads at most the first 256 KB of the page; the meta tags are in the head. */
    fun fetch(url: String): String? {
        cache[url]?.let { return it.ifEmpty { null } }
        val found = runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .header("Accept", "text/html")
                .build()
            ani.dantotsu.okHttpClient.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val source = res.body?.source() ?: return@use null
                source.request(256L * 1024)
                val bytes = source.buffer.snapshot().let { s -> s.substring(0, minOf(s.size, 256 * 1024)) }
                newsPreviewImage(bytes.utf8(), url)
            }
        }.getOrNull()
        cache[url] = found.orEmpty()
        return found
    }
}
