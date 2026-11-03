package ani.dantotsu.settings

/**
 * One titled block of rows on a settings group screen. [advanced] rows follow the
 * section's rows and stay hidden until the screen's "Show advanced settings" toggle
 * is on; search still finds them.
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
     * Each section's header and rows, with its advanced rows after them (hidden), then
     * one "Show advanced settings" toggle at the end of the screen that shows or hides
     * every advanced row at once. A section with only advanced rows hides its header
     * too. Everything starts expanded when [expandName] (a search highlight) is an
     * advanced row. Advanced rows keep their own [Settings.isVisible] as the condition
     * for showing once expanded.
     */
    fun flatten(
        sections: List<SettingsSection>,
        advancedLabel: String,
        expandName: String? = null,
    ): ArrayList<Settings> {
        val out = ArrayList<Settings>()
        val toggled = ArrayList<Settings>()
        val wanted = ArrayList<Boolean>()
        val expanded = expandName != null && sections.any { section ->
            section.advanced.any { it.name.equals(expandName, ignoreCase = true) }
        }
        for (section in sections) {
            if (section.rows.isEmpty() && section.advanced.isEmpty()) continue
            val head = header(section.title)
            out += head
            if (section.rows.isEmpty()) {
                toggled += head
                wanted += true
                head.isVisible = expanded
            }
            out += section.rows
            for (row in section.advanced) {
                toggled += row
                wanted += row.isVisible
                row.isVisible = expanded && row.isVisible
                out += row
            }
        }
        if (toggled.isNotEmpty()) {
            out += Settings(
                type = SettingsRowType.ADVANCED,
                name = advancedLabel,
                desc = "",
                icon = 0,
                isChecked = expanded,
                advancedRows = toggled,
                advancedVisibility = wanted,
            )
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
