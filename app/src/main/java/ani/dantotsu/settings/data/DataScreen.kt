package ani.dantotsu.settings.data

import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.DialogSetPasswordBinding
import ani.dantotsu.databinding.ItemSettingStorageBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.novel.NovelDownloaderService
import ani.dantotsu.media.MediaType
import ani.dantotsu.others.calc.BiometricPromptUtils
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.rows.stepperRow
import ani.dantotsu.settings.rows.stepsRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.torrent.TorrentCacheManager
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Settings > Data & storage: storage, downloads, backups, privacy. */
@OptIn(DelicateCoroutinesApi::class)
object DataScreen : SettingsScreen("data", R.string.label_data_storage, R.drawable.ic_round_folder_24, R.string.settings_data_desc) {

    override val activity get() = SettingsDataActivity::class.java

    private val SettingsHost.data get() = activity as SettingsDataActivity

    private const val GB = 1024L * 1024L * 1024L
    private val QUOTAS = longArrayOf(1 * GB, 2 * GB, 3 * GB, 5 * GB, 10 * GB, 0L)
    private val COPY_COUNTS = intArrayOf(1, 2, 3, 5)

    private fun quotaLabels(c: android.content.Context) =
        listOf("1 GB", "2 GB", "3 GB", "5 GB", "10 GB", c.getString(R.string.torrent_quota_unlimited))

    private fun quotaIndex() =
        QUOTAS.indexOf(PrefManager.getVal<Long>(PrefName.TorrentRetainedCacheQuota)).let { if (it >= 0) it else 2 }

    private fun intervalLabels(c: android.content.Context) = listOf(
        c.getString(R.string.auto_backup_interval_off),
        c.getString(R.string.auto_backup_interval_6h),
        c.getString(R.string.auto_backup_interval_12h),
        c.getString(R.string.auto_backup_interval_24h),
        c.getString(R.string.auto_backup_interval_48h),
        c.getString(R.string.auto_backup_interval_weekly),
    )

