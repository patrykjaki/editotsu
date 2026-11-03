package ani.dantotsu.animethemes

import ani.dantotsu.others.AniSkip

/**
 * The "now playing" chip in the episode player: which opening or ending is on screen,
 * from the episode's skip segments (TheIntroDB, AniSkip or the extension) and the show's
 * AnimeThemes list. The chip lives inside the player controls, so it only shows while
 * they do.
 */
object NowPlaying {
    /** False inside an opening segment, true inside an ending, null elsewhere. */
    fun kindAt(stamps: List<AniSkip.Stamp>?, positionS: Double): Boolean? {
        val stamp = stamps?.firstOrNull { positionS >= it.interval.startTime && positionS < it.interval.endTime } ?: return null
        return when (stamp.skipType) {
            "op", "mixed-op" -> false
            "ed", "mixed-ed" -> true
            else -> null
        }
    }

    /** "OP 1 · Idol · YOASOBI"; parts AnimeThemes leaves empty are dropped. */
    fun label(track: ThemeTrack): String =
        listOf(track.tag, track.songTitle.takeIf { it != track.tag }, track.artist)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" · ")

    /** Episode number from the player's episode key ("12", "12.5" -> 12); null when not a number. */
    fun episodeNumber(key: String?): Int? = key?.trim()?.toFloatOrNull()?.toInt()
}
