package ani.dantotsu.settings

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet

/**
 * MAX MERGE M3 (upstream e954d9f1): preferred-resolution priority editor.
 * Global list by default, optional per-extension override (kept out of the
 * exported backup schema). Empty selection resets to the full list.
 */
object ResolutionPriorityDialog {
    val ALL_RESOLUTIONS = listOf("1080p", "720p", "480p", "360p", "240p", "144p")

    fun show(
        context: Context,
        extensionName: String? = null,
        onUpdated: ((List<String>) -> Unit)? = null,
    ) {
        val currentResolutions = PrefManager.getPreferredDownloadResolutions(extensionName)
        val fullList = currentResolutions + ALL_RESOLUTIONS.filter { it !in currentResolutions }
        val checkedItems = fullList.map { it in currentResolutions }.toBooleanArray()

        // Ticked resolutions are tried in list order; none ticked means all of them.
        ChoiceSheet.multi(
            context, context.getString(R.string.preferred_download_resolutions),
            fullList.map { ChoiceOption(it) }, checkedItems,
            confirm = ChoiceSheet.Confirm(R.string.ok) { selection ->
                val chosen = fullList.filterIndexed { i, _ -> selection[i] }
                val toSave = chosen.ifEmpty { ALL_RESOLUTIONS }
                PrefManager.setPreferredDownloadResolutions(extensionName, toSave)
                onUpdated?.invoke(toSave)
            },
            onReset = {
                PrefManager.setPreferredDownloadResolutions(extensionName, ALL_RESOLUTIONS)
                onUpdated?.invoke(ALL_RESOLUTIONS)
            },
        )
    }
}
