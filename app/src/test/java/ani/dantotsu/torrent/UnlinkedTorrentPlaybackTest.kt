package ani.dantotsu.torrent

import ani.dantotsu.connections.shouldSyncProgress
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.media.Media
import ani.dantotsu.media.UNLINKED_TORRENT_MEDIA_ID
import ani.dantotsu.media.continueListWith
import ani.dantotsu.media.isUnlinkedId
import ani.dantotsu.media.isUnlinkedPlayback
import ani.dantotsu.media.unlinkedMediaIdForTorrent
import ani.dantotsu.media.anime.resumePositionKey
import ani.dantotsu.media.anime.subtitlePrefKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7A G2: unlinked synthetic playback isolation matrix. Pure JVM — the
 * guards under test sit before any Android touch, so these prove the real
 * write boundaries refuse synthetic entries rather than asserting
 * predicates in a vacuum. Device/emulator runs remain OPEN for the sheet,
 * player, and download UI itself.
 */
class UnlinkedTorrentPlaybackTest {

    private fun realMedia(id: Int = 1): Media = Media(
        id = id,
        name = "Real",
        nameRomaji = "Real",
        userPreferredName = "Real",
        isAdult = false
    )

    // ---- reserved identity ----

    @Test
    fun `reserved id is negative and recognized`() {
        assertTrue(UNLINKED_TORRENT_MEDIA_ID < 0)
        assertTrue(buildUnlinkedPlayback("T", "f.mkv").media.isUnlinkedPlayback())
    }

    @Test
    fun `real and empty entries are not unlinked`() {
        assertTrue(!realMedia(1).isUnlinkedPlayback())
        assertTrue(!realMedia(0).isUnlinkedPlayback())
        assertTrue(!realMedia(999999).isUnlinkedPlayback())
        val nullMedia: Media? = null
        assertTrue(!nullMedia.isUnlinkedPlayback())
    }

    // ---- factory shape ----

    @Test
    fun `factory carries torrent name and file label without fabrication`() {
        val built = buildUnlinkedPlayback("Big Buck Bunny 4K", "bbb.4k.mkv")
        assertEquals("Big Buck Bunny 4K", built.media.userPreferredName)
        assertEquals("Big Buck Bunny 4K", built.media.mainName())
        assertEquals(UNLINKED_EPISODE_KEY, built.episodeKey)
        assertEquals(UNLINKED_EPISODE_KEY, built.episode.number)
        assertEquals("bbb.4k.mkv", built.episode.title)
        assertEquals(setOf(UNLINKED_EPISODE_KEY), built.media.anime?.episodes?.keys)
        assertEquals(UNLINKED_EPISODE_KEY, built.media.anime?.selectedEpisode)
    }

    @Test
    fun `factory fabricates no seasons artwork or ids`() {
        val built = buildUnlinkedPlayback("T", "f.mkv")
        assertNull(built.media.anime?.totalEpisodes)
        assertNull(built.media.cover)
        assertNull(built.media.banner)
        assertNull(built.media.idMAL)
        assertNull(built.media.selected)
        assertNull(built.media.userStatus)
    }

    @Test
    fun `factory falls back on blank names`() {
        assertEquals("f.mkv", buildUnlinkedPlayback("  ", "f.mkv").media.userPreferredName)
        assertEquals(
            UNLINKED_TITLE_FALLBACK,
            buildUnlinkedPlayback("  ", "  ").media.userPreferredName
        )
    }

    // ---- player-construction seam ----

    @Test
    fun `synthetic episode attaches the resolved stream`() {
        val built = buildUnlinkedPlayback("T", "f.mkv")
        val video = attachManualVideo(built.episode, "Manual torrent", "magnet:x", "http://127.0.0.1/1")
        assertEquals("http://127.0.0.1/1", video.file.url)
        assertEquals("Manual torrent", built.episode.selectedExtractor)
        assertEquals(0, built.episode.selectedVideo)
    }

    // ---- progress isolation (simulated logged-in user) ----

    @Test
    fun `sync decision refuses synthetic even logged in and complete`() {
        val synthetic = buildUnlinkedPlayback("T", "f.mkv").media
        // Logged-in, episode complete, saves allowed: still no sync.
        assertTrue(!shouldSyncProgress(synthetic, false, true, true, false, true, true))
        assertTrue(!shouldSyncProgress(synthetic, false, true, false, true, true, true))
        // Incognito/logged-out variants likewise refuse.
        assertTrue(!shouldSyncProgress(synthetic, true, true, true, false, true, true))
        assertTrue(!shouldSyncProgress(synthetic, false, false, true, false, true, true))
    }

