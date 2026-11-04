package ani.dantotsu.settings

import android.graphics.drawable.Animatable
import android.os.Build.BRAND
import android.os.Build.DEVICE
import android.os.Build.SUPPORTED_ABIS
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.RELEASE
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivitySettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.pop
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.rows.RowAdapter
import ani.dantotsu.settings.rows.ScreenItem
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.screens.SettingsScreens
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random


class SettingsActivity : AppCompatActivity(), SettingsHost {
    override val activity: AppCompatActivity get() = this

    /** The home list has no state of its own to re-read. */
    override fun refresh() {}

    lateinit var binding: ActivitySettingsBinding
    private var cursedCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val context = this
        binding.apply {
            settingsVersion.apply {
                text = getString(R.string.version_current, BuildConfig.VERSION_NAME)

                setOnLongClickListener {
                    copyToClipboard(getDeviceInfo(), false)
                    toast(getString(R.string.copied_device_info))
                    return@setOnLongClickListener true
                }

                // MAX MERGE M3 (upstream e1476cc7 badge, own pipeline):
                // read-only outdated indicator. Quiet on failure, no
                // pipeline mutation, no new cache, fdroid always null.
                lifecycleScope.launch(Dispatchers.IO) {
                    val latest = AppUpdater.latestVersionIfNewer(this@SettingsActivity)
                    if (latest != null) {
                        withContext(Dispatchers.Main) {
                            if (isDestroyed) return@withContext
                            text = getString(R.string.version_current_outdated, BuildConfig.VERSION_NAME)
                            setOnClickListener {
                                openLinkInBrowser("https://github.com/${getString(R.string.repo)}/releases/latest")
                            }
                        }
                    }
                }
            }
            settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            backCallback = onBackPressedDispatcher.addCallback(context) {
                if (settingsSearchBarText.text?.isNotBlank() == true) {
                    settingsSearchBarText.setText("")
                } else if (PrefManager.getCustomVal("reload", false)) {
                    startMainActivity(context)
                    PrefManager.setCustomVal("reload", false)
                } else {
                    finish()
                }
            }

            settingsBack.setOnClickListener {
                if (settingsSearchBarText.text?.isNotBlank() == true) {
                    settingsSearchBarText.setText("")
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
            }

            // One row per screen, from the same list search indexes.
            val mainAdapter = RowAdapter(this@SettingsActivity) {}
            mainAdapter.submitList(SettingsScreens.all.map { screen ->
                ScreenItem.RowItem(
                    actionRow(screen.id, screen.title, screen.summary, screen.icon, opensScreen = true) { host, _ ->
                        host.activity.startActivity(SettingsScreenActivity.intent(host.activity, screen))
                    }
                )
            })

            val searchAdapter = ani.dantotsu.settings.search.SettingsSearchAdapter(emptyList())

            settingsRecyclerView.apply {
                adapter = mainAdapter
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }

            settingsSearchBarText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateBackCallback()
                    val query = s?.toString()?.trim() ?: ""
                    if (query.isNotEmpty()) {
                        val results = ani.dantotsu.settings.rows.SettingsIndex.search(context, query).map {
                            ani.dantotsu.settings.search.SearchableSetting(
                                title = it.title,
                                desc = it.summary,
                                icon = it.icon,
                                breadcrumbs = it.breadcrumbs,
                                targetActivity = it.entry.screen.activity,
                                rowId = it.entry.row.id,
                            )
                        }
                        if (results.isNotEmpty()) {
                            searchAdapter.updateResults(results)
                            settingsRecyclerView.adapter = searchAdapter
                            settingsRecyclerView.visibility = View.VISIBLE
                            settingsEmptyLayout.visibility = View.GONE
                        } else {
                            settingsRecyclerView.visibility = View.GONE
                            settingsEmptyLayout.visibility = View.VISIBLE
                        }
                        settingsFooterLayout.visibility = View.GONE
                        settingsSearchBar.setEndIconDrawable(R.drawable.ic_round_close_24)
                        settingsSearchBar.setEndIconOnClickListener {
                            settingsSearchBarText.setText("")
                        }
                    } else {
                        settingsRecyclerView.adapter = mainAdapter
                        settingsRecyclerView.visibility = View.VISIBLE
                        settingsEmptyLayout.visibility = View.GONE
                        settingsFooterLayout.visibility = View.VISIBLE
                        settingsSearchBar.setEndIconDrawable(R.drawable.ic_round_search_24)
                        settingsSearchBar.setEndIconOnClickListener(null)
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            if (!BuildConfig.FLAVOR.contains("fdroid")) {
                settingsLogo.setOnLongClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppUpdater.check(this@SettingsActivity, true)
                    }
                    true
                }
            }

            settingBuyMeCoffee.setOnClickListener {
                lifecycleScope.launch {
                    it.pop()
                }
                openLinkInBrowser(getString(R.string.coffee))
            }
            lifecycleScope.launch {
                settingBuyMeCoffee.pop()
            }

            loginGithub.setOnClickListener {
                openLinkInBrowser(getString(R.string.github))
            }


            (settingsLogo.drawable as Animatable).start()
            val array = resources.getStringArray(R.array.tips)

            settingsLogo.setSafeOnClickListener {
                cursedCounter++
                (settingsLogo.drawable as Animatable).start()
                if (cursedCounter % 16 == 0) {
                    val oldVal: Boolean = PrefManager.getVal(PrefName.OC)
                    if (!oldVal) {
                        toast(R.string.omega_cursed)
                    } else {
                        toast(R.string.omega_freed)
                    }
                    PrefManager.setVal(PrefName.OC, !oldVal)
                } else {
                    snackString(array[(Math.random() * array.size).toInt()], context)
                }

            }

            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)
                runOnUiThread {
                    if (Random.nextInt(0, 100) > 69) {
                        CustomBottomDialog.newInstance().apply {
                            title = this@SettingsActivity.getString(R.string.enjoying_app)
                            addView(TextView(this@SettingsActivity).apply {
                                text = context.getString(R.string.consider_donating)
                            })

                            setNegativeButton(this@SettingsActivity.getString(R.string.no_moners)) {
                                snackString(R.string.you_be_rich)
                                dismiss()
                            }

                            setPositiveButton(this@SettingsActivity.getString(R.string.donate)) {
                                settingBuyMeCoffee.performClick()
                                dismiss()
                            }
                            show(supportFragmentManager, "dialog")
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun getDeviceInfo(): String {
            return """
                dantotsu Version: ${BuildConfig.VERSION_NAME}
                Device: $BRAND $DEVICE
                Architecture: ${getArch()}
                OS Version: $CODENAME $RELEASE ($SDK_INT)
            """.trimIndent()
        }

        private fun getArch(): String {
            SUPPORTED_ABIS.forEach {
                when (it) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return System.getProperty("os.arch") ?: System.getProperty("os.product.cpu.abi")
            ?: "Unknown Architecture"
        }
    }

    override fun onResume() {
        ThemeManager(this).applyTheme()
        super.onResume()
        updateBackCallback()
    }

    // Predictive back needs the callback off when back would just close the screen.
    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    private fun updateBackCallback() {
        backCallback?.isEnabled = binding.settingsSearchBarText.text?.isNotBlank() == true ||
            PrefManager.getCustomVal("reload", false)
    }
}
