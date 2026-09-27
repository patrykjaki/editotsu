package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityTorrentSettingsBinding
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog

class TorrentSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTorrentSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivityTorrentSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsTorrentLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            torrentSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            val encryptionItem = Settings(
                type = 2,
                name = getString(R.string.torrent_encryption),
                desc = getString(R.string.torrent_encryption_desc),
                icon = R.drawable.ic_shield,
                isChecked = PrefManager.getVal(PrefName.TorrentEncryption),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.TorrentEncryption, isChecked)
                }
            )

            val wifiOnlyItem = Settings(
                type = 2,
                name = getString(R.string.torrent_wifi_only),
                desc = getString(R.string.torrent_wifi_only_desc),
                icon = R.drawable.lan_24,
                isChecked = PrefManager.getVal(PrefName.TorrentWifiOnly),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.TorrentWifiOnly, isChecked)
                }
            )

            var downloadLimit = PrefManager.getVal<Int>(PrefName.TorrentDownloadSpeedLimit)
            val downloadSpeedItem = Settings(
                type = 1,
                name = getString(R.string.torrent_download_speed),
                desc = if (downloadLimit > 0) "$downloadLimit KB/s" else getString(R.string.disabled),
                icon = R.drawable.ic_download_24,
                onClick = { view ->
                    showNumberInputDialog(
                        R.string.torrent_download_speed,
                        PrefName.TorrentDownloadSpeedLimit
                    ) { value ->
                        downloadLimit = value
                        view.settingsDesc.text = if (value > 0) "$value KB/s" else getString(R.string.disabled)
                    }
                }
            )

            var uploadLimit = PrefManager.getVal<Int>(PrefName.TorrentUploadSpeedLimit)
            val uploadSpeedItem = Settings(
                type = 1,
                name = getString(R.string.torrent_upload_speed),
                desc = if (uploadLimit > 0) "$uploadLimit KB/s" else getString(R.string.disabled),
                icon = R.drawable.ic_download_24,
                onClick = { view ->
                    showNumberInputDialog(
                        R.string.torrent_upload_speed,
                        PrefName.TorrentUploadSpeedLimit
                    ) { value ->
                        uploadLimit = value
                        view.settingsDesc.text = if (value > 0) "$value KB/s" else getString(R.string.disabled)
                    }
                }
            )

            var maxConn = PrefManager.getVal<Int>(PrefName.TorrentMaxConnections)
            val maxConnectionsItem = Settings(
                type = 1,
                name = getString(R.string.torrent_max_connections),
                desc = maxConn.toString(),
                icon = R.drawable.ic_round_dns_24,
                onClick = { view ->
                    showNumberInputDialog(
                        R.string.torrent_max_connections,
                        PrefName.TorrentMaxConnections
                    ) { value ->
                        maxConn = value
                        view.settingsDesc.text = value.toString()
                    }
                }
            )

            val batterySavingItem = Settings(
                type = 2,
                name = getString(R.string.torrent_battery_saving),
                desc = getString(R.string.torrent_battery_saving_desc),
                icon = R.drawable.ic_lightbulb_24,
                isChecked = PrefManager.getVal(PrefName.TorrentBatterySaving),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.TorrentBatterySaving, isChecked)
                }
            )

            var customPort = PrefManager.getVal<Int>(PrefName.TorrentPort)
            val portItem = Settings(
                type = 1,
                name = getString(R.string.torrent_port),
                desc = if (customPort > 0) customPort.toString() else getString(R.string.disabled),
                icon = R.drawable.network_node_24,
                onClick = { view ->
                    showNumberInputDialog(
                        R.string.torrent_port,
                        PrefName.TorrentPort
                    ) { value ->
                        customPort = value
                        view.settingsDesc.text = if (value > 0) value.toString() else getString(R.string.disabled)
                    }
                }
            )

            val disableUtpItem = Settings(
                type = 2,
                name = getString(R.string.torrent_disable_utp),
                desc = getString(R.string.torrent_disable_utp_desc),
                icon = R.drawable.lan_24,
                isChecked = PrefManager.getVal(PrefName.TorrentDisableUtp),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.TorrentDisableUtp, isChecked)
                }
            )

            // (quota + purge rows removed — canonical home is Data & Storage)

            // MAX MERGE M3: quota + purge moved to Data & Storage
            // (canonical storage home). This row navigates there; the usage
            // text is read-only, never an editable duplicate.
            val cacheManager = ani.dantotsu.torrent.TorrentCacheManager.getInstance(context)
            val torrentCacheNavItem = Settings(
                type = 1,
                name = getString(R.string.torrent_cache_manage),
                desc = getString(
                    R.string.torrent_cache_manage_desc,
                    formatSize(cacheManager.getStorageBreakdown().torrentCacheBytes)
                ),
                icon = R.drawable.ic_round_folder_24,
                onClick = {
                    startActivity(
                        Intent(
                            context,
                            ani.dantotsu.settings.data.SettingsDataActivity::class.java
                        )
                    )
                }
            )

            val highlightKey = intent.getStringExtra(ani.dantotsu.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    encryptionItem,
                    wifiOnlyItem,
                    downloadSpeedItem,
                    uploadSpeedItem,
                    maxConnectionsItem,
                    batterySavingItem,
                    portItem,
                    disableUtpItem,
                    torrentCacheNavItem
                ),
                highlightKey = highlightKey
            )

            settingsRecyclerView.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun showNumberInputDialog(
        titleRes: Int,
        prefName: PrefName,
        updateText: (Int) -> Unit
    ) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(PrefManager.getVal<Int>(prefName).toString())
            setSelection(text.length)
        }

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            leftMargin = margin
            rightMargin = margin
            topMargin = (8 * resources.displayMetrics.density).toInt()
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        }
        input.layoutParams = params
        container.addView(input)

        customAlertDialog().apply {
            setTitle(getString(titleRes))
            setCustomView(container)
            setPosButton(R.string.yes, onClick = {
                val value = input.text.toString().toIntOrNull() ?: 0
                PrefManager.setVal(prefName, value)
                updateText(value)
            })
            setNegButton(R.string.no)
            show()
        }
    }
}
