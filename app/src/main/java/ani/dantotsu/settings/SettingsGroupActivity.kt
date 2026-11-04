package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.screens.SourcesScreen

/** Settings > Sources. */
class SettingsGroupActivity : SettingsScreenActivity() {
    override val screen get() = SourcesScreen

    companion object {
        fun sources(context: Context) = Intent(context, SettingsGroupActivity::class.java)
    }
}
