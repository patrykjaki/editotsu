package ani.dantotsu.settings

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.customAlertDialog

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
        val currentResolutions = PrefManager.getPreferredDownloadResolutions(extensionName).toMutableList()
        val fullList = currentResolutions + ALL_RESOLUTIONS.filter { it !in currentResolutions }
        val checkedItems = fullList.map { it in currentResolutions }.toBooleanArray()

        context.customAlertDialog().apply {
            setTitle(R.string.preferred_download_resolutions)
            multiChoiceItems(fullList.toTypedArray(), checkedItems) { updatedSelection ->
                currentResolutions.clear()
                fullList.forEachIndexed { index, res ->
                    if (updatedSelection[index]) {
                        currentResolutions.add(res)
                    }
                }
            }
            setPosButton(R.string.ok) {
                val toSave = if (currentResolutions.isEmpty()) ALL_RESOLUTIONS else currentResolutions
                PrefManager.setPreferredDownloadResolutions(extensionName, toSave)
                onUpdated?.invoke(toSave)
            }
            setNeutralButton(R.string.reset) {
                PrefManager.setPreferredDownloadResolutions(extensionName, ALL_RESOLUTIONS)
                onUpdated?.invoke(ALL_RESOLUTIONS)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
