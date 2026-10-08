package ani.dantotsu.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M7A: one-pass adversarial matrix over the manual torrent entry
 * seam. Pure JVM — no Android, no engine, no device.
 */
class ManualTorrentEntryTest {

    // ---- magnet validation ----

    @Test
    fun `valid btih hex magnet normalizes`() {
        val hash = "A".repeat(40)
        val out = MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:$hash&dn=x")
        assertEquals(hash, out)
    }

    @Test
    fun `valid btih base32 magnet converts to hex`() {
        val b32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:$b32")
        assertNotNull(out)
        assertEquals(40, out!!.length)
        assertTrue(out.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `lowercase hex magnet normalizes to uppercase`() {
        val out = MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:${"ab12cd34".repeat(5)}")
        assertEquals("AB12CD34".repeat(5), out)
    }

    @Test
    fun `btmh magnet accepted as opaque hash`() {
        val out = MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btmh:1220abcdef&dn=x")
        assertEquals("1220ABCDEF", out)
    }

    @Test
    fun `magnet without xt rejected`() {
        assertNull(MagnetValidation.normalizeInfoHashOrNull("magnet:?dn=just-a-name"))
    }

    @Test
    fun `magnet with short hash rejected`() {
        assertNull(MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:ABC123"))
    }

    @Test
    fun `magnet with illegal hex chars rejected`() {
        assertNull(MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:${"Z".repeat(40)}"))
    }

    @Test
    fun `non-magnet strings rejected`() {
        assertNull(MagnetValidation.normalizeInfoHashOrNull("https://example.com/x.torrent"))
        assertNull(MagnetValidation.normalizeInfoHashOrNull(""))
        assertNull(MagnetValidation.normalizeInfoHashOrNull(null))
        assertNull(MagnetValidation.normalizeInfoHashOrNull("   "))
        assertNull(MagnetValidation.normalizeInfoHashOrNull("magnet:?xt=urn:btih:"))
    }

    @Test
    fun `isMagnetLink agrees with normalize`() {
        val good = "magnet:?xt=urn:btih:${"B".repeat(40)}"
        assertTrue(MagnetValidation.isMagnetLink(good))
        assertTrue(!MagnetValidation.isMagnetLink("magnet:?dn=no-hash"))
        assertTrue(!MagnetValidation.isMagnetLink(null))
    }

    // ---- intent dispatch allowlist ----

    private val view = "android.intent.action.VIEW"

    @Test
    fun `VIEW magnet allowed`() {
        val uri = "magnet:?xt=urn:btih:${"C".repeat(40)}"
        val payload = ManualTorrentIntentDispatch.classify(view, "magnet", null, uri)
        assertTrue(payload is ManualTorrentPayload.Magnet)
        assertEquals(uri, (payload as ManualTorrentPayload.Magnet).uri)
    }

    @Test
    fun `VIEW magnet with invalid hash rejected`() {
        assertNull(ManualTorrentIntentDispatch.classify(view, "magnet", null, "magnet:?dn=nope"))
    }

    @Test
    fun `VIEW content x-bittorrent allowed`() {
        val payload = ManualTorrentIntentDispatch.classify(
            view, "content", "application/x-bittorrent", "content://prov/x.torrent"
        )
        assertTrue(payload is ManualTorrentPayload.StagedFile)
    }

    @Test
    fun `VIEW file x-bittorrent allowed`() {
        val payload = ManualTorrentIntentDispatch.classify(
            view, "file", "application/x-bittorrent", "file:///sdcard/x.torrent"
        )
        assertTrue(payload is ManualTorrentPayload.StagedFile)
    }

    @Test
    fun `http VIEW never hijacked`() {
        assertNull(
            ManualTorrentIntentDispatch.classify(view, "https", "text/html", "https://example.com/")
        )
        assertNull(
            ManualTorrentIntentDispatch.classify(view, "http", "application/x-bittorrent", "http://example.com/x.torrent")
        )
    }

    @Test
    fun `SEND action rejected even for magnets`() {
        assertNull(
            ManualTorrentIntentDispatch.classify(
                "android.intent.action.SEND", "magnet", null, "magnet:?xt=urn:btih:${"D".repeat(40)}"
            )
        )
    }

    @Test
    fun `generic octet-stream rejected`() {
        assertNull(
            ManualTorrentIntentDispatch.classify(view, "content", "application/octet-stream", "content://prov/x")
        )
    }

    @Test
    fun `null data rejected`() {
        assertNull(ManualTorrentIntentDispatch.classify(view, "magnet", null, null))
        assertNull(ManualTorrentIntentDispatch.classify(null, "magnet", null, "magnet:?xt=urn:btih:x"))
    }

    @Test
    fun `custom schemes rejected`() {
        assertNull(ManualTorrentIntentDispatch.classify(view, "editotsu", null, "editotsu://media/1"))
        assertNull(ManualTorrentIntentDispatch.classify(view, "dantotsu", null, "dantotsu://x"))
    }

    // ---- picker allowlist ----

    @Test
    fun `picker accepts exact mime regardless of name`() {
        assertTrue(ManualTorrentIntentDispatch.isPickableTorrentFile("application/x-bittorrent", "weird.bin"))
    }

    @Test
    fun `picker accepts torrent extension with foreign mime`() {
        assertTrue(ManualTorrentIntentDispatch.isPickableTorrentFile("application/octet-stream", "show.S01.torrent"))
        assertTrue(ManualTorrentIntentDispatch.isPickableTorrentFile(null, "SHOW.TORRENT"))
    }

    @Test
    fun `picker rejects non-torrent names`() {
        assertTrue(!ManualTorrentIntentDispatch.isPickableTorrentFile("application/octet-stream", "video.mp4"))
        assertTrue(!ManualTorrentIntentDispatch.isPickableTorrentFile(null, null))
        assertTrue(!ManualTorrentIntentDispatch.isPickableTorrentFile("video/mp4", "x.torrent.mp4"))
    }

    // ---- default file pick ----

    @Test
    fun `largest file picked by default`() {
        val files = listOf(
            ManualTorrentFileEntry(0, "cd1/sample.mkv", 50L),
            ManualTorrentFileEntry(1, "cd1/ep12.mkv", 900L),
            ManualTorrentFileEntry(2, "cd1/ep12.nfo", 4L),
        )
        assertEquals(1, pickDefaultManualFile(files)!!.index)
    }

    @Test
    fun `empty file list picks nothing`() {
        assertNull(pickDefaultManualFile(emptyList()))
    }

    @Test
    fun `display name strips directories`() {
        assertEquals("ep12.mkv", ManualTorrentFileEntry(3, "a/b\\ep12.mkv", 9L).displayName)
    }

    // ---- pending holder ----

    @Test
    fun `offer consume round trip`() {
        val holder = PendingManualTorrent()
        val payload = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}")
        val gen = holder.offer(payload)
        assertEquals(payload, holder.peekLive())
        assertEquals(payload, holder.consume(gen))
        // Destructive: second consume finds nothing.
        assertNull(holder.consume(gen))
        assertNull(holder.peekLive())
    }

    @Test
    fun `stale generation never resurrects`() {
        val holder = PendingManualTorrent()
        val first = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}")
        val gen1 = holder.offer(first)
        val second = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"F".repeat(40)}")
        holder.offer(second)
        assertNull(holder.consume(gen1))
    }

    @Test
    fun `expired offer is dropped`() {
        var now = 0L
        val holder = PendingManualTorrent(timeProvider = { now })
        val gen = holder.offer(ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}"))
        now = ManualTorrentEntry.PENDING_TTL_MS + 1
        assertNull(holder.peekLive())
        assertNull(holder.consume(gen))
    }

    @Test
    fun `clear drops pending without consume`() {
        val holder = PendingManualTorrent()
        holder.offer(ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}"))
        holder.clear()
        assertNull(holder.peekLive())
    }

    @Test
    fun `drop fires on replace but not on successful consume`() {
        val dropped = mutableListOf<ManualTorrentPayload>()
        val holder = PendingManualTorrent(onDrop = { dropped.add(it) })
        val first = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}")
        holder.offer(first)
        val second = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"F".repeat(40)}")
        val gen2 = holder.offer(second)
        assertEquals(listOf(first), dropped)
        assertEquals(second, holder.consume(gen2))
        assertEquals(listOf(first), dropped)
    }

    @Test
    fun `drop fires on expiry`() {
        val dropped = mutableListOf<ManualTorrentPayload>()
        var now = 0L
        val holder = PendingManualTorrent(timeProvider = { now }, onDrop = { dropped.add(it) })
        val payload = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}")
        holder.offer(payload)
        now = ManualTorrentEntry.PENDING_TTL_MS + 1
        assertNull(holder.peekLive())
        assertEquals(listOf(payload), dropped)
    }

    @Test
    fun `constants sane`() {
        assertEquals(10 * 1024 * 1024, ManualTorrentEntry.MAX_TORRENT_FILE_BYTES)
        assertEquals(15 * 60 * 1000L, ManualTorrentEntry.PENDING_TTL_MS)
    }
}
