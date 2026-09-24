package eu.kanade.tachiyomi.network.interceptor

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upstream 8966c9bd subset: AnilistInterceptor must attach Referer/Origin to
 * *.anilist.co requests (AniList 403 enforcement) without touching others.
 * Runs through a real OkHttpClient with a recording terminal interceptor,
 * so no network is used.
 */
class AnilistInterceptorTest {

    private fun intercepted(url: String, vararg headers: Pair<String, String>): Request {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(AnilistInterceptor())
            .addInterceptor { chain ->
                seen.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().close()
        check(seen.size == 1)
        return seen.single()
    }

    @Test
    fun `adds Referer and Origin to anilist host`() {
        val out = intercepted("https://graphql.anilist.co/")
        assertEquals("https://anilist.co/", out.header("Referer"))
        assertEquals("https://anilist.co", out.header("Origin"))
    }

    @Test
    fun `preserves caller-supplied Referer and Origin`() {
        val out = intercepted(
            "https://graphql.anilist.co/",
            "Referer" to "https://example.test/",
            "Origin" to "https://example.test"
        )
        assertEquals("https://example.test/", out.header("Referer"))
        assertEquals("https://example.test", out.header("Origin"))
    }

    @Test
    fun `leaves non-anilist hosts untouched`() {
        val out = intercepted("https://api.jikan.moe/v4/anime/1/full")
        assertNull(out.header("Referer"))
        assertNull(out.header("Origin"))
    }
}
