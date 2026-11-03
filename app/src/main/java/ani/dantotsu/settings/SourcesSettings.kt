package ani.dantotsu.settings

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.databinding.ItemRepositoryBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import eu.kanade.domain.base.BasePreferences
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Settings > Sources: extensions and repositories, torrents, search. */
object SourcesSettings {

    fun sections(activity: AppCompatActivity, rebuild: () -> Unit): List<SettingsSection> =
        listOf(
            extensions(activity),
            torrents(activity, rebuild),
            search(activity),
        )

    private fun repoPref(type: MediaType) = when (type) {
        MediaType.ANIME -> PrefName.AnimeExtensionRepos
        MediaType.MANGA -> PrefName.MangaExtensionRepos
        MediaType.NOVEL -> PrefName.NovelExtensionRepos
    }

    private fun showRepos(list: ViewGroup, type: MediaType) {
        list.removeAllViews()
        PrefManager.getVal<Set<String>>(repoPref(type)).forEach { item ->
            val view = ItemRepositoryBinding.inflate(LayoutInflater.from(list.context), list, true)
            view.repositoryItem.text = item.removePrefix("https://raw.githubusercontent.com/")
            view.repositoryItem.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                copyToClipboard(item, true)
                true
            }
        }
        list.isVisible = list.childCount > 0
    }

    private fun repoRow(activity: AppCompatActivity, type: MediaType, name: Int, desc: Int) = Settings(
        type = SettingsRowType.CLICK,
        name = activity.getString(name),
        desc = activity.getString(desc),
        icon = R.drawable.ic_github,
        onClick = { row ->
            AddRepositoryBottomSheet.newInstance(
                type,
                PrefManager.getVal<Set<String>>(repoPref(type)).toList(),
                onRepositoryAdded = { input, mediaType ->
                    AddRepositoryBottomSheet.addRepo(input, mediaType)
                    showRepos(row.attachView, mediaType)
                },
                onRepositoryRemoved = { item, mediaType ->
                    AddRepositoryBottomSheet.removeRepo(item, mediaType)
                    showRepos(row.attachView, mediaType)
                }
            ).show(activity.supportFragmentManager, "add_repo")
        },
        attach = { showRepos(it.attachView, type) }
    )

    private fun extensions(activity: AppCompatActivity): SettingsSection = with(activity) {
        val installer = Injekt.get<BasePreferences>().extensionInstaller()
        SettingsSection(
            title = getString(R.string.settings_section_extensions),
            rows = listOf(
                repoRow(this, MediaType.ANIME, R.string.anime_add_repository, R.string.anime_add_repository_desc),
                repoRow(this, MediaType.MANGA, R.string.manga_add_repository, R.string.manga_add_repository_desc),
                repoRow(this, MediaType.NOVEL, R.string.novel_add_repository, R.string.novel_add_repository_desc),
                Settings(
                    type = SettingsRowType.SWITCH,
                    name = getString(R.string.NSFWExtention),
                    desc = getString(R.string.NSFWExtention_desc),
                    icon = R.drawable.ic_round_nsfw_24,
                    isChecked = PrefManager.getVal(PrefName.NSFWExtension),
                    switch = { isChecked, _ -> PrefManager.setVal(PrefName.NSFWExtension, isChecked) }
                ),
                Settings(
                    type = SettingsRowType.SWITCH,
                    name = getString(R.string.skip_loading_extension_icons),
                    desc = getString(R.string.skip_loading_extension_icons_desc),
                    icon = R.drawable.ic_round_no_icon_24,
                    isChecked = PrefManager.getVal(PrefName.SkipExtensionIcons),
                    switch = { isChecked, _ -> PrefManager.setVal(PrefName.SkipExtensionIcons, isChecked) }
                ),
            ),
            advanced = listOf(
                CommonSettingsRows.dns(this),
                Settings(
                    type = SettingsRowType.CLICK,
                    name = getString(R.string.extension_installer),
                    desc = installer.get().titleResId,
                    icon = R.drawable.ic_round_new_releases_24,
                    onClick = {
                        customAlertDialog().apply {
                            setTitle(getString(R.string.extension_installer))
                            val entries = installer.entries
                            val options = entries.map { it.titleResId }.toTypedArray()
                            singleChoiceItems(options, entries.indexOf(installer.get())) { which ->
                                installer.set(entries[which])
                                restartApp()
                            }
                            show()
                        }
                    }
                ),
                Settings(
                    type = SettingsRowType.CLICK,
                    name = getString(R.string.extension_test),
                    desc = getString(R.string.extension_test_desc),
                    icon = R.drawable.ic_round_search_sources_24,
                    isActivity = true,
                    onClick = { startActivity(Intent(this, ParserTestActivity::class.java)) }
                ),
                Settings(
                    type = SettingsRowType.CLICK,
                    name = getString(R.string.user_agent),
                    desc = getString(R.string.user_agent_desc),
                    icon = R.drawable.ic_round_video_settings_24,
                    onClick = {
                        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
                        val editText = dialogView.userAgentTextBox
                        editText.setText(PrefManager.getVal<String>(PrefName.DefaultUserAgent))
                        customAlertDialog().apply {
                            setTitle(R.string.user_agent)
                            setCustomView(dialogView.root)
                            setPosButton(R.string.ok) {
                                PrefManager.setVal(PrefName.DefaultUserAgent, editText.text.toString())
                            }
                            setNeutralButton(R.string.reset) {
                                PrefManager.removeVal(PrefName.DefaultUserAgent)
                                editText.setText("")
                            }
                            setNegButton(R.string.cancel)
                        }.show()
                    }
                ),
                Settings(
                    type = SettingsRowType.SWITCH,
                    name = getString(R.string.proxy),
                    desc = getString(R.string.proxy_desc),
                    icon = R.drawable.swap_horizontal_circle_24,
                    isChecked = PrefManager.getVal(PrefName.EnableSocks5Proxy),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.EnableSocks5Proxy, isChecked)
                        restartApp()
                    }
                ),
                Settings(
                    type = SettingsRowType.CLICK,
                    name = getString(R.string.proxy_setup),
                    desc = getString(R.string.proxy_setup_desc),
                    icon = R.drawable.lan_24,
                    onClick = { ProxyDialogFragment().show(supportFragmentManager, "dialog") }
                ),
            )
        )
    }

    private fun torrents(activity: AppCompatActivity, rebuild: () -> Unit): SettingsSection = with(activity) {
        val enabled = PrefManager.getVal<Boolean>(PrefName.TorrentEnabled)
        val enable = Settings(
            type = SettingsRowType.SWITCH,
            name = getString(R.string.enable_torrent),
            desc = getString(R.string.enable_torrent_desc),
            icon = R.drawable.ic_round_dns_24,
            isChecked = enabled,
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.TorrentEnabled, isChecked)
                lifecycleScope.launchIO {
                    if (isChecked && !TorrentServerService.isRunning()) TorrentServerService.start()
                    if (!isChecked && TorrentServerService.isRunning()) TorrentServerService.stop()
                }
                rebuild()
            }
        )
        if (!enabled) {
            return SettingsSection(getString(R.string.settings_section_torrents), listOf(enable))
        }
        SettingsSection(
            title = getString(R.string.settings_section_torrents),
            rows = listOf(
                enable,
                Settings(
                    type = SettingsRowType.CLICK,
                    name = getString(R.string.play_magnet_or_torrent),
                    desc = getString(R.string.play_magnet_or_torrent_desc),
                    icon = R.drawable.ic_round_play_arrow_24,
                    onClick = {
                        ani.dantotsu.torrent.ManualTorrentBottomSheet.newInstance()
                            .show(supportFragmentManager, "ManualTorrentBottomSheet")
                    }
                ),
                prefSwitch(R.string.torrent_wifi_only, R.string.torrent_wifi_only_desc, R.drawable.lan_24, PrefName.TorrentWifiOnly),
                prefSwitch(R.string.torrent_encryption, R.string.torrent_encryption_desc, R.drawable.ic_shield, PrefName.TorrentEncryption),
                prefSwitch(R.string.torrent_battery_saving, R.string.torrent_battery_saving_desc, R.drawable.ic_lightbulb_24, PrefName.TorrentBatterySaving),
            ),
            advanced = listOf(
                numberRow(R.string.torrent_download_speed, PrefName.TorrentDownloadSpeedLimit, R.drawable.ic_download_24) {
                    if (it > 0) "$it KB/s" else getString(R.string.disabled)
                },
                numberRow(R.string.torrent_upload_speed, PrefName.TorrentUploadSpeedLimit, R.drawable.ic_download_24) {
                    if (it > 0) "$it KB/s" else getString(R.string.disabled)
                },
                numberRow(R.string.torrent_max_connections, PrefName.TorrentMaxConnections, R.drawable.ic_round_dns_24) {
                    it.toString()
                },
                numberRow(R.string.torrent_port, PrefName.TorrentPort, R.drawable.network_node_24) {
                    if (it > 0) it.toString() else getString(R.string.disabled)
                },
                prefSwitch(R.string.torrent_disable_utp, R.string.torrent_disable_utp_desc, R.drawable.lan_24, PrefName.TorrentDisableUtp),
            )
        )
    }

    private fun search(activity: AppCompatActivity): SettingsSection = SettingsSection(
        title = activity.getString(R.string.settings_section_search),
        rows = CommonSettingsRows.searchSourceList(activity),
    )

    /** A switch bound to one boolean pref. */
    fun AppCompatActivity.prefSwitch(name: Int, desc: Int, icon: Int, pref: PrefName) = Settings(
        type = SettingsRowType.SWITCH,
        name = getString(name),
        desc = getString(desc),
        icon = icon,
        isChecked = PrefManager.getVal(pref),
        switch = { isChecked, _ -> PrefManager.setVal(pref, isChecked) }
    )

    /** A row that edits one int pref in a number dialog; [label] formats the value. */
    private fun AppCompatActivity.numberRow(name: Int, pref: PrefName, icon: Int, label: (Int) -> String) =
        Settings(
            type = SettingsRowType.CLICK,
            name = getString(name),
            desc = label(PrefManager.getVal(pref)),
            icon = icon,
            onClick = { row ->
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText(PrefManager.getVal<Int>(pref).toString())
                    setSelection(text.length)
                }
                val margin = (24 * resources.displayMetrics.density).toInt()
                val container = android.widget.FrameLayout(this).apply {
                    addView(input, android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = margin; rightMargin = margin; topMargin = margin / 3; bottomMargin = margin / 3 })
                }
                customAlertDialog().apply {
                    setTitle(getString(name))
                    setCustomView(container)
                    setPosButton(R.string.yes, onClick = {
                        val value = input.text.toString().toIntOrNull() ?: 0
                        PrefManager.setVal(pref, value)
                        row.settingsDesc.text = label(value)
                    })
                    setNegButton(R.string.no)
                    show()
                }
            }
        )
}
