package ani.dantotsu.settings.screens

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.databinding.ItemRepositoryBinding
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.AddRepositoryBottomSheet
import ani.dantotsu.settings.ProxyDialogFragment
import ani.dantotsu.settings.SettingsGroupActivity
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.RowView
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import eu.kanade.domain.base.BasePreferences
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Settings > Sources: extensions and repositories, torrents, search. */
object SourcesScreen : SettingsScreen("sources", R.string.settings_sources, R.drawable.ic_extension, R.string.settings_sources_desc) {

    override val activity get() = SettingsGroupActivity::class.java

    private val DNS_PROVIDERS = listOf(
        "None", "Cloudflare", "Google", "AdGuard", "Quad9", "AliDNS", "DNSPod", "360",
        "Quad101", "Mullvad", "Controld", "Njalla", "Shecan", "Libre",
    )

    private val torrentsOn = { PrefManager.getVal<Boolean>(PrefName.TorrentEnabled) }

    override fun sections() = listOf(
        Section(R.string.settings_section_extensions, listOf(
            repoRow(MediaType.ANIME, R.string.anime_add_repository, R.string.anime_add_repository_desc),
            repoRow(MediaType.MANGA, R.string.manga_add_repository, R.string.manga_add_repository_desc),
            repoRow(MediaType.NOVEL, R.string.novel_add_repository, R.string.novel_add_repository_desc),
            switchRow(PrefName.NSFWExtension, R.string.NSFWExtention, R.string.NSFWExtention_desc, R.drawable.ic_round_nsfw_24),
            switchRow(PrefName.SkipExtensionIcons, R.string.skip_loading_extension_icons,
                R.string.skip_loading_extension_icons_desc, R.drawable.ic_round_no_icon_24),
            choiceRow(PrefName.DohProvider, R.string.selected_dns, R.drawable.lan_24,
                labels = { DNS_PROVIDERS }, advanced = true,
                onChange = { host, _ -> host.activity.restartApp() }),
            actionRow("extension_installer", R.string.extension_installer, icon = R.drawable.ic_round_new_releases_24,
                advanced = true,
                current = { Injekt.get<BasePreferences>().extensionInstaller().get().titleResId },
                onClick = { host, _ -> pickInstaller(host) }),
            actionRow("extension_test", R.string.extension_test, R.string.extension_test_desc,
                R.drawable.ic_round_search_sources_24, opensScreen = true, advanced = true,
                onClick = { host, _ ->
                    host.activity.startActivity(Intent(host.activity, ParserTestActivity::class.java))
                }),
            actionRow("DefaultUserAgent", R.string.user_agent, R.string.user_agent_desc,
                R.drawable.ic_round_video_settings_24, advanced = true, keys = listOf(PrefName.DefaultUserAgent),
                onClick = { host, _ -> editUserAgent(host) }),
            switchRow(PrefName.EnableSocks5Proxy, R.string.proxy, R.string.proxy_desc,
                R.drawable.swap_horizontal_circle_24, advanced = true,
                onChange = { host, _ -> host.activity.restartApp() }),
            actionRow("proxy_setup", R.string.proxy_setup, R.string.proxy_setup_desc, R.drawable.lan_24,
                advanced = true,
                onClick = { host, _ -> ProxyDialogFragment().show(host.activity.supportFragmentManager, "dialog") }),
        )),
        Section(R.string.settings_section_torrents, listOf(
            switchRow(PrefName.TorrentEnabled, R.string.enable_torrent, R.string.enable_torrent_desc,
                R.drawable.ic_round_dns_24, refresh = true,
                onChange = { host, isChecked ->
                    host.activity.lifecycleScope.launchIO {
                        if (isChecked && !TorrentServerService.isRunning()) TorrentServerService.start()
                        if (!isChecked && TorrentServerService.isRunning()) TorrentServerService.stop()
                    }
                }),
            actionRow("play_magnet", R.string.play_magnet_or_torrent, R.string.play_magnet_or_torrent_desc,
                R.drawable.ic_round_play_arrow_24, visible = torrentsOn,
                onClick = { host, _ ->
                    ani.dantotsu.torrent.ManualTorrentBottomSheet.newInstance()
                        .show(host.activity.supportFragmentManager, "ManualTorrentBottomSheet")
                }),
            switchRow(PrefName.TorrentWifiOnly, R.string.torrent_wifi_only, R.string.torrent_wifi_only_desc,
                R.drawable.lan_24, visible = torrentsOn),
            switchRow(PrefName.TorrentEncryption, R.string.torrent_encryption, R.string.torrent_encryption_desc,
                R.drawable.ic_shield, visible = torrentsOn),
            switchRow(PrefName.TorrentBatterySaving, R.string.torrent_battery_saving,
                R.string.torrent_battery_saving_desc, R.drawable.ic_lightbulb_24, visible = torrentsOn),
            numberRow(PrefName.TorrentDownloadSpeedLimit, R.string.torrent_download_speed, R.drawable.ic_download_24) { c, v ->
                if (v > 0) c.getString(R.string.speed_kbps, v) else c.getString(R.string.disabled)
            },
            numberRow(PrefName.TorrentUploadSpeedLimit, R.string.torrent_upload_speed, R.drawable.ic_download_24) { c, v ->
                if (v > 0) c.getString(R.string.speed_kbps, v) else c.getString(R.string.disabled)
            },
            numberRow(PrefName.TorrentMaxConnections, R.string.torrent_max_connections, R.drawable.ic_round_dns_24) { _, v ->
                v.toString()
            },
            numberRow(PrefName.TorrentPort, R.string.torrent_port, R.drawable.network_node_24) { c, v ->
                if (v > 0) v.toString() else c.getString(R.string.disabled)
            },
            switchRow(PrefName.TorrentDisableUtp, R.string.torrent_disable_utp, R.string.torrent_disable_utp_desc,
                R.drawable.lan_24, advanced = true, visible = torrentsOn),
        )),
        Section(R.string.settings_section_search, listOf(
            switchRow(PrefName.SearchSources, R.string.search_source_list, R.string.search_source_list_desc,
                R.drawable.ic_round_search_sources_24),
        )),
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
        list.visibility = if (list.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun repoRow(type: MediaType, title: Int, summary: Int): Row = actionRow(
        id = repoPref(type).name, title = title, summary = summary, icon = R.drawable.ic_github,
        keys = listOf(repoPref(type)),
        extra = { _, list -> showRepos(list, type) },
        onClick = { host, row ->
            AddRepositoryBottomSheet.newInstance(
                type,
                PrefManager.getVal<Set<String>>(repoPref(type)).toList(),
                onRepositoryAdded = { input, mediaType ->
                    AddRepositoryBottomSheet.addRepo(input, mediaType)
                    showRepos(row.extra, mediaType)
                },
                onRepositoryRemoved = { item, mediaType ->
                    AddRepositoryBottomSheet.removeRepo(item, mediaType)
                    showRepos(row.extra, mediaType)
                }
            ).show(host.activity.supportFragmentManager, "add_repo")
        },
    )

    private fun pickInstaller(host: SettingsHost) = with(host.activity) {
        val installer = Injekt.get<BasePreferences>().extensionInstaller()
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

    private fun editUserAgent(host: SettingsHost) = with(host.activity) {
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

    /** Advanced torrent number, edited in a number dialog; [label] formats the value. */
    private fun numberRow(
        key: PrefName,
        title: Int,
        icon: Int,
        label: (android.content.Context, Int) -> String,
    ): Row = actionRow(
        id = key.name, title = title, icon = icon, advanced = true, visible = torrentsOn,
        keys = listOf(key),
        current = { label(it, PrefManager.getVal(key)) },
        onClick = { host, row -> editNumber(host, row, key, title, label) },
    )

    private fun editNumber(
        host: SettingsHost,
        row: RowView,
        key: PrefName,
        title: Int,
        label: (android.content.Context, Int) -> String,
    ) = with(host.activity) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefManager.getVal<Int>(key).toString())
            setSelection(text.length)
        }
        val margin = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            addView(input, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = margin; rightMargin = margin; topMargin = margin / 3; bottomMargin = margin / 3 })
        }
        customAlertDialog().apply {
            setTitle(getString(title))
            setCustomView(container)
            setPosButton(R.string.yes, onClick = {
                val value = input.text.toString().toIntOrNull() ?: 0
                PrefManager.setVal(key, value)
                row.setValue(label(this@with, value))
            })
            setNegButton(R.string.no)
            show()
        }
    }
}
