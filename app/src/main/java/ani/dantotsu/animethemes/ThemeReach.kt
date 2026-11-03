package ani.dantotsu.animethemes

import ani.dantotsu.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What the AnimeThemes CDN answered to a one-byte request, checked before mpv opens
 * the video. mpv waits 20 s per attempt on a dead host and reports nothing useful;
 * this check answers in at most [ThemeReach.TIMEOUT_S] seconds and tells a rate limit
 * from an outage.
 */
sealed interface ThemeReachResult {
    data object Ok : ThemeReachResult
    /** 429, or 503 with Retry-After: the CDN asks to come back later. */
    data class RateLimited(val retryAfterS: Int) : ThemeReachResult
    /** Any other HTTP error (404, 5xx). */
    data class HttpError(val code: Int) : ThemeReachResult
    /** No answer in time, DNS failure, connection reset. */
    data object Unreachable : ThemeReachResult
}

object ThemeReach {
    const val TIMEOUT_S = 8L
    const val DEFAULT_RETRY_AFTER_S = 10
    const val MAX_RETRY_AFTER_S = 60

    private val client by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S + 2, TimeUnit.SECONDS)
            .build()
    }

    /** Local files and content uris are always reachable. */
    fun needsCheck(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    suspend fun check(url: String, headers: Map<String, String> = emptyMap()): ThemeReachResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            try {
                client.newCall(request).execute().use { classify(it.code, it.header("Retry-After")) }
            } catch (e: IOException) {
                ThemeReachResult.Unreachable
            } catch (e: IllegalArgumentException) {
                ThemeReachResult.HttpError(0)
            }
        }

    fun classify(code: Int, retryAfter: String?): ThemeReachResult = when {
        code in 200..299 -> ThemeReachResult.Ok
        code == 429 -> ThemeReachResult.RateLimited(retryAfterSeconds(retryAfter) ?: DEFAULT_RETRY_AFTER_S)
        code == 503 && retryAfterSeconds(retryAfter) != null -> ThemeReachResult.RateLimited(retryAfterSeconds(retryAfter)!!)
        else -> ThemeReachResult.HttpError(code)
    }

    /** Retry-After in seconds (the HTTP-date form is ignored), clamped to 1..[MAX_RETRY_AFTER_S]. */
    fun retryAfterSeconds(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.coerceIn(1, MAX_RETRY_AFTER_S)
}
