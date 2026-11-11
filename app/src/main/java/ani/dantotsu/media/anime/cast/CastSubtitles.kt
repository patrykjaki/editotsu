package ani.dantotsu.media.anime.cast

/** Which subtitles the default Cast receiver can show. */
object CastSubtitles {
    const val VTT = "text/vtt"
    const val TTML = "application/ttml+xml"

    /**
     * MIME type for the receiver, or null when it cannot render the format (ASS, SRT):
     * the default receiver supports WebVTT and TTML text tracks only.
     */
    fun mimeFor(type: String?, url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            type.equals("VTT", ignoreCase = true) -> VTT
            type.equals("TTML", ignoreCase = true) -> TTML
            type.equals("ASS", ignoreCase = true) || type.equals("SSA", ignoreCase = true) ||
                type.equals("SRT", ignoreCase = true) -> null
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> VTT
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> TTML
            else -> null
        }
    }
}
