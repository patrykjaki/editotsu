package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's best match for a remembered group with several qualities is the row
 * auto-play would start (FamilyAutoSelector), not "no best".
 */
class FamilyBestMatchTest {
    private val pkg = "eu.kanade.tachiyomi.animeextension.all.editotsutorrents"

    private class Ext(override val server: VideoServer, videoList: List<Video>) : VideoExtractor() {
        init { videos = videoList }
        override suspend fun extract(): VideoContainer = throw UnsupportedOperationException()
    }

    private fun server(torrent: String, resolution: Int, hash: String): VideoServer {
        val data = """{"editotsu":1,"infoHash":"$hash","fileIdx":0,"torrentName":"$torrent",""" +
            """"sizeBytes":503238553,"seeders":200,"tracker":"nyaa","numFiles":1}"""
        val video = eu.kanade.tachiyomi.animesource.model.Video(
            videoUrl = "magnet:?xt=urn:btih:$hash&index=0",
            videoTitle = "$torrent\n👤 200 💾 480 MB ⚙️ Nyaa",
            resolution = resolution,
            internalData = data,
            initialized = true,
        )
        return VideoServer(video.quality, video.url, hashMapOf(StableCandidateBuilder.PROVIDER_EXTRA_KEY to pkg), video)
    }

    private fun ext(s: VideoServer): VideoExtractor =
        Ext(s, listOf(Video(null, VideoType.CONTAINER, FileUrl(s.video!!.videoUrl))))

    private fun remember(s: VideoServer): FamilyPayload {
        val c = StableCandidateBuilder.familyCandidate(s)
        assertNotNull("no family for ${s.name}", c)
        return FamilyPayload(c!!.providerPkg, c.groupKey, c.soft)
    }

    private val ep1At720 = server("[SubsPlease] Oshi no Ko - 01 (720p) [AAAAAAAA].mkv", 720, "1".repeat(40))
    private val ep2 = listOf(
        server("[SubsPlease] Oshi no Ko - 02 (1080p) [BBBBBBBB].mkv", 1080, "2".repeat(40)),
        server("[SubsPlease] Oshi no Ko - 02 (720p) [CCCCCCCC].mkv", 720, "3".repeat(40)),
        server("[SubsPlease] Oshi no Ko - 02 (480p) [DDDDDDDD].mkv", 480, "4".repeat(40)),
        server("[Erai-raws] Oshi no Ko - 02 [1080p][EEEEEEEE].mkv", 1080, "5".repeat(40)),
    )

    @Test
    fun remembered_720p_is_the_best_match_among_three_qualities() {
        val stored = remember(ep1At720)
        val links = ep2.map(::ext)
        val rows = PickerRowsBuilder().build(links, null, stored, null)

        assertEquals(3, rows.count { it.rememberedFamily })
        assertEquals(listOf(RowRef(1, 0)), rows.filter { it.familyChoice }.map { it.ref })

        val list = PickerListBuilder.build(rows, hasRememberedFamily = true)
        assertEquals(BestPick(RowRef(1, 0), BestReason.SAME_GROUP), list.best)
        assertEquals(0, list.bestIndex)
        // the SubsPlease card shows and plays 720p, and fills the 720p button
        val card = list.entries[0] as PickerEntry.Release
        assertEquals(RowRef(1, 0), card.row.ref)
        assertEquals(RowRef(1, 0), list.defaultQuality(card))
        assertTrue(list.isRememberedQuality(RowRef(1, 0)))
    }

    @Test
    fun picker_best_matches_what_auto_play_resolves() {
        val stored = remember(ep1At720)
        val auto = FamilyAutoSelector.select(stored, ep2.mapNotNull { StableCandidateBuilder.familyCandidate(it) })
        val expected = StableCandidateBuilder.familyCandidate(ep2[1])!!.exactKey
        assertEquals(FamilyAutoResult.Chosen(expected), auto)

        val rows = PickerRowsBuilder().build(ep2.map(::ext), null, stored, null)
        assertEquals(RowRef(1, 0), PickerListBuilder.build(rows).best?.ref)
    }

    @Test
    fun two_equal_720p_reposts_leave_no_best_match() {
        val stored = remember(ep1At720)
        val reposts = listOf(
            server("[SubsPlease] Oshi no Ko - 02 (720p) [CCCCCCCC].mkv", 720, "3".repeat(40)),
            server("[SubsPlease] Oshi no Ko - 02 (720p) [CCCCCCCC].mkv", 720, "6".repeat(40)),
            server("[SubsPlease] Oshi no Ko - 02 (1080p) [BBBBBBBB].mkv", 1080, "2".repeat(40)),
        )
        val rows = PickerRowsBuilder().build(reposts.map(::ext), null, stored, null)
        assertTrue(rows.none { it.familyChoice })
        assertNull(PickerListBuilder.build(rows).best)
    }

    @Test
    fun bestMatch_uses_the_family_choice_flag() {
        fun row(i: Int, res: Int, choice: Boolean) = PickerRow(
            ref = RowRef(i, 0), provider = pkg, presentation = SourcePresentationKind.RELEASE,
            header = "SubsPlease", resolution = res, transport = "Torrent", audioKind = null,
            rememberedFamily = true, familyChoice = choice,
        )
        assertEquals(
            BestPick(RowRef(1, 0), BestReason.SAME_GROUP),
            BestMatch.pick(listOf(row(0, 1080, false), row(1, 720, true), row(2, 480, false))),
        )
        assertNull(BestMatch.pick(listOf(row(0, 1080, false), row(1, 720, false))))
    }
}