    @Test
    fun `sync decision keeps real behavior`() {
        val real = realMedia()
        // Logged-in + complete + saves on: sync (unchanged production rule).
        assertTrue(shouldSyncProgress(real, false, true, true, false, true, true))
        assertTrue(shouldSyncProgress(real, false, true, false, true, true, true))
        assertTrue(!shouldSyncProgress(real, true, true, true, false, true, true))
        assertTrue(!shouldSyncProgress(real, false, false, true, false, true, true))
        assertTrue(!shouldSyncProgress(real, false, true, false, false, true, true))
        assertTrue(!shouldSyncProgress(real, false, true, true, false, false, true))
        val adult = realMedia().apply { isAdult = true }
        assertTrue(!shouldSyncProgress(adult, false, true, true, false, true, false))
        assertTrue(shouldSyncProgress(adult, false, true, true, false, true, true))
    }

    @Test
    fun `updateProgress returns before any Android touch for synthetic`() {
        // The unlinked guard is the first line of the real choke-point
        // function — before prefs, network, and toasts. Reaching this
        // return on plain JVM (no Android runtime) proves the ordering:
        // any regression that moves real work above the guard crashes here
        // with Stub!/NPE instead of passing.
        updateProgress(buildUnlinkedPlayback("T", "f.mkv").media, "1")
        updateProgress(buildUnlinkedPlayback("T", "f.mkv").media, "not-a-number")
    }

    // ---- history pollution ----

    @Test
    fun `synthetic id never enters continue-watching`() {
        assertEquals(listOf(1, 2), continueListWith(listOf(1, 2), UNLINKED_TORRENT_MEDIA_ID))
        assertEquals(emptyList<Int>(), continueListWith(emptyList(), UNLINKED_TORRENT_MEDIA_ID))
    }

    @Test
    fun `real ids keep most-recent semantics without dupes`() {
        assertEquals(listOf(2, 3, 1), continueListWith(listOf(1, 2, 3), 1))
        assertEquals(listOf(1, 4), continueListWith(listOf(1), 4))
    }

    // ---- input titles ----

    @Test
    fun `describeInputTitle prefers readable names`() {
        val hash = "A".repeat(40)
        assertEquals(
            "Magnet ${hash.take(8)}",
            describeInputTitle(ManualTorrentPayload.Magnet("magnet:?xt=urn:btih:$hash"))
        )
        assertEquals(
            "example.com / x.torrent",
            describeInputTitle(ManualTorrentPayload.HttpUrl("https://example.com/x.torrent"))
        )
        assertEquals(
            "real-name.torrent",
            describeInputTitle(
                ManualTorrentPayload.StagedFile("/cache/manual_1.torrent"),
                "real-name.torrent"
            )
        )
        assertEquals(UNLINKED_TITLE_FALLBACK, describeInputTitle(ManualTorrentPayload.Magnet("garbage")))
    }

    // ---- download task contract ----

    @Test
    fun `unlinked download tasks key on strings not catalog ids`() {
        assertEquals(
            "Big Buck Bunny 4K/bbb.4k.mkv",
            AnimeDownloaderService.AnimeDownloadTask.getTaskName("Big Buck Bunny 4K", "bbb.4k.mkv")
        )
    }

    // ---- HOLD1/HOLD2: per-torrent identity over the full negative range ----

    @Test
    fun `unlinked predicate is the full negative range`() {
        assertTrue(isUnlinkedId(-1))
        assertTrue(isUnlinkedId(UNLINKED_TORRENT_MEDIA_ID))
        assertTrue(isUnlinkedId(Int.MIN_VALUE + 1))
        assertTrue(!isUnlinkedId(0))
        assertTrue(!isUnlinkedId(1))
        assertTrue(!isUnlinkedId(Int.MAX_VALUE))
    }

    @Test
    fun `derived ids are distinct stable negative and nonzero`() {
        val idA = unlinkedMediaIdForTorrent("A".repeat(40))
        val idB = unlinkedMediaIdForTorrent("B".repeat(40))
        assertTrue(idA != idB)
        assertEquals(idA, unlinkedMediaIdForTorrent("A".repeat(40)))
        // Case/whitespace normalized to the same torrent.
        assertEquals(idA, unlinkedMediaIdForTorrent("  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  "))
        listOf(idA, idB).forEach { id ->
            assertTrue(id < 0)
            assertTrue(id != 0)
            assertTrue(isUnlinkedId(id))
        }
        assertTrue(buildUnlinkedPlayback("T", "f.mkv", "A".repeat(40)).media.isUnlinkedPlayback())
        // Unknown hashes fall back to the known constant.
        assertEquals(UNLINKED_TORRENT_MEDIA_ID, unlinkedMediaIdForTorrent(null))
        assertEquals(UNLINKED_TORRENT_MEDIA_ID, unlinkedMediaIdForTorrent("   "))
        assertTrue(buildUnlinkedPlayback("T", "f.mkv").media.isUnlinkedPlayback())
    }

