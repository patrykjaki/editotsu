package ani.dantotsu.settings

import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.DialogSetPasswordBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.initActivity
import ani.dantotsu.others.calc.BiometricPromptUtils
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/**
 * Rows that used to live on the "Common" screen, now placed in the groups they belong to
 * (Data & storage, Appearance, Sources). Behaviour and prefs are unchanged.
 */
@OptIn(DelicateCoroutinesApi::class)
object CommonSettingsRows {

    private val DNS_PROVIDERS = listOf(
        "None", "Cloudflare", "Google", "AdGuard", "Quad9", "AliDNS", "DNSPod", "360",
        "Quad101", "Mullvad", "Controld", "Njalla", "Shecan", "Libre",
    )

    fun dns(activity: AppCompatActivity) = with(activity) {
        Settings(
            type = SettingsRowType.CLICK,
            name = getString(R.string.selected_dns),
            desc = DNS_PROVIDERS.getOrElse(PrefManager.getVal(PrefName.DohProvider)) { DNS_PROVIDERS[0] },
            icon = R.drawable.lan_24,
            onClick = { row ->
                customAlertDialog().apply {
                    setTitle(R.string.selected_dns)
                    singleChoiceItems(
                        DNS_PROVIDERS.toTypedArray(),
                        PrefManager.getVal<Int>(PrefName.DohProvider)
                    ) { i ->
                        PrefManager.setVal(PrefName.DohProvider, i)
                        row.settingsDesc.text = DNS_PROVIDERS[i]
                        restartApp()
                    }
                    show()
                }
            }
        )
    }

