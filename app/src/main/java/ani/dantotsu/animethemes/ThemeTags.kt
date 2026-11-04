package ani.dantotsu.animethemes

/**
 * One tag format everywhere: "OP 1", "ED 12". AnimeThemes leaves the sequence empty
 * on shows with a single opening or ending, and the slug fallback reads "OP1"; this
 * puts the space back. Other slugs ("OP", odd ones) stay as they are.
 */
object ThemeTags {
    private val SLUG = Regex("^(OP|ED)(\\d+)(.*)$", RegexOption.IGNORE_CASE)

    fun display(type: String?, sequence: Int?, slug: String): String =
        if (!type.isNullOrBlank() && sequence != null) "$type $sequence" else normalize(slug)

    fun normalize(tag: String): String {
        val m = SLUG.matchEntire(tag.trim()) ?: return tag.trim()
        val (kind, number, rest) = m.destructured
        val suffix = rest.trimStart('-', ' ', '_')
        return listOf("${kind.uppercase()} $number", suffix).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
