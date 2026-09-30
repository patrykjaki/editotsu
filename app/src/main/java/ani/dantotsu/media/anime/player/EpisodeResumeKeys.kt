package ani.dantotsu.media.anime.player

import java.math.BigDecimal

/** Legacy numeric aliases are safe only when they cannot name a different episode. */
object EpisodeResumeKeys {
    private val numericEpisode = Regex("[0-9]+(?:\\.[0-9]+)?")

    fun compatibleAlias(key: String, episodeKeys: Set<String>): String? {
        if (!numericEpisode.matches(key)) return null
        val alias = BigDecimal(key).stripTrailingZeros().toPlainString()
        return alias.takeIf { it != key && it !in episodeKeys }
    }
}
