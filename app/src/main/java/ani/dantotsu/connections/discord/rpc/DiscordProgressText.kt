package ani.dantotsu.connections.discord.rpc

/** The progress line on the Discord card: "Episode 3 of 12", "Chapter 1". */
internal object DiscordProgressText {
    private val numberPattern = Regex("""\d+(?:\.\d+)?""")

    /** Sources sometimes put the title in the number ("Ch. 1 - Ryomen Sukuna", "1-Sub & Dub"): keep just the number. */
    fun number(raw: String): String = numberPattern.find(raw)?.value?.removeSuffix(".0") ?: raw.trim()

    fun progress(label: String, number: String, total: Int?): String =
        if (total != null && total > 0) "$label $number of $total" else "$label $number"
}