    @Test
    fun `factory carries the derived identity`() {
        val hash = "C".repeat(40)
        val built = buildUnlinkedPlayback("Some Torrent", "ep.mkv", hash)
        assertEquals(unlinkedMediaIdForTorrent(hash), built.media.id)
        assertEquals("Some Torrent", built.media.userPreferredName)
        assertEquals("ep.mkv", built.episode.title)
    }

    @Test
    fun `a-b-a resume state stays isolated`() {
        val idA = unlinkedMediaIdForTorrent("A".repeat(40))
        val idB = unlinkedMediaIdForTorrent("B".repeat(40))
        // Same shape the player reads/writes: resume position, max
        // duration, legacy subtitle lang, per-episode subtitle record.
        val prefs = mutableMapOf<String, Long>()
        prefs[resumePositionKey(idA, UNLINKED_EPISODE_KEY)] = 100L
        prefs[resumePositionKey(idB, UNLINKED_EPISODE_KEY)] = 250L
        // B opens: reads its own position, never A's.
        assertEquals(250L, prefs[resumePositionKey(idB, UNLINKED_EPISODE_KEY)])
        // Back to A: A's position intact.
        assertEquals(100L, prefs[resumePositionKey(idA, UNLINKED_EPISODE_KEY)])
        // Same torrent reopens stably under the same key.
        assertEquals(
            resumePositionKey(idA, UNLINKED_EPISODE_KEY),
            resumePositionKey(unlinkedMediaIdForTorrent("A".repeat(40)), UNLINKED_EPISODE_KEY)
        )
        // Subtitle namespaces split with the identity.
        assertTrue(subtitlePrefKey(idA, "1") != subtitlePrefKey(idB, "1"))
    }

    @Test
    fun `real media resume keys are byte-identical to before`() {
        // The helper must not reshape existing keys for real media.
        assertEquals("123_12", resumePositionKey(123, "12"))
        assertEquals("977001_1", resumePositionKey(977001, "1"))
    }

    @Test
    fun `derived identities never pollute continue-watching`() {
        val idA = unlinkedMediaIdForTorrent("A".repeat(40))
        assertEquals(listOf(5), continueListWith(listOf(5), idA))
    }

    // ---- HOLD2: per-file keys within one torrent ----

    @Test
    fun `episode keys derive from the selected file index`() {
        val hash = "D".repeat(40)
        val first = buildUnlinkedPlayback("Pack", "s01e01.mkv", hash, 0)
        val second = buildUnlinkedPlayback("Pack", "s01e02.mkv", hash, 7)
        // 1-based: the first file keeps the default key "1".
        assertEquals("1", first.episodeKey)
        assertEquals("1", first.episode.number)
        assertEquals("8", second.episodeKey)
        assertEquals("8", second.episode.number)
        // Same torrent → same media id; different files → different keys.
        assertEquals(first.media.id, second.media.id)
        assertTrue(first.episodeKey != second.episodeKey)
        assertEquals(setOf("1"), first.media.anime?.episodes?.keys)
        assertEquals("1", first.media.anime?.selectedEpisode)
        assertEquals(setOf("8"), second.media.anime?.episodes?.keys)
        // Negative/contiguous indices coerce sanely.
        assertEquals("1", buildUnlinkedPlayback("P", "f.mkv", hash, -4).episodeKey)
    }

    @Test
    fun `same-torrent different-file state stays isolated`() {
        val hash = "E".repeat(40)
        val id = unlinkedMediaIdForTorrent(hash)
        val file0 = buildUnlinkedPlayback("Pack", "a.mkv", hash, 0)
        val file3 = buildUnlinkedPlayback("Pack", "b.mkv", hash, 3)
        val prefs = mutableMapOf<String, Long>()
        prefs[resumePositionKey(id, file0.episodeKey)] = 100L
        prefs[resumePositionKey(id, file3.episodeKey)] = 400L
        // File 3 opens: reads its own position, never file 0's.
        assertEquals(400L, prefs[resumePositionKey(id, file3.episodeKey)])
        // Back to file 0: intact.
        assertEquals(100L, prefs[resumePositionKey(id, file0.episodeKey)])
        // Subtitle namespaces split with the file key too.
        assertTrue(
            subtitlePrefKey(id, file0.episodeKey) != subtitlePrefKey(id, file3.episodeKey)
        )
    }

    @Test
    fun `unlinked download task names stay unique per file`() {
        // Same display name in different folders must not collapse tasks.
        val first = "1 - episode01.mkv"
        val second = "4 - episode01.mkv"
        assertTrue(first != second)
        assertEquals(
            "Pack/$first",
            AnimeDownloaderService.AnimeDownloadTask.getTaskName("Pack", first)
        )
    }
}
