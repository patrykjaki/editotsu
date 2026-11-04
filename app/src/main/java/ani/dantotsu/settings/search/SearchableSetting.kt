package ani.dantotsu.settings.search

/** A search hit shown on the settings home screen. */
data class SearchableSetting(
    val title: String,
    val desc: String? = null,
    val icon: Int = ani.dantotsu.R.drawable.ic_round_settings_24,
    val breadcrumbs: String = "",
    val targetActivity: Class<out android.app.Activity>,
    /** Row id the target screen scrolls to and flashes. */
    val rowId: String? = null,
)
