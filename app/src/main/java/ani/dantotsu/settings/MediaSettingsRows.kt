package ani.dantotsu.settings

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.media.MediaType
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Rows from the old Anime and Manga screens, now on Player, Reading and Data & storage. */
object MediaSettingsRows {

    /** Settings > Player > Anime. */
    fun anime(activity: AppCompatActivity): List<Settings> = with(activity) {
        listOf(
            layoutChoice(
                R.string.default_ep_view, PrefName.AnimeDefaultView,
                arrayOf(getString(R.string.list), getString(R.string.grid), getString(R.string.compact)),
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
            Settings(
                type = 1,
                name = getString(R.string.preferred_download_resolutions),
                desc = PrefManager.getPreferredDownloadResolutions(null).joinToString(", "),
                icon = R.drawable.ic_round_high_quality_24,
                onClick = { row ->
                    ResolutionPriorityDialog.show(this, null) {
                        row.settingsDesc.text = PrefManager.getPreferredDownloadResolutions(null).joinToString(", ")
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
        )
    }

    /** Settings > Reading > Library. */
    fun manga(activity: AppCompatActivity): List<Settings> = with(activity) {
        listOf(
            layoutChoice(
                R.string.default_chp_view, PrefName.MangaDefaultView,
                arrayOf(getString(R.string.list), getString(R.string.compact)),
            ),
            Settings(
                type = 2,
                name = getString(R.string.include_list),
                desc = getString(R.string.include_list_desc),
                icon = R.drawable.view_list_24,
                isChecked = PrefManager.getVal(PrefName.IncludeMangaList),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.IncludeMangaList, isChecked)
                    restartApp()
                }
            ),
            // MAX MERGE M3 (upstream e954d9f1, OFF by default).
            Settings(
                type = 2,
                name = getString(R.string.smart_download_manga),
                desc = getString(R.string.smart_download_manga_desc),
                icon = R.drawable.ic_download_24,
                isChecked = PrefManager.getVal(PrefName.SmartDownloadManga),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.SmartDownloadManga, isChecked)
                }
            ),
        )
    }

    /** Settings > Data & storage: delete all downloads of one type. */
    fun purges(activity: AppCompatActivity): List<Settings> = with(activity) {
        val context = activity
        listOf(
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
                type = 1,
                name = getString(R.string.purge_manga_downloads),
                desc = getString(R.string.purge_manga_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.purge_manga_downloads)
                        setMessage(R.string.purge_confirm, getString(R.string.manga))
                        setPosButton(R.string.yes, onClick = {
                            // Authoritative purge-all via the service scope
                            // barrier (all live manga owners cancelled+joined
                            // before physical deletion). The service reports
                            // the honest outcome itself.
                            val intent = Intent(context, MangaDownloaderService::class.java).apply {
                                action = MangaDownloaderService.ACTION_PURGE_MANGA
                            }
                            ContextCompat.startForegroundService(context, intent)
                        })
                        setNegButton(R.string.no)
                        show()
                    }
                }
            ),
            Settings(
                type = 1,
                name = getString(R.string.purge_novel_downloads),
                desc = getString(R.string.purge_novel_downloads_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.purge_novel_downloads)
                        setMessage(R.string.purge_confirm, getString(R.string.novels))
                        setPosButton(R.string.yes) {
                            val downloadsManager = Injekt.get<DownloadsManager>()
                            downloadsManager.purgeDownloads(MediaType.NOVEL)
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                }
            ),
        )
    }

    /** Episode/chapter list layout; the row shows the current one. */
    private fun AppCompatActivity.layoutChoice(name: Int, pref: PrefName, options: Array<String>) = Settings(
        type = SettingsRowType.CLICK,
        name = getString(name),
        desc = options.getOrElse(PrefManager.getVal<Int>(pref)) { options[0] },
        icon = R.drawable.view_list_24,
        onClick = { b ->
            customAlertDialog().apply {
                setTitle(getString(name))
                singleChoiceItems(options, PrefManager.getVal<Int>(pref).coerceIn(0, options.lastIndex)) { which ->
                    PrefManager.setVal(pref, which)
                    b.settingsDesc.text = options[which]
                }
                show()
            }
        },
    )

    /** One AnimeThemes video preference: the row shows the current option, a tap lists them. */
    private fun AppCompatActivity.themeChoice(name: Int, icon: Int, pref: PrefName, options: Array<String>) = Settings(
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
