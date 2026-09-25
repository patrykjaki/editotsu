package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

@Deprecated("Use the version with kotlin.time APIs instead.")
fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
) = addInterceptor(RateLimitInterceptor(httpUrl.host, permits, period.toDuration(unit.toDurationUnit())))

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Duration = 1.seconds,
) = addInterceptor(RateLimitInterceptor(httpUrl.host, permits, period))

// Upstream 61e8248b: String-URL overload for Lib16 extensions.
// Review correction: fail fast on malformed input — a null host must
// never silently become a global limiter (null host == global scope).
fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Duration = 1.seconds,
) = addInterceptor(
    RateLimitInterceptor(
        requireNotNull(url.toHttpUrlOrNull()) { "rateLimitHost: malformed URL: $url" }.host,
        permits,
        period
    )
)
