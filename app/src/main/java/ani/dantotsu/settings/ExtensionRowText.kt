package ani.dantotsu.settings

/** Second line of an extension row: "English · 16.10 · 18+ · Yūzōnō". */
object ExtensionRowText {
    const val SEPARATOR = " · "

    fun versionLine(lang: String, version: String, nsfw: Boolean, repo: String?): String =
        listOf(lang, version, if (nsfw) "18+" else "", repo.orEmpty())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(SEPARATOR)
}