    override fun sections() = listOf(
        Section(R.string.settings_section_storage, listOf(
            customRow("storage_usage", R.string.storage_usage, R.layout.item_setting_storage, inSection = true) { host, view ->
                host.data.bindStorage(ItemSettingStorageBinding.bind(view))
            },
            actionRow("clear_cache", R.string.clear_cache, icon = R.drawable.ic_round_delete_24,
                current = { c ->
                    val size = (c as SettingsDataActivity).cacheSize
                    c.getString(R.string.clear_cache_desc,
                        if (size != null && size > 0) formatStorageSize(size) else c.getString(R.string.cache_calculating))
                },
                onClick = { host, _ ->
                    host.activity.customAlertDialog().apply {
                        setTitle(R.string.clear_cache)
                        setMessage(R.string.clear_cache)
                        setPosButton(R.string.ok) { host.data.clearAppCache() }
                        setNegButton(R.string.cancel)
                        show()
                    }
                }),
            actionRow("purge_downloads", R.string.purge_downloads, R.string.purge_downloads_desc,
                R.drawable.ic_round_delete_24, onClick = { host, _ -> purgeAll(host) }),
            // Stored in bytes; the stepper walks QUOTAS (the last one is unlimited).
            stepsRow(
                "TorrentRetainedCacheQuota", R.string.torrent_cache_quota, QUOTAS.size,
                label = { c, i -> quotaLabels(c)[i] }, get = ::quotaIndex,
                icon = R.drawable.ic_round_folder_24, keys = listOf(PrefName.TorrentRetainedCacheQuota),
            ) { host, index ->
                val chosen = QUOTAS[index]
                PrefManager.setVal(PrefName.TorrentRetainedCacheQuota, chosen)
                val cacheMgr = TorrentCacheManager.getInstance(host.activity)
                host.activity.lifecycleScope.launch(Dispatchers.IO) { cacheMgr.evictIfNeeded(chosen) }
            },
            actionRow("clear_torrent_cache", R.string.clear_torrent_cache, icon = R.drawable.ic_round_delete_24,
                current = { (it as SettingsDataActivity).torrentCacheUsageText() },
                onClick = { host, row -> clearTorrentCache(host, row) }),
            actionRow("rebuild_download_index", R.string.rebuild_download_index, R.string.rebuild_download_index_desc,
                R.drawable.ic_download_24, advanced = true, onClick = { host, _ -> rebuildIndex(host) }),
            actionRow("clear_app_cache", R.string.clear_app_cache, R.string.clear_app_cache_desc,
                R.drawable.ic_round_delete_24, advanced = true, onClick = { host, _ -> clearAll(host) }),
        )),
        Section(R.string.downloads, listOf(
            actionRow("DownloadsDir", R.string.change_download_directory, icon = R.drawable.ic_round_folder_24,
                keys = listOf(PrefName.DownloadsDir),
                current = { (it as SettingsDataActivity).downloadsPath() },
                onClick = { host, _ -> changeDownloadDir(host) }),
            choiceRow(PrefName.DownloadManager, R.string.download_manager_select, R.drawable.ic_download_24,
                labels = { listOf("Default", "1DM", "ADM") },
                details = { c -> listOf(null, c.getString(R.string.download_manager_external_desc, "1DM"),
                    c.getString(R.string.download_manager_external_desc, "ADM")) }),
            switchRow(PrefName.DownloadWifiOnly, R.string.download_wifi_only, icon = R.drawable.lan_24),
            // A stored 0 (the old "off") downloads one at a time, the same as 1.
            stepperRow(PrefName.MaxParallelDownloads, R.string.max_parallel_downloads, R.string.max_parallel_downloads_desc,
                R.drawable.ic_download_24, from = 1f, to = 10f, step = 1f,
                format = { _, v -> v.toInt().toString() }, read = { it.coerceAtLeast(1f) }),
            purgeRow("purge_anime", R.string.purge_anime_downloads, R.string.anime) { host ->
                Injekt.get<DownloadsManager>().purgeDownloads(MediaType.ANIME)
            },
            purgeRow("purge_manga", R.string.purge_manga_downloads, R.string.manga) { host ->
                // Purge-all goes through the service scope barrier: every live manga owner is
                // cancelled and joined before deletion. The service reports the outcome.
                ContextCompat.startForegroundService(host.activity,
                    Intent(host.activity, MangaDownloaderService::class.java).apply {
                        action = MangaDownloaderService.ACTION_PURGE_MANGA
                    })
            },
            purgeRow("purge_novel", R.string.purge_novel_downloads, R.string.novels) { host ->
                Injekt.get<DownloadsManager>().purgeDownloads(MediaType.NOVEL)
            },
        )),
        Section(R.string.settings_section_backups, listOf(
            actionRow("backup_now", R.string.backup_now, R.string.backup_now_desc, R.drawable.backup_restore,
                keywords = listOf(R.string.export_settings_keyword),
                onClick = { host, _ ->
                    BackupRestore.chooseSections(host.activity) { sections, password ->
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                        val payload = BackupRestore.create(sections, password)
                        password?.fill('0')
                        val ext = if (password != null) "sani" else "ani"
                        host.data.saveBackup(payload, "Editotsu_${stamp}.$ext")
                    }
                }),
            actionRow("restore_backup", R.string.restore_backup, R.string.restore_backup_desc, R.drawable.backup_restore,
                keywords = listOf(R.string.import_settings_keyword),
                onClick = { host, _ -> host.data.openBackup.launch(arrayOf("*/*")) }),
            choiceRow(PrefName.AutoBackupInterval, R.string.auto_backup_interval, R.drawable.ic_round_auto_awesome_24,
                labels = ::intervalLabels, refresh = true,
                onChange = { host, which -> AutoBackupWorker.schedule(host.activity, which) }),
            // A count: stepper like the other numbers on this screen.
            stepsRow(
                "AutoBackupMaxCopies", R.string.auto_backup_max_copies, COPY_COUNTS.size,
                label = { _, i -> COPY_COUNTS[i].toString() },
                get = { COPY_COUNTS.indexOf(PrefManager.getVal<Int>(PrefName.AutoBackupMaxCopies)).let { if (it < 0) 2 else it } },
                icon = R.drawable.backup_restore, keys = listOf(PrefName.AutoBackupMaxCopies),
            ) { _, which -> PrefManager.setVal(PrefName.AutoBackupMaxCopies, COPY_COUNTS[which]) },
            actionRow("auto_backup", R.string.auto_backup, icon = R.drawable.backup_restore,
                current = { c ->
                    val timestamp = PrefManager.getVal<Long>(PrefName.LastAutoBackupTimestamp)
                    if (timestamp > 0) {
                        c.getString(R.string.last_auto_backup,
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp)))
                    } else {
                        c.getString(R.string.last_auto_backup_never)
                    }
                },
                // Shows when the last automatic backup ran; the interval row above sets it up.
                onClick = null),
        )),
        Section(R.string.settings_section_privacy, listOf(
            actionRow("app_lock", R.string.app_lock, R.string.app_lock_desc, R.drawable.ic_round_lock_open_24,
                keys = listOf(PrefName.AppPassword, PrefName.BiometricToken, PrefName.OverridePassword),
                onClick = { host, _ -> showAppLockDialog(host) }),
            switchRow(PrefName.HidePrivate, R.string.hide_private, R.string.hide_private_desc,
                R.drawable.ic_round_remove_red_eye_24, onChange = { host, _ -> host.activity.restartApp() }),
            switchRow(PrefName.AdultOnly, R.string.adult_only_content, R.string.adult_only_content_desc,
                R.drawable.ic_round_nsfw_24, visible = { Anilist.adult },
                onChange = { host, _ -> host.activity.restartApp() }),
        )),
    )

    private fun purgeRow(id: String, title: Int, type: Int, purge: (SettingsHost) -> Unit) =
        actionRow(id, title, icon = R.drawable.ic_round_delete_24, advanced = true, onClick = { host, _ ->
            host.activity.customAlertDialog().apply {
                setTitle(title)
                setMessage(R.string.purge_confirm, host.activity.getString(type))
                setPosButton(R.string.yes) { purge(host) }
                setNegButton(R.string.no)
                show()
            }
        })

    private fun purgeAll(host: SettingsHost) = with(host.activity) {
        customAlertDialog().apply {
            setTitle(R.string.purge_downloads)
            setMessage(
                R.string.purge_confirm,
                "${getString(R.string.anime)}, ${getString(R.string.manga)} & ${getString(R.string.novels)}"
            )
            setPosButton(R.string.yes) {
                // The purge runs inside the services (cancel, join, purge, report); this
                // screen never deletes directories itself. Completion refreshes it.
                ContextCompat.startForegroundService(this@with,
                    Intent(this@with, AnimeDownloaderService::class.java).apply {
                        action = AnimeDownloaderService.ACTION_PURGE_ALL_ANIME
                    })
                ContextCompat.startForegroundService(this@with,
                    Intent(this@with, NovelDownloaderService::class.java).apply {
                        action = NovelDownloaderService.ACTION_PURGE_ALL_NOVEL
                    })
                ContextCompat.startForegroundService(this@with,
                    Intent(this@with, MangaDownloaderService::class.java).apply {
                        action = MangaDownloaderService.ACTION_PURGE_MANGA
                    })
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun clearTorrentCache(host: SettingsHost, row: ani.dantotsu.settings.rows.RowView) = with(host.data) {
        customAlertDialog().apply {
            setTitle(getString(R.string.clear_torrent_cache))
            setMessage(getString(R.string.clear_torrent_cache_confirm))
            setPosButton(R.string.yes) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = TorrentCacheManager.getInstance(this@with).clearTorrentCache()
                    val updated = torrentCacheUsageText()
                    withContext(Dispatchers.Main) {
                        row.setValue(updated)
                        toast(
                            if (result.activeBytesSkipped > 0) getString(R.string.torrent_cache_freed_skipped,
                                formatStorageSize(result.bytesFreed), formatStorageSize(result.activeBytesSkipped))
                            else getString(R.string.torrent_cache_freed, formatStorageSize(result.bytesFreed))
                        )
                    }
                }
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun changeDownloadDir(host: SettingsHost): Unit = with(host.data) {
        val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
        if (DownloadsManager.isAnyDownloadActive()) {
            toast(getString(R.string.download_dir_busy))
            return@with
        }
        customAlertDialog().apply {
            setTitle(R.string.change_download_directory)
            setMessage(R.string.change_download_directory_confirm)
            setPosButton(R.string.ok) {
                folderPicker.registerForCallback { success ->
                    if (success) {
                        toast(getString(R.string.please_wait))
                        val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                        lifecycleScope.launch(Dispatchers.IO) {
                            Injekt.get<DownloadsManager>().moveDownloadsDir(
                                this@with, Uri.parse(oldUri), Uri.parse(newUri),
                            ) { finished, message ->
                                if (finished) {
                                    toast(getString(R.string.success))
                                    runOnUiThread { refresh() }
                                } else {
                                    // The picker already saved the new folder; the
                                    // downloads are still in the old one.
                                    if (newUri != oldUri) {
                                        PrefManager.setVal(PrefName.DownloadsDir, oldUri)
                                    }
                                    toast(getString(R.string.download_dir_not_changed, message))
                                    runOnUiThread { refresh() }
                                }
                            }
                        }
                    } else {
                        toast(getString(R.string.error))
                    }
                }
                folderPicker.launch()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun rebuildIndex(host: SettingsHost) = with(host.activity) {
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
    }

    private fun clearAll(host: SettingsHost) = with(host.activity) {
        customAlertDialog().apply {
            setTitle(getString(R.string.clear_app_cache))
            setMessage(getString(R.string.clear_app_cache_confirm))
            setPosButton(R.string.yes) {
                GlobalScope.launch(Dispatchers.IO) {
                    val cacheMgr = TorrentCacheManager.getInstance(this@with)
                    val torrentResult = cacheMgr.clearTorrentCache()
                    val transientBytes = cacheMgr.cleanTransientFiles(0L)
                    val totalFreed = torrentResult.bytesFreed + transientBytes
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.clear_app_cache_done, formatStorageSize(totalFreed)))
                    }
                }
            }
            setNegButton(R.string.no)
            show()
        }
    }

    private fun showAppLockDialog(host: SettingsHost) = with(host.activity) {
        customAlertDialog().apply {
            val view = DialogSetPasswordBinding.inflate(layoutInflater)
            setTitle(R.string.app_lock)
            setCustomView(view.root)
            setPosButton(R.string.ok) {
                val password = view.passwordInput.text.toString()
                val confirmPassword = view.confirmPasswordInput.text.toString()
                if (password == confirmPassword && password.isNotEmpty()) {
                    PrefManager.setVal(PrefName.AppPassword, password)
                    PrefManager.setVal(PrefName.OverridePassword, view.forgotPasswordCheckbox.isChecked)
                    if (view.biometricCheckbox.isChecked) {
                        val canBiometricPrompt = BiometricManager.from(applicationContext)
                            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                            BiometricManager.BIOMETRIC_SUCCESS
                        if (canBiometricPrompt) {
                            val biometricPrompt = BiometricPromptUtils.createBiometricPrompt(this@with) { _ ->
                                PrefManager.setVal(PrefName.BiometricToken, UUID.randomUUID().toString())
                                toast(R.string.success)
                            }
                            biometricPrompt.authenticate(BiometricPromptUtils.createPromptInfo(this@with))
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
