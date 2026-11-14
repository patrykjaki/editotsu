package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLinkTest {
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&index=0"

    @Test
    fun `only a torrent release whose source asked for it is looked up`() {
        assertTrue(SourceLink.wanted("""{"editotsu":1,"resolve":true}""", magnet))
        assertFalse(SourceLink.wanted("""{"editotsu":1}""", magnet))
        assertFalse(SourceLink.wanted("""{"editotsu":1,"resolve":"true"}""", magnet))
        assertFalse(SourceLink.wanted("not json", magnet))
        assertFalse(SourceLink.wanted(null, magnet))
        // already a direct link (the answer of an earlier pick): play it, don't ask again
        assertFalse(SourceLink.wanted("""{"editotsu":1,"resolve":true}""", "https://cdn.example/file.mkv"))
    }

    @Test
    fun `only a http link replaces the torrent`() {
        assertTrue(SourceLink.usable("https://cdn.example/file.mkv"))
        assertFalse(SourceLink.usable(magnet))
        assertFalse(SourceLink.usable(""))
        assertFalse(SourceLink.usable(null))
    }

    @Test
    fun `a release the source has ready is marked on the row`() {
        val ready = EditotsuInternalData.parse("""{"editotsu":1,"resolve":true,"instant":true}""")!!
        assertTrue(ready.resolve && ready.instant)
        val unknown = EditotsuInternalData.parse("""{"editotsu":1,"resolve":true}""")!!
        assertEquals(true to false, unknown.resolve to unknown.instant)
    }
}
