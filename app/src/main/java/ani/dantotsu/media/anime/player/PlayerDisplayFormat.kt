package ani.dantotsu.media.anime.player

import java.util.Locale

/** Display only: never rewrite an episode's storage or provider identity. */
object PlayerDisplayFormat {
    private val wholeEpisode = Regex("([0-9]+)\\.0+")

    fun episodeNumber(number: String): String =
        wholeEpisode.matchEntire(number)?.groupValues?.get(1) ?: number

    fun speed(speed: Float): String = String.format(Locale.US, "%.2fx", speed)
}

/** The preference stores an index; only this boundary converts it to an absolute speed. */
object PlaybackSpeedSelection {
    fun values(cursed: Boolean): List<Float> = if (cursed) {
        listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
    } else {
        listOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
    }
}
