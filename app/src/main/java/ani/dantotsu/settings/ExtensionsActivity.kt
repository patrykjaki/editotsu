package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AutoCompleteTextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.others.setDropdownText
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.markSegment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    // Predictive back needs the callback disabled unless it will clear an active search query.
    private lateinit var clearSearchBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()
        initActivity(this)

        supportFragmentManager.addOnBackStackChangedListener {
            val hasPrefFragment = supportFragmentManager.findFragmentById(R.id.fragmentExtensionsContainer) != null
            if (!hasPrefFragment) showSourcePreferences(false, null)
        }

        val viewPager = binding.viewPager
        viewPager.offscreenPageLimit = 1
        // Type and status are chosen with the controls; swiping between six
        // pages would skip across types and fight the drag-to-reorder lists.
        viewPager.isUserInputEnabled = false

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = ExtensionPages.PAGE_COUNT

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> InstalledAnimeExtensionsFragment()
                    1 -> AnimeExtensionsFragment()
                    2 -> InstalledMangaExtensionsFragment()
                    3 -> MangaExtensionsFragment()
                    4 -> InstalledNovelExtensionsFragment()
                    5 -> NovelExtensionsFragment()
                    else -> AnimeExtensionsFragment()
                }
            }
        }

        val searchView: AutoCompleteTextView = binding.searchViewText

        // Primary tabs: Anime / Manga / Novel
        val tabs = binding.extensionsTypeTabs
        ExtensionPages.TYPES.forEach { type ->
            tabs.addTab(tabs.newTab().setText(typeLabel(type)), false)
        }

        val startPage = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        val startType = ExtensionPages.typeOf(startPage)
        val startInstalled = ExtensionPages.isInstalled(startPage)

        tabs.getTabAt(ExtensionPages.tabIndexOf(startType))?.select()
        binding.extensionsStatusToggle.check(
            if (startInstalled) R.id.extensionsStatusInstalled else R.id.extensionsStatusAvailable
        )
        listOf(binding.extensionsStatusInstalled, binding.extensionsStatusAvailable).forEach { button ->
            markSegment(button, null, button.isChecked)
        }

        fun selectPage() {
            val type = ExtensionPages.typeForTab(tabs.selectedTabPosition)
            val installed = binding.extensionsStatusToggle.checkedButtonId != R.id.extensionsStatusAvailable
            val page = ExtensionPages.pageOf(type, installed)
            if (viewPager.currentItem != page) {
                if (searchView.text.isNotEmpty()) searchView.setText("")
                searchView.clearFocus()
                viewPager.setCurrentItem(page, false)
            }
            searchView.hint = getString(searchHint(type))
            binding.extensionsLanguageBar.isVisible = !installed
            if (!installed) updateLanguageChip()
        }

        viewPager.setCurrentItem(startPage, false)
        searchView.hint = getString(searchHint(startType))
        binding.extensionsLanguageBar.isVisible = !startInstalled
        updateLanguageChip()

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectPage()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        binding.extensionsStatusToggle.addOnButtonCheckedListener { group, id, isChecked ->
            markSegment(group.findViewById(id), null, isChecked)
            if (isChecked) selectPage()
        }

        binding.extensionsLanguageChip.setOnClickListener {
            // The chip toggles itself on tap; keep its checked state tied to the active filter.
            updateLanguageChip()
            chooseLanguage()
        }

        binding.extensionsReposButton.setOnClickListener {
            openRepositories(ExtensionPages.typeOf(binding.viewPager.currentItem))
        }
        binding.extensionsMenuButton.setOnClickListener { showMenu(it) }

        clearSearchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                searchView.setText("")
                searchView.clearFocus()
            }
        }
        onBackPressedDispatcher.addCallback(this, clearSearchBackCallback)

        binding.searchClear.setOnClickListener {
            searchView.setText("")
        }

        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = Unit
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrEmpty()
                binding.searchClear.isVisible = hasText
                updateBackCallback()
                (currentPageFragment() as? SearchQueryHandler)
                    ?.updateContentBasedOnQuery(s?.toString()?.trim())
            }
        })

        binding.settingsContainer.setPadding(0, statusBarHeight, 0, navBarHeight)
        // Read real insets too (when this screen is opened directly or when the IME shows).
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                bars.left,
                if (bars.top > 0) bars.top else statusBarHeight,
                bars.right,
                maxOf(bars.bottom, ime.bottom, navBarHeight),
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.settingsContainer)

        binding.extensionsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        observeUpdateBadges()
    }

    private fun observeUpdateBadges() {
        val animeManager: AnimeExtensionManager = Injekt.get()
        val mangaManager: MangaExtensionManager = Injekt.get()
        val novelManager: NovelExtensionManager = Injekt.get()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    animeManager.installedExtensionsFlow.collect { list ->
                        updateTabBadge(MediaType.ANIME, list.count { it.hasUpdate })
                    }
                }
                launch {
                    mangaManager.installedExtensionsFlow.collect { list ->
                        updateTabBadge(MediaType.MANGA, list.count { it.hasUpdate })
                    }
                }
                launch {
                    novelManager.allInstalledExtensionsFlow.collect { list ->
                        updateTabBadge(MediaType.NOVEL, list.count(ExtensionUpdateAll::novelHasUpdate))
                    }
                }
            }
        }
    }

    private fun updateTabBadge(type: MediaType, count: Int) {
        val tab = binding.extensionsTypeTabs.getTabAt(ExtensionPages.tabIndexOf(type)) ?: return
        if (count > 0) {
            tab.orCreateBadge.apply {
                isVisible = true
                number = count
                maxCharacterCount = 3
                // Default anchoring puts the badge over the label's last letters; move it past the text.
                horizontalOffsetWithText = -(8 * resources.displayMetrics.density).toInt()
            }
        } else {
            tab.removeBadge()
        }
    }

    private fun updateBackCallback() {
        clearSearchBackCallback.isEnabled =
            !binding.searchViewText.text.isNullOrEmpty() && !binding.fragmentExtensionsContainer.isVisible
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, binding.viewPager.currentItem)
    }

    private fun currentPageFragment(): Fragment? =
        supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    private fun showMenu(anchor: View) {
        val page = binding.viewPager.currentItem
        val type = ExtensionPages.typeOf(page)
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_REFRESH, 0, R.string.extensions_menu_refresh)
            if (!ExtensionPages.isInstalled(page)) {
                menu.add(0, MENU_LANGUAGE, 1, R.string.extensions_menu_language)
            }
            menu.add(0, MENU_TEST, 2, R.string.extensions_menu_test)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_REFRESH -> ExtensionRefresh.refresh(this@ExtensionsActivity, this@ExtensionsActivity, type)
                    MENU_LANGUAGE -> chooseLanguage()
                    MENU_TEST -> ContextCompat.startActivity(
                        this@ExtensionsActivity,
                        Intent(this@ExtensionsActivity, ParserTestActivity::class.java),
                        null
                    )
                }
                true
            }
            show()
        }
    }

    private fun languageLabel(entry: LanguageMapper.Companion.Language): String =
        if (entry == LanguageMapper.Companion.Language.ALL) getString(R.string.extensions_language_all)
        else LanguageMapper.getLanguageName(entry.code)

    private fun updateLanguageChip() {
        val code: String = PrefManager.getVal(PrefName.LangSort)
        val entry = LanguageMapper.Companion.Language.entries.firstOrNull { it.code == code }
            ?: LanguageMapper.Companion.Language.ALL
        binding.extensionsLanguageChip.setDropdownText(languageLabel(entry))
        binding.extensionsLanguageChip.isChecked = entry != LanguageMapper.Companion.Language.ALL
    }

    private fun chooseLanguage() {
        val entries = LanguageMapper.Companion.Language.entries
        val options = entries.map { ChoiceOption(languageLabel(it)) }
        val current: String = PrefManager.getVal(PrefName.LangSort)
        val index = entries.indexOfFirst { it.code == current }.coerceAtLeast(0)
        ChoiceSheet.single(
            this, getString(R.string.extensions_menu_language), options, index,
        ) { selected ->
            PrefManager.setVal(PrefName.LangSort, entries[selected].code)
            updateLanguageChip()
            (currentPageFragment() as? SearchQueryHandler)?.notifyDataChanged()
        }
    }

    fun showAvailable() {
        binding.extensionsStatusToggle.check(R.id.extensionsStatusAvailable)
    }

    fun openRepositories(type: MediaType) {
        AddRepositoryBottomSheet.newInstance(
            type,
            ExtensionEmptyState.repositories(type).toList(),
            AddRepositoryBottomSheet::addRepo,
            AddRepositoryBottomSheet::removeRepo
        ).show(supportFragmentManager, "add_repo")
    }

    /**
     * Shows a source's preference screen in place of the lists (or restores
     * the lists). Called by the installed-extension fragments.
     */
    fun showSourcePreferences(show: Boolean, title: String?) {
        if (isFinishing || isDestroyed) return
        binding.viewPager.isVisible = !show
        binding.extensionsTypeTabs.isVisible = !show
        binding.extensionsTabsDivider.isVisible = !show
        binding.extensionsFilterBar.isVisible = !show
        binding.extensionsReposButton.isVisible = !show
        binding.extensionsMenuButton.isVisible = !show
        binding.fragmentExtensionsContainer.isGone = !show
        binding.extensions.text = if (show && title != null) title else getString(R.string.extensions)
        if (show) binding.searchViewText.clearFocus()
        updateBackCallback()
    }

    private fun typeLabel(type: MediaType): Int = when (type) {
        MediaType.ANIME -> R.string.extensions_type_anime
        MediaType.MANGA -> R.string.extensions_type_manga
        MediaType.NOVEL -> R.string.extensions_type_novel
    }

    private fun searchHint(type: MediaType): Int = when (type) {
        MediaType.ANIME -> R.string.extensions_search_anime
        MediaType.MANGA -> R.string.extensions_search_manga
        MediaType.NOVEL -> R.string.extensions_search_novel
    }

    private companion object {
        const val KEY_PAGE = "extensions_page"
        const val MENU_REFRESH = 1
        const val MENU_LANGUAGE = 2
        const val MENU_TEST = 3
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
