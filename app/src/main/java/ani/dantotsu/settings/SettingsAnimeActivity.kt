package ani.dantotsu.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsAnimeBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAnimeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAnimeBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAnimeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {

            settingsAnimeLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            animeSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            val highlightKey = intent.getStringExtra(ani.dantotsu.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.player_settings),
                        desc = getString(R.string.player_settings_desc),
                        icon = R.drawable.ic_round_video_settings_24,
                        onClick = {
                            startActivity(Intent(context, PlayerSettingsActivity::class.java))
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.purge_anime_downloads),
                        desc = getString(R.string.purge_anime_downloads_desc),
                        icon = R.drawable.ic_round_delete_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.purge_anime_downloads)
                                setMessage(R.string.purge_confirm, getString(R.string.anime))
                                setPosButton(R.string.yes, onClick = {
                                    val downloadsManager = Injekt.get<DownloadsManager>()
                                    downloadsManager.purgeDownloads(MediaType.ANIME)
                                })
                                setNegButton(R.string.no)
                                show()
                            }
                        }

                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.prefer_dub),
                        desc = getString(R.string.prefer_dub_desc),
                        icon = R.drawable.ic_round_audiotrack_24,
                        isChecked = PrefManager.getVal(PrefName.SettingsPreferDub),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SettingsPreferDub, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.show_yt),
                        desc = getString(R.string.show_yt_desc),
                        icon = R.drawable.ic_round_play_circle_24,
                        isChecked = PrefManager.getVal(PrefName.ShowYtButton),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.ShowYtButton, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.include_list),
                        desc = getString(R.string.include_list_anime_desc),
                        icon = R.drawable.view_list_24,
                        isChecked = PrefManager.getVal(PrefName.IncludeAnimeList),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.IncludeAnimeList, isChecked)
                            restartApp()
                        }
                    ),
                    // MAX MERGE M3 (upstream e954d9f1).
                    // MAX MERGE M3 (upstream e954d9f1).
                    Settings(
                        type = 1,
                        name = getString(R.string.preferred_download_resolutions),
                        desc = PrefManager.getPreferredDownloadResolutions(null).joinToString(", "),
                        icon = R.drawable.ic_round_high_quality_24,
                        onClick = {
                            ResolutionPriorityDialog.show(context, null) {
                                (settingsRecyclerView.adapter as? SettingsAdapter)?.notifyDataSetChanged()
                            }
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.auto_select_resolution_priority),
                        desc = getString(R.string.auto_select_resolution_priority_desc),
                        icon = R.drawable.ic_round_high_quality_24,
                        isChecked = PrefManager.getVal(PrefName.AutoSelectResolutionPriority),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.AutoSelectResolutionPriority, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.smart_download_anime),
                        desc = getString(R.string.smart_download_anime_desc),
                        icon = R.drawable.ic_download_24,
                        isChecked = PrefManager.getVal(PrefName.SmartDownloadAnime),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SmartDownloadAnime, isChecked)
                        }
                    ),
                    themeChoice(
                        R.string.theme_pref_resolution, R.drawable.ic_round_high_quality_24,
                        PrefName.AnimeThemesPreferredResolution,
                        arrayOf(
                            getString(R.string.theme_opt_auto), "1080p", "720p", getString(R.string.theme_opt_480p_or_lower),
                        ),
                    ),
                    themeChoice(
                        R.string.theme_pref_source, R.drawable.ic_round_movie_filter_24,
                        PrefName.AnimeThemesPreferredSource,
                        arrayOf(getString(R.string.theme_opt_auto), getString(R.string.theme_opt_bd), getString(R.string.theme_opt_web)),
                    ),
                    themeChoice(
                        R.string.theme_pref_credits, R.drawable.ic_round_video_settings_24,
                        PrefName.AnimeThemesPreferredCredits,
                        arrayOf(
                            getString(R.string.theme_opt_auto), getString(R.string.theme_opt_creditless),
                            getString(R.string.theme_opt_with_credits),
                        ),
                    ),
                    themeChoice(
                        R.string.theme_pref_subtitles, R.drawable.ic_round_subtitles_24,
                        PrefName.AnimeThemesPreferredSubtitles,
                        arrayOf(
                            getString(R.string.theme_opt_auto), getString(R.string.theme_opt_no_subtitles),
                            getString(R.string.theme_opt_subtitled),
                        ),
                    ),
                ),
                highlightKey = highlightKey
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }

            var previousEp: View = when (PrefManager.getVal<Int>(PrefName.AnimeDefaultView)) {
                0 -> settingsEpList
                1 -> settingsEpGrid
                2 -> settingsEpCompact
                else -> settingsEpList
            }
            previousEp.alpha = 1f
            fun uiEp(mode: Int, current: View) {
                previousEp.alpha = 0.33f
                previousEp = current
                current.alpha = 1f
                PrefManager.setVal(PrefName.AnimeDefaultView, mode)
            }

            settingsEpList.setOnClickListener {
                uiEp(0, it)
            }

            settingsEpGrid.setOnClickListener {
                uiEp(1, it)
            }

            settingsEpCompact.setOnClickListener {
                uiEp(2, it)
            }

        }
    }

    /** One AnimeThemes video preference: the row shows the current option, a tap lists them. */
    private fun themeChoice(name: Int, icon: Int, pref: PrefName, options: Array<String>) = Settings(
        type = 1,
        name = getString(name),
        desc = getString(R.string.theme_pref_desc, options.getOrElse(PrefManager.getVal<Int>(pref)) { options[0] }),
        icon = icon,
        onClick = { b ->
            customAlertDialog().apply {
                setTitle(getString(name))
                singleChoiceItems(options, PrefManager.getVal<Int>(pref).coerceIn(0, options.lastIndex)) { which ->
                    PrefManager.setVal(pref, which)
                    b.settingsDesc.text = getString(R.string.theme_pref_desc, options[which])
                }
                show()
            }
        },
    )
}
