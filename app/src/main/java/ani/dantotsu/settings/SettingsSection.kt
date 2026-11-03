package ani.dantotsu.settings

/**
 * One titled block of rows on a settings group screen. [advanced] rows sit under a
 * collapsed "Advanced" row at the end of the section; search still finds them.
 */
data class SettingsSection(
    val title: String,
    val rows: List<Settings>,
    val advanced: List<Settings> = emptyList(),
)

object SettingsRowType {
    const val CLICK = 1
    const val SWITCH = 2
    const val HEADER = 3
    const val ADVANCED = 4
}

object SettingsSections {
    /**
     * Header, rows, then (if any) an Advanced toggle followed by the advanced rows, which
     * start hidden unless [expandName] (a search highlight) is one of them. Advanced rows
     * keep their own [Settings.isVisible] as the condition for showing once expanded.
     */
    fun flatten(
        sections: List<SettingsSection>,
        advancedLabel: String,
        expandName: String? = null,
    ): ArrayList<Settings> {
        val out = ArrayList<Settings>()
        for (section in sections) {
            if (section.rows.isEmpty() && section.advanced.isEmpty()) continue
            out += header(section.title)
            out += section.rows
            if (section.advanced.isNotEmpty()) {
                val expanded = expandName != null &&
                    section.advanced.any { it.name.equals(expandName, ignoreCase = true) }
                val wanted = section.advanced.map { it.isVisible }
                section.advanced.forEachIndexed { i, row -> row.isVisible = expanded && wanted[i] }
                out += Settings(
                    type = SettingsRowType.ADVANCED,
                    name = advancedLabel,
                    desc = "",
                    icon = 0,
                    isChecked = expanded,
                    advancedRows = section.advanced,
                    advancedVisibility = wanted,
                )
                out += section.advanced
            }
        }
        return out
    }

    fun header(title: String) = Settings(
        type = SettingsRowType.HEADER,
        name = title,
        desc = "",
        icon = 0,
    )
}
