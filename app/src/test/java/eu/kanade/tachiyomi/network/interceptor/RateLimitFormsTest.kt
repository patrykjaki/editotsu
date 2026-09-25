package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Lib16 tranche (upstream 61e8248b, item G): both RateLimit call forms
 * must link and register an interceptor; legacy signatures stay intact.
 * Timing behavior itself is covered by the shared limiter core, unchanged.
 */
class RateLimitFormsTest {

    @Test
    fun `old TimeUnit form still registers`() {
        val client = OkHttpClient.Builder()
            .rateLimit(5)
            .rateLimit(10, 2, TimeUnit.MINUTES)
            .build()
        assertEquals(2, client.interceptors.size)
        assertTrue(client.interceptors.all { it is RateLimitInterceptor })
    }

    @Test
    fun `new Duration form registers`() {
        val client = OkHttpClient.Builder()
            .rateLimit(5)
            .rateLimit(10, 2.minutes)
            .build()
        assertEquals(2, client.interceptors.size)
        assertTrue(client.interceptors.all { it is RateLimitInterceptor })
    }

    @Test
    fun `host overloads register`() {
        val client = OkHttpClient.Builder()
            .rateLimitHost("https://example.test".toHttpUrlOrNull()!!, 5)
            .rateLimitHost("https://example.test", 5, 1.seconds)
            .build()
        assertEquals(2, client.interceptors.size)
    }

    @Test
    fun `sub-second durations clamp instead of crashing`() {
        // coerceAtLeast(1L): zero/negative periods must not break construction.
        val client = OkHttpClient.Builder()
            .rateLimit(5, kotlin.time.Duration.ZERO)
            .build()
        assertEquals(1, client.interceptors.size)
    }
}
