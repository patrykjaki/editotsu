package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next episode with "make default" on and an Editotsu Torrents release remembered: the stored
 * family (group, quality, batch) picks the release, not the most seeded row. Rows are built the
 * way AniyomiAdapter builds them on the device (name = videoTitle, embed = "", video with data).
 */
class NextEpisodeFamilyTest {
    private val pkg = "eu.kanade.tachiyomi.animeextension.all.editotsutorrents"

    private fun row(
        torrent: String,
        seeders: Int,
        hash: String,
        file: String? = null,
        numFiles: Int = 1,
        fileIdx: Int = 0,
    ): VideoServer {
        val title = listOfNotNull(torrent, file?.substringAfterLast('/')).joinToString("\n") +
            "\n👤 $seeders 💾 1.40 GB ⚙️ Nyaa"
        val fileJson = file?.let { ""","fileName":"$it"""" } ?: ""
        val data = """{"editotsu":1,"infoHash":"$hash","fileIdx":$fileIdx,"torrentName":"$torrent"$fileJson,""" +
            """"sizeBytes":1503238553,"seeders":$seeders,"tracker":"nyaa","numFiles":$numFiles}"""
        val video = eu.kanade.tachiyomi.animesource.model.Video(
            videoUrl = "magnet:?xt=urn:btih:$hash&index=$fileIdx",
            videoTitle = title,
            resolution = 1080,
            internalData = data,
            initialized = true,
        )
        return VideoServer(video.quality, video.url, hashMapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to pkg), video)
    }

    private val h1 = "a".repeat(40)
    private val h2 = "b".repeat(40)
    private val batchHash = "c".repeat(40)
    private val otherBatch = "d".repeat(40)

    private fun remember(s: VideoServer): Pair<String, FamilyPayload> {
        val c = StableCandidateBuilder.familyCandidate(s)
        assertNotNull("no family for ${s.name}", c)
        c!!
        val payload = FamilyPayload(c.providerPkg, c.groupKey, c.soft)
        // survives the store's codec (entry and value caps)
        val decoded = SelectedFamilyCodec.decode(SelectedFamilyCodec.encode(payload)!!)
        assertEquals(payload, decoded)
        return c.exactKey to payload
    }

    private fun resolve(stored: Pair<String, FamilyPayload>, servers: List<VideoServer>) =
        RecoveryCoordinator.resolve(stored.first, stored.second, null, servers)

    @Test
    fun same_group_single_beats_more_seeded_rows() {
        val ep1 = row("[ASW] Oshi no Ko - 01 [1080p HEVC][11111111].mkv", 40, h1)
        val stored = remember(ep1)
        val ep2 = listOf(
            row("[SubsPlease] Oshi no Ko - 02 (1080p) [22222222].mkv", 900, "e".repeat(40)),
            row("[ASW] Oshi no Ko (01-12) [1080p HEVC][Batch]", 300, batchHash, "Oshi no Ko/[ASW] Oshi no Ko - 02 [1080p HEVC][33333333].mkv", 12, 1),
            row("[ASW] Oshi no Ko - 02 [1080p HEVC][44444444].mkv", 35, h2),
        )
        val r = resolve(stored, ep2)
        assertTrue(r.toString(), r is RecoveryResult.Resolved)
        assertEquals(ep2[2].name, (r as RecoveryResult.Resolved).server.name)
    }

    @Test
    fun batch_continues_in_the_same_batch() {
        val ep1 = row("[ASW] Oshi no Ko (01-12) [1080p HEVC][Batch]", 300, batchHash, "Oshi no Ko/[ASW] Oshi no Ko - 01 [1080p HEVC][11111111].mkv", 12, 0)
        val stored = remember(ep1)
        assertEquals("c".repeat(32), stored.second.soft["pack"])
        val ep2 = listOf(
            row("[ASW] Oshi no Ko - 02 [1080p HEVC][44444444].mkv", 35, h2),
            row("[ASW] Oshi no Ko S1 [1080p HEVC][Batch]", 500, otherBatch, "S1/[ASW] Oshi no Ko - 02 [1080p HEVC][55555555].mkv", 12, 1),
            row("[ASW] Oshi no Ko (01-12) [1080p HEVC][Batch]", 300, batchHash, "Oshi no Ko/[ASW] Oshi no Ko - 02 [1080p HEVC][33333333].mkv", 12, 1),
        )
        val r = resolve(stored, ep2)
        assertTrue(r.toString(), r is RecoveryResult.Resolved)
        val chosen = (r as RecoveryResult.Resolved).server
        assertEquals(ep2[2].name, chosen.name)
        assertTrue(chosen.video!!.videoUrl.endsWith("&index=1"))
    }

    @Test
    fun other_group_only_is_no_match() {
        val stored = remember(row("[ASW] Oshi no Ko - 01 [1080p HEVC][11111111].mkv", 40, h1))
        val ep2 = listOf(row("[SubsPlease] Oshi no Ko - 02 (1080p) [22222222].mkv", 900, h2))
        assertEquals(RecoveryResult.NoMatch, resolve(stored, ep2))
    }
}
