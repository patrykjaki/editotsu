package ani.dantotsu.settings

import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString

/**
 * Settings > Tracking > Progress updates: the "ask before updating progress" switches that
 * were on the Player and Reader screens. Same prefs; chapter zero is off-limits while
 * "ask individually" is on, as before.
 */
object ProgressSettingsRows {

    fun section(activity: AppCompatActivity, rebuild: () -> Unit): SettingsSection = with(activity) {
        SettingsSection(
            title = getString(R.string.settings_section_progress),
            rows = media(
                activity, rebuild,
                ask = PrefName.AskIndividualPlayer, askName = R.string.ask_update_progress_anime,
                askDesc = R.string.ask_update_progress_info_ep,
                zero = PrefName.ChapterZeroPlayer,
                adult = PrefName.UpdateForHPlayer, adultName = R.string.ask_update_progress_hentai,
            ) + media(
                activity, rebuild,
                ask = PrefName.AskIndividualReader, askName = R.string.ask_update_progress_manga,
                askDesc = R.string.ask_update_progress_info_chap,
                zero = PrefName.ChapterZeroReader,
                adult = PrefName.UpdateForHReader, adultName = R.string.ask_update_progress_doujin,
            )
        )
    }

    private fun media(
        activity: AppCompatActivity,
        rebuild: () -> Unit,
        ask: PrefName, askName: Int, askDesc: Int,
        zero: PrefName,
        adult: PrefName, adultName: Int,
    ): List<Settings> = with(activity) {
        val asking = PrefManager.getVal<Boolean>(ask)
        listOf(
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(askName),
                desc = getString(askDesc),
                icon = R.drawable.ic_round_sync_24,
                isChecked = asking,
                switch = { isChecked, _ ->
                    PrefManager.setVal(ask, isChecked)
                    rebuild()
                }
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.ask_update_progress_chapter_zero),
                desc = getString(R.string.ask_update_progress_info_zero),
                icon = R.drawable.ic_round_sync_24,
                isChecked = PrefManager.getVal(zero),
                switch = { isChecked, _ -> PrefManager.setVal(zero, isChecked) },
                attachToSwitch = {
                    it.settingsButton.isEnabled = !asking
                    it.settingsLayout.alpha = if (asking) 0.5f else 1f
                }
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(adultName),
                desc = "",
                icon = R.drawable.ic_round_nsfw_24,
                isChecked = PrefManager.getVal(adult),
                switch = { isChecked, _ ->
                    PrefManager.setVal(adult, isChecked)
                    if (isChecked) snackString(getString(R.string.very_bold))
                }
            ),
        )
    }
}
