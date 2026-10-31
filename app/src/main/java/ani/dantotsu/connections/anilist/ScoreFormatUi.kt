package ani.dantotsu.connections.anilist

import ani.dantotsu.connections.anilist.api.ScoreFormat

/**
 * The signed-in user's AniList score format. Falls back to POINT_10_DECIMAL
 * (the old "x / 10" display) when unknown, e.g. MAL-only or not loaded yet.
 */
fun userScoreFormat(): ScoreFormat =
    ScoreFormat.entries.firstOrNull { it.name == Anilist.scoreFormat }
        ?: ScoreFormat.POINT_10_DECIMAL

/** Card/list score text in the user's format; see [ScoreFormats.cardText]. */
fun cardScoreText(userRaw: Int, meanScore: Int?): String =
    ScoreFormats.cardText(userRaw, meanScore, userScoreFormat())
