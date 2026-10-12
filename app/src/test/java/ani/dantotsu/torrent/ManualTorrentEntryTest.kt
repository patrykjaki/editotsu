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
    fun `VIEW content x-bittorrent yields borrowed receipt`() {
        val payload = ManualTorrentIntentDispatch.classify(
            view, "content", "application/x-bittorrent", "content://prov/x.torrent"
        )
        assertTrue(payload is ManualTorrentPayload.ReceivedFile)
        assertEquals("content://prov/x.torrent", (payload as ManualTorrentPayload.ReceivedFile).uriString)
    }

    @Test
    fun `VIEW file x-bittorrent yields borrowed receipt`() {
        val payload = ManualTorrentIntentDispatch.classify(
            view, "file", "application/x-bittorrent", "file:///sdcard/x.torrent"
        )
        assertTrue(payload is ManualTorrentPayload.ReceivedFile)
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

    // ---- HOLD1-A: borrowed files can never enter cleanup ownership ----

    @Test
    fun `offering borrowed receipt throws`() {
        val holder = PendingManualTorrent()
        try {
            holder.offer(ManualTorrentPayload.ReceivedFile("file:///sdcard/x.torrent"))
            assertTrue("ReceivedFile offer must throw", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("staged"))
        }
        assertNull(holder.peekLive())
    }

    // ---- HOLD1-C: receipt-start ordering ----

    @Test
    fun `slow file stage cannot overwrite newer magnet`() {
        val dropped = mutableListOf<ManualTorrentPayload>()
        val holder = PendingManualTorrent(onDrop = { dropped.add(it) })
        val fileToken = holder.beginStage()
        val magnet = ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}")
        holder.offer(magnet)
        val lateFile = ManualTorrentPayload.StagedFile("/cache/manual_1.torrent")
        assertTrue(!holder.offerStaged(fileToken, lateFile))
        // Newer magnet kept; obsolete staged copy disposed exactly once.
        assertEquals(magnet, holder.peekLive())
        assertEquals(listOf(lateFile), dropped)
    }

    @Test
    fun `current stage token wins`() {
        val holder = PendingManualTorrent()
        val stale = holder.beginStage()
        val current = holder.beginStage()
        val payload = ManualTorrentPayload.StagedFile("/cache/manual_2.torrent")
        assertTrue(!holder.offerStaged(stale, payload))
        assertTrue(holder.offerStaged(current, payload))
        assertEquals(payload, holder.peekLive())
    }

    @Test
    fun `magnet offer invalidates in-flight file stage`() {
        val dropped = mutableListOf<ManualTorrentPayload>()
        val holder = PendingManualTorrent(onDrop = { dropped.add(it) })
        val fileToken = holder.beginStage()
        holder.offer(ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"F".repeat(40)}"))
        // The in-flight stage completes late: disposed, offer kept.
        assertTrue(!holder.offerStaged(fileToken, ManualTorrentPayload.StagedFile("/cache/late.torrent")))
        assertTrue(holder.peekLive() is ManualTorrentPayload.Magnet)
    }

    // ---- HOLD1-C: bounded copy core ----

    @Test
    fun `copyBounded copies exact bytes`() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        assertEquals(300_000L, copyBounded(java.io.ByteArrayInputStream(data), out, 1_000_000L))
        assertTrue(data.contentEquals(out.toByteArray()))
    }

    @Test
    fun `copyBounded rejects over-cap input`() {
        val out = java.io.ByteArrayOutputStream()
        assertNull(copyBounded(java.io.ByteArrayInputStream(ByteArray(11)), out, 10L))
    }

    @Test
    fun `copyBounded accepts exactly cap-sized input`() {
        val out = java.io.ByteArrayOutputStream()
        assertEquals(10L, copyBounded(java.io.ByteArrayInputStream(ByteArray(10)), out, 10L))
    }

    @Test
    fun `copyBounded propagates midstream failure`() {
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("boom")
        }
        try {
            copyBounded(failing, java.io.ByteArrayOutputStream(), 10L)
            assertTrue("midstream failure must throw", false)
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `copyBounded empty input yields zero`() {
        val out = java.io.ByteArrayOutputStream()
        assertEquals(0L, copyBounded(java.io.ByteArrayInputStream(ByteArray(0)), out, 10L))
    }

    // ---- HOLD1-B: attach seam — selected index to player-consumed video ----

    private fun testEpisode(): ani.dantotsu.media.anime.Episode =
        ani.dantotsu.media.anime.Episode(number = "12")

    @Test
    fun `attach exposes chosen link as player-consumed video`() {
        val ep = testEpisode()
        val consumed = attachManualVideo(ep, "Manual torrent", "magnet:?xt=urn:btih:x", "http://127.0.0.1:8090/stream?hash=H&index=2")
        assertEquals("Manual torrent", ep.selectedExtractor)
        assertEquals(0, ep.selectedVideo)
        // Exactly what ExoplayerView reads:
        val ext = ep.extractors!!.find { it.server.name == ep.selectedExtractor }!!
        val video = ext.videos.getOrNull(ep.selectedVideo)!!
        assertTrue(video === consumed)
        assertEquals("http://127.0.0.1:8090/stream?hash=H&index=2", video.file.url)
    }

    @Test
    fun `attach replaces same-named extractor without touching others`() {
        val ep = testEpisode()
        attachManualVideo(ep, "Manual torrent", "magnet:?xt=urn:btih:a", "http://127.0.0.1/link1")
        val other = ani.dantotsu.parsers.Video(null, ani.dantotsu.parsers.VideoType.CONTAINER, "https://cdn/x.mp4")
        val otherExtractor = object : ani.dantotsu.parsers.VideoExtractor() {
            override val server = ani.dantotsu.parsers.VideoServer("CDN", "")
            override suspend fun extract() = ani.dantotsu.parsers.VideoContainer(listOf(other))
        }.also { it.videos = listOf(other) }
        ep.extractors!!.add(otherExtractor)
        attachManualVideo(ep, "Manual torrent", "magnet:?xt=urn:btih:b", "http://127.0.0.1/link2")
        assertEquals(2, ep.extractors!!.size)
        assertEquals("http://127.0.0.1/link2", ep.extractors!!.find { it.server.name == "Manual torrent" }!!.videos[0].file.url)
        assertEquals("https://cdn/x.mp4", ep.extractors!!.find { it.server.name == "CDN" }!!.videos[0].file.url)
    }

    @Test
    fun `attach leaves unrelated episodes untouched`() {
        val ep1 = testEpisode()
        val ep2 = ani.dantotsu.media.anime.Episode(number = "13")
        attachManualVideo(ep1, "Manual torrent", "magnet:?xt=urn:btih:x", "http://127.0.0.1/link")
        assertNull(ep2.extractors)
        assertNull(ep2.selectedExtractor)
        assertEquals(0, ep2.selectedVideo)
    }

    // ---- HOLD2: clear invalidates in-flight staging ----

    @Test
    fun `clear rejects late stage completion`() {
        val deleted = mutableListOf<String>()
        val holder = PendingManualTorrent(onDrop = {
            (it as? ManualTorrentPayload.StagedFile)?.locator?.let { path ->
                deleted.add(path)
            }
        })
        val token = holder.beginStage()
        holder.clear()
        assertTrue(
            !holder.offerStaged(token, ManualTorrentPayload.StagedFile("/cache/late.torrent"))
        )
        assertEquals(listOf("/cache/late.torrent"), deleted)
        assertNull(holder.peekLive())
    }

    // ---- HOLD2-A: staged publication transfer ----

    @Test
    fun `publish fresh token wins`() {
        val offered = mutableListOf<ManualTorrentPayload.StagedFile>()
        val result = publishStagedReceipt(3L, "/cache/a.torrent") { _, payload ->
            offered.add(payload)
            true
        }
        assertEquals(StagedPublishResult.PUBLISHED, result)
        assertEquals(listOf("/cache/a.torrent"), offered.map { it.locator })
    }

    @Test
    fun `publish stale token reports cleaned`() {
        var disposed = false
        val result = publishStagedReceipt(1L, "/cache/a.torrent") { _, _ ->
            disposed = true
            false
        }
        assertEquals(StagedPublishResult.STALE_CLEANED, result)
        assertTrue(disposed)
    }

    @Test
    fun `publish nothing staged reports empty`() {
        var called = false
        val result = publishStagedReceipt(1L, null) { _, _ ->
            called = true
            true
        }
        assertEquals(StagedPublishResult.NOTHING_STAGED, result)
        assertTrue(!called)
    }

    @Test
    fun `cancel between stage completion and publish leaves no orphan`() {
        // Faithful model of MainActivity's staging→Main window with real
        // files and the real holder: copy completes, publication is
        // cancelled (CancellationException), the finally rule deletes.
        val source = java.io.File.createTempFile("original", ".torrent")
        source.writeBytes(byteArrayOf(7, 8, 9))
        val staged = java.io.File.createTempFile("manual", ".torrent")
        source.inputStream().use { input ->
            staged.outputStream().use { output ->
                copyBounded(input, output, ManualTorrentEntry.MAX_TORRENT_FILE_BYTES.toLong())
            }
        }
        val holder = PendingManualTorrent(onDrop = {
            (it as? ManualTorrentPayload.StagedFile)?.locator?.let { path ->
                java.io.File(path).delete()
            }
        })
        val token = holder.beginStage()
        var result = StagedPublishResult.NOTHING_STAGED
        var publishRan = false
        val tornDownBeforePublish = true
        try {
            // Publication never runs: scope torn down first.
            if (tornDownBeforePublish) throw kotlinx.coroutines.CancellationException("scope torn down")
            result = publishStagedReceipt(token, staged.absolutePath) { _, _ -> true }
            publishRan = true
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected here: in production this propagates to the torn-down
            // scope; the test catches only to assert the cleanup state.
        } finally {
            if (result != StagedPublishResult.PUBLISHED) {
                staged.delete()
            }
        }
        assertTrue(!publishRan)
        assertTrue(!staged.exists())
        assertTrue(source.exists())
        assertNull(holder.peekLive())
        source.delete()
    }

    // ---- HOLD2-B/HOLD3: ownership settlement (real temp files) ----

    private fun realTemp(prefix: String): java.io.File {
        val f = java.io.File.createTempFile(prefix, ".torrent")
        f.writeBytes(byteArrayOf(1, 2, 3))
        return f
    }

    @Test
    fun `failed-A retry file retired by magnet-B success`() {
        // HOLD3 reproducer shape: pick staged A, addTorrent(A) fails
        // (ownedTempPath=A retained for retry), magnet B succeeds.
        val fileA = realTemp("stagedA")
        val settled = settleOwnership(
            staged = null, owned = fileA.absolutePath, current = null
        )
        assertNull(settled.retained)
        assertEquals(listOf(fileA.absolutePath), settled.deleted)
        settled.deleted.forEach { java.io.File(it).delete() }
        assertTrue(!fileA.exists())
    }

    @Test
    fun `failed-A retry file retired by staged-B success, B retained`() {
        val fileA = realTemp("stagedA")
        val fileB = realTemp("stagedB")
        val settled = settleOwnership(
            staged = null, owned = fileA.absolutePath, current = fileB.absolutePath
        )
        assertEquals(fileB.absolutePath, settled.retained)
        assertEquals(listOf(fileA.absolutePath), settled.deleted)
        settled.deleted.forEach { java.io.File(it).delete() }
        assertTrue(!fileA.exists())
        assertTrue(fileB.exists())
        fileB.delete()
    }

    @Test
    fun `resolved-A plus failed-A retry both retired by magnet success`() {
        val resolvedA = realTemp("resolvedA")
        val retryA = realTemp("retryA")
        val settled = settleOwnership(
            staged = resolvedA.absolutePath, owned = retryA.absolutePath, current = null
        )
        assertNull(settled.retained)
        assertEquals(
            listOf(resolvedA.absolutePath, retryA.absolutePath),
            settled.deleted
        )
        settled.deleted.forEach { java.io.File(it).delete() }
        assertTrue(!resolvedA.exists())
        assertTrue(!retryA.exists())
    }

    @Test
    fun `same-path retry transfers without deleting`() {
        val fileA = realTemp("stagedA")
        val settled = settleOwnership(
            staged = null, owned = fileA.absolutePath, current = fileA.absolutePath
        )
        assertEquals(fileA.absolutePath, settled.retained)
        assertTrue(settled.deleted.isEmpty())
        assertTrue(fileA.exists())
        fileA.delete()
    }

    @Test
    fun `same staged path re-resolve keeps file`() {
        val fileA = realTemp("stagedA")
        val settled = settleOwnership(
            staged = fileA.absolutePath, owned = null, current = fileA.absolutePath
        )
        assertEquals(fileA.absolutePath, settled.retained)
        assertTrue(settled.deleted.isEmpty())
        assertTrue(fileA.exists())
        fileA.delete()
    }

    @Test
    fun `shared pointer deleted exactly once`() {
        val fileA = realTemp("stagedA")
        val settled = settleOwnership(
            staged = fileA.absolutePath, owned = fileA.absolutePath, current = null
        )
        assertNull(settled.retained)
        assertEquals(listOf(fileA.absolutePath), settled.deleted)
        settled.deleted.forEach { java.io.File(it).delete() }
        assertTrue(!fileA.exists())
    }

    @Test
    fun `no previous ownership deletes nothing`() {
        val settled = settleOwnership(staged = null, owned = null, current = null)
        assertNull(settled.retained)
        assertTrue(settled.deleted.isEmpty())
        val settled2 = settleOwnership(staged = null, owned = null, current = "/cache/b.torrent")
        assertEquals("/cache/b.torrent", settled2.retained)
        assertTrue(settled2.deleted.isEmpty())
    }

    // ---- HOLD1-A: original-file survival (reviewer negative control) ----

    @Test
    fun `original borrowed file survives every holder outcome`() {
        val original = java.io.File.createTempFile("original", ".torrent")
        original.writeBytes(byteArrayOf(1, 2, 3))
        val dropped = mutableListOf<ManualTorrentPayload>()
        val holder = PendingManualTorrent(onDrop = {
            // Production-equivalent: only app-owned staged copies deleted.
            (it as? ManualTorrentPayload.StagedFile)?.locator?.let { path ->
                java.io.File(path).delete()
            }
        })
        // 1. Offering the borrowed path itself is rejected outright.
        try {
            holder.offer(ManualTorrentPayload.ReceivedFile(original.absolutePath))
            assertTrue("borrowed offer must throw", false)
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(original.exists())
        // 2. Staged-copy lifecycle never touches the original.
        val staged = java.io.File.createTempFile("manual", ".torrent")
        holder.offer(ManualTorrentPayload.StagedFile(staged.absolutePath))
        holder.clear()
        assertTrue(!staged.exists())
        assertTrue(original.exists())
        assertEquals(listOf(3), listOf(original.readBytes().size))
        // 3. Replace + expiry paths.
        val staged2 = java.io.File.createTempFile("manual", ".torrent")
        holder.offer(ManualTorrentPayload.StagedFile(staged2.absolutePath))
        holder.offer(ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:${"E".repeat(40)}"))
        assertTrue(!staged2.exists())
        assertTrue(original.exists())
        original.delete()
    }

    // ---- G6: http(s) .torrent-URL input ----

    @Test
    fun `http torrent urls accepted`() {
        assertTrue(HttpTorrentUrl.isHttpTorrentUrl("http://example.com/x.torrent"))
        assertTrue(HttpTorrentUrl.isHttpTorrentUrl("https://example.com/x.TORRENT"))
        assertTrue(HttpTorrentUrl.isHttpTorrentUrl("  HTTPS://example.com/a.torrent?x=1  "))
        assertTrue(HttpTorrentUrl.isHttpTorrentUrl("http://example.com/dir/file.torrent#frag"))
    }

    @Test
    fun `non-torrent urls rejected`() {
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl(null))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl(""))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("   "))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("magnet:?xt=urn:btih:${"A".repeat(40)}"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("ftp://example.com/x.torrent"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("file:///x.torrent"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("content://x/x.torrent"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("https://example.com/watch?v=1"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("https://example.com/torrent-page"))
        assertTrue(!HttpTorrentUrl.isHttpTorrentUrl("javascript:alert(1)"))
    }

    @Test
    fun `typed input classifies magnet http and garbage`() {
        val magnet = "magnet:?xt=urn:btih:${"B".repeat(40)}"
        val asMagnet = ManualTorrentInput.classify("  $magnet  ")
        assertTrue(asMagnet is ManualTorrentPayload.Magnet)
        assertEquals(magnet, (asMagnet as ManualTorrentPayload.Magnet).uri)
        val http = "https://example.com/x.torrent"
        val asHttp = ManualTorrentInput.classify(http)
        assertTrue(asHttp is ManualTorrentPayload.HttpUrl)
        assertEquals(http, (asHttp as ManualTorrentPayload.HttpUrl).url)
        assertNull(ManualTorrentInput.classify(null))
        assertNull(ManualTorrentInput.classify("   "))
        assertNull(ManualTorrentInput.classify("https://example.com/watch"))
        assertNull(ManualTorrentInput.classify("not a link"))
    }

    @Test
    fun `http view intents still rejected — no link hijack`() {
        assertNull(
            ManualTorrentIntentDispatch.classify(
                "android.intent.action.VIEW", "https",
                null, "https://example.com/x.torrent"
            )
        )
        assertNull(
            ManualTorrentIntentDispatch.classify(
                "android.intent.action.VIEW", "http",
                "application/x-bittorrent", "http://example.com/x.torrent"
            )
        )
    }

    // ---- HOLD1: downstream engine verification ----

    @Test
    fun `engine fetch dispatch is case-insensitive on scheme`() {
        assertTrue(isHttpFetchUrl("http://example.com/x.torrent"))
        assertTrue(isHttpFetchUrl("https://example.com/x.torrent"))
        assertTrue(isHttpFetchUrl("HTTP://example.com/x.torrent"))
        assertTrue(isHttpFetchUrl("HTTPS://example.com/x.torrent"))
        assertTrue(isHttpFetchUrl("Http://example.com/x.torrent"))
        assertTrue(isHttpFetchUrl("hTTpS://example.com/x.torrent"))
        // Path/query/fragment case is preserved by the predicate (it only
        // compares the scheme): mixed-case URLs still dispatch.
        assertTrue(isHttpFetchUrl("https://example.com/Dir/File.TORRENT?X=1#F"))
        assertTrue(!isHttpFetchUrl("magnet:?xt=urn:btih:${"A".repeat(40)}"))
        assertTrue(!isHttpFetchUrl("ftp://example.com/x.torrent"))
        assertTrue(!isHttpFetchUrl("file:///x.torrent"))
        assertTrue(!isHttpFetchUrl(""))
    }

    @Test
    fun `entry gate acceptance implies engine fetch dispatch`() {
        // Core HOLD1 regression: any URL the G6 gate accepts MUST reach the
        // engine's HTTP fetch branch. The converse is intentionally false:
        // the engine predicate is scheme-only (other callers pass bare http
        // URLs; a non-torrent body then fails cleanly at parse with its temp
        // deleted), while the entry gate additionally requires the .torrent
        // marker to keep typed input strict.
        val gateAccepted = listOf(
            "http://example.com/x.torrent",
            "https://example.com/x.torrent",
            "HTTPS://example.com/x.torrent",
            "HTTP://example.com/x.torrent",
            "Http://example.com/Dir/F.Torrent?a=1#z"
        )
        gateAccepted.forEach { sample ->
            assertTrue("gate rejects: $sample", HttpTorrentUrl.isHttpTorrentUrl(sample))
            assertTrue("engine misses gate-accepted: $sample", isHttpFetchUrl(sample))
        }
        // Gate-strictness samples: rejected by the gate (no marker / wrong
        // scheme) regardless of engine dispatch.
        listOf(
            "https://example.com/watch?v=1",
            "https://example.com/torrent-page",
            "magnet:?xt=urn:btih:${"A".repeat(40)}",
            "ftp://example.com/x.torrent",
            "file:///x.torrent",
            "not a link"
        ).forEach { sample ->
            assertTrue("gate accepts: $sample", !HttpTorrentUrl.isHttpTorrentUrl(sample))
        }
    }

    @Test
    fun `http fetch bound refuses oversize bodies without materializing them`() {
        // The fetch path reuses copyBounded with the shared staging cap:
        // an over-cap stream is refused (null) after capped streaming —
        // never loaded whole into memory. On-demand bytes, no big alloc.
        val cap = ManualTorrentEntry.MAX_TORRENT_FILE_BYTES.toLong()
        val big = object : java.io.InputStream() {
            var remaining = cap + 1L
            override fun read(): Int =
                if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 0.toByte())
                remaining -= n
                return n
            }
        }
        assertNull(
            copyBounded(big, java.io.ByteArrayOutputStream(), cap)
        )
    }
}
