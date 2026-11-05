package ani.dantotsu.screenshots

import ani.dantotsu.FileUrl
import ani.dantotsu.animethemes.ThemeChoice
import ani.dantotsu.animethemes.ThemeTrack
import ani.dantotsu.animethemes.ThemeWarningType
import ani.dantotsu.media.Media
import ani.dantotsu.media.Selected
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoType

/** Fixed test data shaped like what the app gets from AniList, AnimeThemes and Editotsu Torrents. */
object Fixtures {

    fun media(episodes: Map<String, Episode> = emptyMap()) = Media(
        anime = Anime(totalEpisodes = 24, episodes = episodes.toMutableMap()),
        id = 900001,
        name = "The Lantern Apothecary",
        nameRomaji = "Andon Yakkyoku Nisshi",
        userPreferredName = "Andon Yakkyoku Nisshi",
        isAdult = false,
        userProgress = 5,
    ).apply { selected = Selected() }

    // ---- AnimeThemes ------------------------------------------------------------------

    fun track(
        id: Int, tag: String, song: String, artist: String, episodes: String?,
        ending: Boolean = false, version: Int = 1, versions: Int = 1,
        warning: ThemeWarningType = ThemeWarningType.NONE,
    ) = ThemeTrack(
        themeId = id, tag = tag, songTitle = song, artist = artist, episodes = episodes,
        url = "https://v.animethemes.moe/$id.webm", fallbackUrls = emptyList(), warning = warning,
        entryId = id * 10 + version, version = version, versionCount = versions,
        audioUrl = "https://a.animethemes.moe/$id.ogg", isEnding = ending,
        mediaId = 900001, showTitle = "Andon Yakkyoku Nisshi",
    )

    fun choice(vararg versions: ThemeTrack) = ThemeChoice(versions.toList(), versions.first(), versions.first().isEnding)

    /** Two seasons' worth: more than four openings (collapsed list), a spoiler, two versions, NSFW. */
    val themes: List<ThemeChoice> = listOf(
        choice(track(1, "OP 1", "Paper Lanterns at Dawn", "Kumo Station", "1-12")),
        choice(
            track(2, "OP 2", "Umbrella Weather", "Teal Window", "13-24", version = 1, versions = 2),
            track(2, "OP 2", "Umbrella Weather", "Teal Window", "13-24", version = 2, versions = 2),
        ),
        choice(track(3, "OP 3", "Hundred Remedies", "Mirei Hoshino", "25-36", warning = ThemeWarningType.SPOILER)),
        choice(track(4, "OP 4", "Midnight-Procession-of-Sixteen-Lanterns", "The Glass Herbarium", "37-48")),
        choice(track(5, "OP 5", "Bitter Tonic", "Oxbow Lane", "49-", warning = ThemeWarningType.NSFW)),
        choice(track(11, "ED 1", "Passwords", "Nanase Rin", "1-12", ending = true)),
        choice(track(12, "ED 2", "Footprints in Ash", "Low Tide Choir", "13-24", ending = true)),
    )

    // ---- Source picker -----------------------------------------------------------------

    private class Ext(override val server: VideoServer, videoList: List<Video>) : VideoExtractor() {
        init { videos = videoList }
        override suspend fun extract(): VideoContainer = throw UnsupportedOperationException()
    }

    private const val TORRENTS = "eu.kanade.tachiyomi.animeextension.all.editotsutorrents"

    /** One Editotsu Torrents release as AniyomiAdapter hands it to the picker. */
    fun release(
        group: String, height: Int, codec: String, bitDepth: Int, seeders: Int, sizeMb: Int, n: Int,
        audio: String = """{"lang":"ja","codec":"AAC","channels":2,"default":true}""",
    ): VideoExtractor {
        val hash = "%040x".format(n)
        val width = height * 16 / 9
        val name = "[$group] Andon Yakkyoku Nisshi - 06 (${width}x$height ${if (bitDepth == 10) "10bit " else ""}$codec) [${hash.takeLast(8).uppercase()}].mkv"
        val title = "$name\n👤 $seeders 💾 $sizeMb MB ⚙️ Nyaa"
        val magnet = "magnet:?xt=urn:btih:$hash&dn=x&index=0"
        val internal = """{"editotsu":1,"infoHash":"$hash","fileIdx":0,"torrentName":"$name",""" +
            """"sizeBytes":${sizeMb * 1_048_576L},"seeders":$seeders,"leechers":2,"tracker":"nyaa",""" +
            """"numFiles":1,"trusted":false,"remake":false,"source":"nyaa","tags":{"from":"name","group":"$group"},""" +
            """"media":{"from":"mediainfo","codec":"$codec","bitDepth":$bitDepth,"width":$width,"height":$height,""" +
            """"durationSec":1420,"audio":[$audio],"subtitles":[{"lang":"en","codec":"ASS","forced":false}]}}"""
        val aniVideo = eu.kanade.tachiyomi.animesource.model.Video(
            videoUrl = magnet, videoTitle = title, resolution = height, internalData = internal, initialized = true,
        )
        val server = VideoServer(aniVideo.quality, aniVideo.url, hashMapOf("editotsu-picker-provider" to TORRENTS), aniVideo)
        return Ext(server, listOf(Video(height, VideoType.CONTAINER, FileUrl(magnet))))
    }

    private const val DUAL = """{"lang":"ja","codec":"AAC","channels":2,"default":true},{"lang":"en","codec":"AAC","channels":2,"default":false}"""

    /** 9 groups (6 shown + "+3 more" in the filter sheet), 3 resolutions, 2 codecs, sub and dual audio. */
    val releases: List<VideoExtractor> get() = listOf(
        release("SubsPlease", 1080, "AVC", 8, 412, 1390, 1),
        release("SubsPlease", 720, "AVC", 8, 188, 702, 2),
        release("SubsPlease", 480, "AVC", 8, 41, 352, 3),
        release("Erai-raws", 1080, "HEVC", 10, 160, 1210, 4),
        release("ASW", 1080, "HEVC", 10, 96, 380, 5),
        release("Judas", 1080, "HEVC", 10, 52, 290, 6),
        release("DameDesuYo", 720, "HEVC", 10, 15, 350, 7),
        release("EMBER", 1080, "HEVC", 10, 33, 420, 8, audio = DUAL),
        release("Yameii", 1080, "AVC", 8, 21, 1450, 9, audio = DUAL),
        release("Anime Time", 1080, "HEVC", 10, 9, 310, 10),
        release("Tsundere-Raws", 1080, "AVC", 8, 6, 1380, 11),
    )

    /** A streaming source: servers with HLS and MP4 links (stream cards, no torrent data). */
    val streams: List<VideoExtractor> get() = listOf(
        Ext(
            VideoServer("HD-1", "https://example.invalid/embed/1"),
            listOf(
                Video(1080, VideoType.M3U8, FileUrl("https://example.invalid/1080.m3u8")),
                Video(720, VideoType.M3U8, FileUrl("https://example.invalid/720.m3u8")),
                Video(360, VideoType.M3U8, FileUrl("https://example.invalid/360.m3u8")),
            ),
        ),
        Ext(
            VideoServer("Vidstream", "https://example.invalid/embed/2"),
            listOf(Video(1080, VideoType.CONTAINER, FileUrl("https://example.invalid/1080.mp4"))),
        ),
        Ext(
            VideoServer("Mp4Upload (dub)", "https://example.invalid/embed/3"),
            listOf(Video(720, VideoType.CONTAINER, FileUrl("https://example.invalid/720.mp4"))),
        ),
    )
}
