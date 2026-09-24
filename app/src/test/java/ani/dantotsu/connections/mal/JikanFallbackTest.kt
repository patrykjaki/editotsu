package ani.dantotsu.connections.mal

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream 5ffa682b subset: JikanQueries must try mirrors in order
 * (primary → midnightignite → tenrai) and fail closed when all fail.
 * Uses the local testHttpHandler seam; no live network.
 */
class JikanFallbackTest {

    private val requestedUrls = mutableListOf<String>()

    @After
    fun tearDown() {
        JikanQueries.testHttpHandler = null
        requestedUrls.clear()
    }

    private fun scriptedHandler(vararg codes: Int) {
        val queue = codes.toMutableList()
        JikanQueries.testHttpHandler = { url ->
            requestedUrls.add(url)
            val code = queue.removeFirstOrNull() ?: 500
            val body = if (code in 200..299) """{"data":[]}""" else ""
            Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error $code")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
                .let { com.lagradost.nicehttp.NiceResponse(it, ani.dantotsu.Mapper) }
        }
    }

    @Test
    fun `mirrors are tried in order until success`() = runBlocking {
        scriptedHandler(429, 429, 200)

        val res = JikanQueries().search("naruto")

        assertTrue(res != null)
        assertEquals(3, requestedUrls.size)
        assertTrue(requestedUrls[0].startsWith(JikanQueries.apiUrls[0]))
        assertTrue(requestedUrls[1].startsWith(JikanQueries.apiUrls[1]))
        assertTrue(requestedUrls[2].startsWith(JikanQueries.apiUrls[2]))
    }

    @Test
    fun `all mirrors failing returns null`() = runBlocking {
        scriptedHandler(429, 500, 429)

        val res = JikanQueries().search("naruto")

        assertNull(res)
        assertEquals(3, requestedUrls.size)
    }

    @Test
    fun `primary success makes no fallback calls`() = runBlocking {
        scriptedHandler(200)

        val res = JikanQueries().search("naruto")

        assertTrue(res != null)
        assertEquals(1, requestedUrls.size)
        assertTrue(requestedUrls[0].startsWith("https://api.jikan.moe/v4"))
    }
}
