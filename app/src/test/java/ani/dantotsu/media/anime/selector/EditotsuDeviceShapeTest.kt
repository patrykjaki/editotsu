package ani.dantotsu.media.anime.selector

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Editotsu Torrents row the way it reaches the picker on the device: AniyomiAdapter's
 * VideoServer(name = videoTitle, embed = video page url, extra = provider, video = the aniyomi
 * Video), internalData as Android's org.json writes it (`\/` escapes, media + tags objects).
 */
class EditotsuDeviceShapeTest {

    private val magnet = "magnet:?xt=urn:btih:e971d09c2578aefd982b7d4059e8bdd1fc3d2cf4&dn=x&index=0"

    private val title = "[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC) [F9A34879].mkv\n" +
        "👤 15 💾 350.12 MB ⚙️ Nyaa"

    private val internal = """{"editotsu":1,"infoHash":"e971d09c2578aefd982b7d4059e8bdd1fc3d2cf4","fileIdx":0,""" +
        """"torrentName":"[DameDesuYo] ReZero kara Hajimeru Isekai Seikatsu - 01 (1280x720 10bit AAC) [F9A34879].mkv",""" +
        """"sizeBytes":367127347,"seeders":15,"leechers":2,"tracker":"nyaa","anidbEid":176270,"anidbAid":11681,""" +
        """"numFiles":1,"trusted":false,"remake":false,"source":"animetosho",""" +
        """"tags":{"from":"name","group":"DameDesuYo"},""" +
        """"media":{"from":"mediainfo","codec":"HEVC","bitDepth":10,"width":1280,"height":720,"durationSec":1500,""" +
        """"audio":[{"lang":"ja","codec":"AAC","channels":2,"default":true}],""" +
        """"subtitles":[{"lang":"en","codec":"ASS","forced":false,"title":"Eng \/ DDY"}]}}"""

    private class Ext(override val server: VideoServer, videoList: List<Video>) : VideoExtractor() {
        init { videos = videoList }
        override suspend fun extract(): VideoContainer = throw UnsupportedOperationException()
    }

    private fun deviceRow(): VideoExtractor {
        val aniVideo = eu.kanade.tachiyomi.animesource.model.Video(
            videoUrl = magnet,
            videoTitle = title,
            resolution = 720,
            internalData = internal,
            initialized = true,
        )
        val server = VideoServer(
            aniVideo.quality,
            aniVideo.url,
            hashMapOf("editotsu-picker-provider" to "eu.kanade.tachiyomi.animeextension.all.editotsutorrents"),
            aniVideo,
        )
        // VideoServerPassthrough: the number in the quality text, the magnet, CONTAINER
        return Ext(server, listOf(Video(1280, VideoType.CONTAINER, FileUrl(magnet))))
    }

    @Test
    fun device_row_is_a_release_with_data() {
        val built = PickerRowsBuilder().buildWithViews(listOf(deviceRow()), null, null, "Editotsu Torrents")
        val view = built.views.values.single()
        val row = built.rows.single()
        assertNotNull(EditotsuInternalData.of(deviceRow().server.video))
        assertEquals(SourcePresentationKind.RELEASE, view.presentation)
        assertEquals("DameDesuYo", view.header)
        assertTrue(view.pills.joinToString { it.toString() }, view.pills.isNotEmpty())
        assertEquals(720, row.resolution)
    }
}
