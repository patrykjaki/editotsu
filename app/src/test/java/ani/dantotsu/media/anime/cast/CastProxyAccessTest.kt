package ani.dantotsu.media.anime.cast

import ani.dantotsu.media.anime.cast.CastProxyAccess.Decision
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProxyAccessTest {
    private val media = "https://cdn.example/show/ep1/master.m3u8"
    private val sub = "https://subs.example/ep1.vtt"
    private val local = "content://ani.dantotsu.provider/downloads/ep1.mkv"
    private val headers = mapOf("Referer" to "https://site.example/")

    private fun opened(): Pair<CastProxyAccess, String> {
        val a = CastProxyAccess()
        a.openSession("192.168.1.20", 19876)
        a.setItem(mapOf(media to headers, sub to headers), listOf(local))
        val token = a.proxyUrl(media)!!.toHttpUrl().queryParameter(CastProxyAccess.TOKEN_PARAM)!!
        return a to token
    }

    @Test
    fun `generated urls carry the token and the target`() {
        val (a, token) = opened()
        val url = a.proxyUrl(media)!!.toHttpUrl()
        assertEquals("192.168.1.20", url.host)
        assertEquals(19876, url.port)
        assertEquals("/proxy", url.encodedPath)
        assertEquals(media, url.queryParameter("url"))
        assertEquals(token, url.queryParameter("t"))
        assertNull("headers are not put in the url", url.queryParameter("header"))
        assertEquals(32, token.length)
        val l = a.localUrl(local)!!.toHttpUrl()
        assertEquals("/local", l.encodedPath)
        assertEquals(token, l.queryParameter("t"))
    }

    @Test
    fun `requests without the right token are forbidden`() {
        val (a, token) = opened()
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", null, media))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", "", media))
        val wrong = token.dropLast(1) + (if (token.endsWith("0")) "1" else "0")
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", wrong, media))
        assertEquals(Decision.FORBIDDEN, a.decide("/local", null, local))
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, media))
    }

    @Test
    fun `only registered media and subtitle urls are proxied`() {
        val (a, token) = opened()
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, media))
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, sub))
        assertEquals(headers, a.proxyHeadersFor(media))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, "http://192.168.1.1/"))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, "https://cdn.example/show/ep2/master.m3u8"))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, null))
    }

    @Test
    fun `only the registered local uri is served`() {
        val (a, token) = opened()
        assertEquals(Decision.ALLOW, a.decide("/local", token, local))
        assertEquals(Decision.FORBIDDEN, a.decide("/local", token, "content://ani.dantotsu.provider/downloads/ep2.mkv"))
        assertEquals(Decision.FORBIDDEN, a.decide("/local", token, "file:///data/data/ani.dantotsu/shared_prefs/x.xml"))
        assertEquals("a local uri is not proxied", Decision.FORBIDDEN, a.decide("/proxy", token, local))
    }

    @Test
    fun `unknown paths are not found even with a token`() {
        val (a, token) = opened()
        assertEquals(Decision.NOT_FOUND, a.decide("/", token, media))
        assertEquals(Decision.FORBIDDEN, a.decide("/", null, media))
    }

    @Test
    fun `urls written into a served manifest become allowed`() {
        val (a, token) = opened()
        val segment = "https://cdn.example/show/ep1/720p/seg-001.ts"
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, segment))
        a.allowDerived(segment, headers)
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, segment))
        assertEquals(headers, a.proxyHeadersFor(segment))
    }

    @Test
    fun `dash templates allow their expansions only`() {
        val (a, token) = opened()
        a.allowTemplate("https://cdn.example/dash/v1/seg-\$Number%05d\$.m4s?k=1", headers)
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, "https://cdn.example/dash/v1/seg-00042.m4s?k=1"))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, "https://cdn.example/dash/v1/seg-1/../../x.m4s?k=1"))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, "https://evil.example/dash/v1/seg-1.m4s?k=1"))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, "https://cdn.example/dash/v1/seg-1.m4s?k=2"))
    }

    @Test
    fun `a new item replaces the old allow list`() {
        val (a, token) = opened()
        val seg = "https://cdn.example/show/ep1/seg-1.ts"
        a.allowDerived(seg, headers)
        val next = "https://cdn.example/show/ep2/master.m3u8"
        a.setItem(mapOf(next to headers), emptyList())
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, next))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, media))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, seg))
        assertEquals(Decision.FORBIDDEN, a.decide("/local", token, local))
    }

    @Test
    fun `a new session rotates the token and clears everything`() {
        val (a, oldToken) = opened()
        a.openSession("192.168.1.20", 19876)
        val newToken = a.proxyUrl(media)!!.toHttpUrl().queryParameter("t")!!
        assertNotEquals(oldToken, newToken)
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", oldToken, media))
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", newToken, media))
    }

    @Test
    fun `closing denies everything and a stale close keeps a newer session`() {
        val a = CastProxyAccess()
        val first = a.openSession("10.0.0.2", 19876)
        val second = a.openSession("10.0.0.2", 19876)
        a.setItem(mapOf(media to headers), emptyList())
        val token = a.proxyUrl(media)!!.toHttpUrl().queryParameter("t")!!
        a.closeSession(first)
        assertTrue(a.isOpen)
        assertEquals(Decision.ALLOW, a.decide("/proxy", token, media))
        a.closeSession(second)
        assertFalse(a.isOpen)
        assertEquals(Decision.FORBIDDEN, a.decide("/proxy", token, media))
        assertNull(a.proxyUrl(media))
        a.allowDerived("https://x.example/a", headers)
        assertNull("nothing is registered while closed", a.proxyHeadersFor("https://x.example/a"))
    }

    @Test
    fun `ensureSession keeps an open session`() {
        val a = CastProxyAccess()
        val gen = a.ensureSession("10.0.0.2", 19876)
        val token = a.proxyUrl(media)!!.toHttpUrl().queryParameter("t")
        assertEquals(gen, a.ensureSession("10.0.0.2", 19876))
        assertEquals(token, a.proxyUrl(media)!!.toHttpUrl().queryParameter("t"))
    }

    @Test
    fun `derived list is bounded`() {
        val a = CastProxyAccess(maxDerived = 3)
        a.openSession("10.0.0.2", 19876)
        (1..5).forEach { a.allowDerived("https://cdn.example/s$it.ts", headers) }
        assertNull(a.proxyHeadersFor("https://cdn.example/s1.ts"))
        assertEquals(headers, a.proxyHeadersFor("https://cdn.example/s5.ts"))
    }
}
