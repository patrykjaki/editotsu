package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentWatchEntryTest {

    private val hash = "A".repeat(40)

    // ---- button visibility ----

    @Test
    fun `button visible on API 28 plus with add-on enabled`() {
        assertTrue(TorrentWatchEntry.isButtonVisible(28, true))
        assertTrue(TorrentWatchEntry.isButtonVisible(35, true))
    }

    @Test
    fun `button hidden when add-on disabled`() {
        assertFalse(TorrentWatchEntry.isButtonVisible(35, false))
    }

    @Test
    fun `button hidden below API 28 even when enabled`() {
        assertFalse(TorrentWatchEntry.isButtonVisible(27, true))
    }

    // ---- clipboard prefill ----

    @Test
    fun `magnet on clipboard is prefilled trimmed`() {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=x"
        assertEquals(magnet, TorrentWatchEntry.clipboardPrefill("  $magnet\n"))
    }

    @Test
    fun `http torrent url on clipboard is prefilled`() {
        val url = "https://example.org/files/show.torrent"
        assertEquals(url, TorrentWatchEntry.clipboardPrefill(url))
    }

    @Test
    fun `plain text, blank and null are not prefilled`() {
        assertNull(TorrentWatchEntry.clipboardPrefill("hello world"))
        assertNull(TorrentWatchEntry.clipboardPrefill("   "))
        assertNull(TorrentWatchEntry.clipboardPrefill(null))
    }

    @Test
    fun `invalid magnet and content uri are not prefilled`() {
        assertNull(TorrentWatchEntry.clipboardPrefill("magnet:?dn=no-hash"))
        assertNull(TorrentWatchEntry.clipboardPrefill("content://downloads/1/show.torrent"))
        assertNull(TorrentWatchEntry.clipboardPrefill("https://example.org/page.html"))
    }

    // ---- anchor episode ----

    private data class Ep(val number: String)

    private val eps = linkedMapOf("1" to Ep("1"), "2" to Ep("2"), "ep-3" to Ep("3"))

    @Test
    fun `continue key found by map key`() {
        assertEquals(Ep("2"), TorrentWatchEntry.anchorEpisode("2", eps) { it.number })
    }

    @Test
    fun `continue key found by episode number`() {
        assertEquals(Ep("3"), TorrentWatchEntry.anchorEpisode("3", eps) { it.number })
    }

    @Test
    fun `no continue key means unanchored`() {
        assertNull(TorrentWatchEntry.anchorEpisode(null, eps) { it.number })
    }

    @Test
    fun `episodes not loaded means unanchored`() {
        assertNull(TorrentWatchEntry.anchorEpisode<Ep>("1", null) { it.number })
        assertNull(TorrentWatchEntry.anchorEpisode("1", emptyMap<String, Ep>()) { it.number })
    }

    @Test
    fun `stale continue key not in list means unanchored`() {
        assertNull(TorrentWatchEntry.anchorEpisode("9", eps) { it.number })
    }
}
