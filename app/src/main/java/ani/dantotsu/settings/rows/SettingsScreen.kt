package ani.dantotsu.settings.rows

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One settings group screen (Player, Reading, ...). */
abstract class SettingsScreen(
    val id: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    /** Second line on the settings home list. */
    @StringRes val summary: Int,
) {
    /** The activity that shows this screen; search opens it. */
    abstract val activity: Class<out Activity>

    /** Built fresh on every refresh. Must not read prefs or a Context (see [Row]). */
    abstract fun sections(): List<Section>

    fun rows(): List<Row> = sections().flatMap { it.rows }
}

/** What the list on a screen shows, in order. */
sealed interface ScreenItem {
    val key: String

    data class Header(@StringRes val title: Int) : ScreenItem {
        override val key get() = "header:$title"
    }

    class RowItem(val row: Row) : ScreenItem {
        override val key get() = row.id
    }

    data class AdvancedToggle(val expanded: Boolean) : ScreenItem {
        override val key get() = "advanced"
    }
}

object ScreenItems {
    /**
     * Section headers and their rows, in definition order. Advanced rows show only when
     * [showAdvanced] is on; a section with nothing to show loses its header, and a
     * section titled 0 has none (single-section screens). One
     * advanced toggle closes the screen when any visible row is advanced.
     */
    fun build(
        sections: List<Section>,
        showAdvanced: Boolean,
        isVisible: (Row) -> Boolean = { it.visible() },
    ): List<ScreenItem> {
        val out = ArrayList<ScreenItem>()
        var anyAdvanced = false
        for (section in sections) {
            val visible = section.rows.filter(isVisible)
            if (visible.any { it.advanced }) anyAdvanced = true
            val shown = visible.filter { showAdvanced || !it.advanced }
            if (shown.isEmpty()) continue
            if (section.title != 0) out += ScreenItem.Header(section.title)
            shown.forEach { out += ScreenItem.RowItem(it) }
        }
        if (anyAdvanced) out += ScreenItem.AdvancedToggle(showAdvanced)
        return out
    }
}