    fun searchSourceList(activity: AppCompatActivity) = listOf(
        with(activity) {
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.search_source_list),
                desc = getString(R.string.search_source_list_desc),
                icon = R.drawable.ic_round_search_sources_24,
                isChecked = PrefManager.getVal(PrefName.SearchSources),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.SearchSources, isChecked) },
            )
        }
    )

    /** Appearance > General: start tab and what lists show. */
    fun general(activity: AppCompatActivity): List<Settings> = with(activity) {
        val tabs = arrayOf(getString(R.string.anime), getString(R.string.home), getString(R.string.manga))
        listOf(
            Settings(
                type = SettingsRowType.CLICK,
                name = getString(R.string.startUpTab),
                desc = tabs.getOrElse(PrefManager.getVal(PrefName.DefaultStartUpTab)) { tabs[1] },
                icon = R.drawable.ic_round_home_24,
                onClick = { row ->
                    customAlertDialog().apply {
                        setTitle(R.string.startUpTab)
                        singleChoiceItems(tabs, PrefManager.getVal<Int>(PrefName.DefaultStartUpTab)) { i ->
                            PrefManager.setVal(PrefName.DefaultStartUpTab, i)
                            row.settingsDesc.text = tabs[i]
                            initActivity(this@with)
                        }
                        show()
                    }
                }
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.always_continue_content),
                desc = getString(R.string.always_continue_content_desc),
                icon = R.drawable.ic_round_play_arrow_24,
                isChecked = PrefManager.getVal(PrefName.ContinueMedia),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.ContinueMedia, isChecked) },
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.recentlyListOnly),
                desc = getString(R.string.recentlyListOnly_desc),
                icon = R.drawable.ic_round_new_releases_24,
                isChecked = PrefManager.getVal(PrefName.RecentlyListOnly),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.RecentlyListOnly, isChecked) },
            ),
        )
    }

    /** Data & storage > Downloads. */
    fun downloads(activity: AppCompatActivity): List<Settings> = with(activity) {
        listOf(
            Settings(
                type = SettingsRowType.CLICK,
                name = getString(R.string.download_manager_select),
                desc = getString(R.string.download_manager_select_desc),
                icon = R.drawable.ic_download_24,
                onClick = {
                    val managers = arrayOf("Default", "1DM", "ADM")
                    customAlertDialog().apply {
                        setTitle(getString(R.string.download_manager))
                        singleChoiceItems(managers, PrefManager.getVal(PrefName.DownloadManager)) { count ->
                            PrefManager.setVal(PrefName.DownloadManager, count)
                        }
                        show()
                    }
                },
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.download_wifi_only),
                desc = getString(R.string.download_wifi_only_desc),
                icon = R.drawable.lan_24,
                isChecked = PrefManager.getVal(PrefName.DownloadWifiOnly),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.DownloadWifiOnly, isChecked) }
            ),
            Settings(
                type = SettingsRowType.CLICK,
                name = "Max Parallel Downloads",
                desc = "Concurrent download tasks (0 = Sequential, 1-10 = Concurrent). Note: High concurrency may trigger source rate limits.",
                icon = R.drawable.ic_download_24,
                onClick = {
                    val options = arrayOf("0 (Off / Sequential)", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
                    val current = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
                    customAlertDialog().apply {
                        setTitle("Max Parallel Downloads")
                        singleChoiceItems(options, current) { which ->
                            PrefManager.setVal(PrefName.MaxParallelDownloads, which)
                        }
                        show()
                    }
                },
            ),
        )
    }

    /** Data & storage > Advanced. */
    fun maintenance(activity: AppCompatActivity): List<Settings> = with(activity) {
        listOf(
            Settings(
                type = SettingsRowType.CLICK,
                name = getString(R.string.rebuild_download_index),
                desc = getString(R.string.rebuild_download_index_desc),
                icon = R.drawable.ic_download_24,
                onClick = {
                    customAlertDialog().apply {
                        setTitle(R.string.rebuild_download_index)
                        setMessage(R.string.rebuild_download_index_msg)
                        setPosButton(R.string.ok) {
                            toast(getString(R.string.please_wait))
                            GlobalScope.launch(Dispatchers.IO) {
                                val recovered = Injekt.get<DownloadsManager>().rebuildIndexFromDisk()
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.rebuild_download_index_done, recovered))
                                }
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                },
            ),
            Settings(
                type = SettingsRowType.CLICK,
                name = getString(R.string.clear_app_cache),
                desc = getString(R.string.clear_app_cache_desc),
                icon = R.drawable.ic_round_delete_24,
                onClick = {
                    customAlertDialog().apply {
                        setTitle(getString(R.string.clear_app_cache))
                        setMessage("Clear temporary cache, dormant torrents, and subtitle files? Active playback streams will be preserved.")
                        setPosButton(R.string.yes) {
                            GlobalScope.launch(Dispatchers.IO) {
                                val cacheMgr = ani.dantotsu.torrent.TorrentCacheManager.getInstance(this@with)
                                val torrentResult = cacheMgr.clearTorrentCache()
                                val transientBytes = cacheMgr.cleanTransientFiles(0L)
                                val totalFreed = torrentResult.bytesFreed + transientBytes
                                withContext(Dispatchers.Main) {
                                    val units = arrayOf("B", "KB", "MB", "GB", "TB")
                                    val digitGroups = if (totalFreed > 0) (Math.log10(totalFreed.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1) else 0
                                    val formatted = if (totalFreed > 0) String.format(java.util.Locale.US, "%.2f %s", totalFreed / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups]) else "0 B"
                                    toast("Cleared app cache! ($formatted freed)")
                                }
                            }
                        }
                        setNegButton(R.string.no)
                        show()
                    }
                },
            ),
        )
    }

    /** Data & storage > Privacy: app lock, private entries, adult content. */
    fun privacy(activity: AppCompatActivity): List<Settings> = with(activity) {
        listOf(
            Settings(
                type = SettingsRowType.CLICK,
                name = getString(R.string.app_lock),
                desc = getString(R.string.app_lock_desc),
                icon = R.drawable.ic_round_lock_open_24,
                onClick = { showAppLockDialog(this) },
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.hide_private),
                desc = getString(R.string.hide_private_desc),
                icon = R.drawable.ic_round_remove_red_eye_24,
                isChecked = PrefManager.getVal(PrefName.HidePrivate),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.HidePrivate, isChecked)
                    restartApp()
                },
            ),
            Settings(
                type = SettingsRowType.SWITCH,
                name = getString(R.string.adult_only_content),
                desc = getString(R.string.adult_only_content_desc),
                icon = R.drawable.ic_round_nsfw_24,
                isChecked = PrefManager.getVal(PrefName.AdultOnly),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.AdultOnly, isChecked)
                    restartApp()
                },
                isVisible = Anilist.adult,
            ),
        )
    }

    private fun showAppLockDialog(activity: AppCompatActivity) = with(activity) {
        customAlertDialog().apply {
            val view = DialogSetPasswordBinding.inflate(layoutInflater)
            setTitle(R.string.app_lock)
            setCustomView(view.root)
            setPosButton(R.string.ok) {
                if (view.forgotPasswordCheckbox.isChecked) {
                    PrefManager.setVal(PrefName.OverridePassword, true)
                }
                val password = view.passwordInput.text.toString()
                val confirmPassword = view.confirmPasswordInput.text.toString()
                if (password == confirmPassword && password.isNotEmpty()) {
                    PrefManager.setVal(PrefName.AppPassword, password)
                    if (view.biometricCheckbox.isChecked) {
                        val canBiometricPrompt = BiometricManager.from(applicationContext)
                            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                            BiometricManager.BIOMETRIC_SUCCESS
                        if (canBiometricPrompt) {
                            val biometricPrompt = BiometricPromptUtils.createBiometricPrompt(activity) { _ ->
                                PrefManager.setVal(PrefName.BiometricToken, UUID.randomUUID().toString())
                                toast(R.string.success)
                            }
                            biometricPrompt.authenticate(BiometricPromptUtils.createPromptInfo(activity))
                        }
                    } else {
                        PrefManager.setVal(PrefName.BiometricToken, "")
                        toast(R.string.success)
                    }
                } else {
                    toast(R.string.password_mismatch)
                }
            }
            setNegButton(R.string.cancel)
            setNeutralButton(R.string.remove) {
                PrefManager.setVal(PrefName.AppPassword, "")
                PrefManager.setVal(PrefName.BiometricToken, "")
                PrefManager.setVal(PrefName.OverridePassword, false)
                toast(R.string.success)
            }
            setOnShowListener {
                view.passwordInput.requestFocus()
                val canAuthenticate = BiometricManager.from(applicationContext).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK,
                ) == BiometricManager.BIOMETRIC_SUCCESS
                view.biometricCheckbox.isVisible = canAuthenticate
                view.biometricCheckbox.isChecked = PrefManager.getVal(PrefName.BiometricToken, "").isNotEmpty()
                view.forgotPasswordCheckbox.isChecked = PrefManager.getVal(PrefName.OverridePassword)
            }
            show()
        }
    }
}
