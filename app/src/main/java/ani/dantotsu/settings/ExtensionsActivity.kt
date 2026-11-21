package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.viewpager2.widget.ViewPager2
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
import kotlin.math.abs

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    // Predictive back needs the callback disabled unless it will clear an active search query.
    private lateinit var clearSearchBackCallback: OnBackPressedCallback
    private var currentType: MediaType = MediaType.ANIME

    private var swipeAxis = ExtensionPages.SwipeAxis.NONE
    private var swipePointerId = MotionEvent.INVALID_POINTER_ID
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var reorderDragging = false
    private var verticalSwipeLocked = false
    private val swipeTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

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

        val startPage = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        currentType = ExtensionPages.typeOf(startPage)
        val startInstalled = ExtensionPages.isInstalled(startPage)
        val startStatus = ExtensionPages.statusIndexOf(startInstalled)

        // Single 2-page pager scoped to the active MediaType (Installed / Available) using
        // stable 0..5 page ids. Chosen over: (a) 6-page boundary clamping, which visibly leaks
        // the next type before snapping back; (b) nested 2-page pagers, which break Installed*
        // fragments' parentFragmentManager transaction onto R.id.fragmentExtensionsContainer;
        // (c) a GestureDetector fling, which lacks live finger-tracking and fights list touches.
        val viewPager = binding.viewPager
        val pagerAdapter = StatusPagerAdapter(this, currentType)
        viewPager.offscreenPageLimit = 1
        viewPager.isUserInputEnabled = true
        viewPager.adapter = pagerAdapter

        val searchView: AutoCompleteTextView = binding.searchViewText

        // Primary tabs: Anime / Manga / Novel
        val tabs = binding.extensionsTypeTabs
        ExtensionPages.TYPES.forEach { type ->
            tabs.addTab(tabs.newTab().setText(typeLabel(type)), false)
        }

        tabs.getTabAt(ExtensionPages.tabIndexOf(currentType))?.select()
        binding.extensionsStatusToggle.check(
            if (startInstalled) R.id.extensionsStatusInstalled else R.id.extensionsStatusAvailable
        )
        listOf(binding.extensionsStatusInstalled, binding.extensionsStatusAvailable).forEach { button ->
            markSegment(button, null, button.isChecked)
        }

        var lastDisplayedPage = startPage

        fun applyPageChrome(type: MediaType, installed: Boolean) {
            searchView.hint = getString(searchHint(type))
            binding.extensionsLanguageBar.isVisible = !installed
            if (!installed) updateLanguageChip()
        }

        fun selectPage(smoothStatusSwitch: Boolean) {
            val type = ExtensionPages.typeForTab(tabs.selectedTabPosition)
            val installed = binding.extensionsStatusToggle.checkedButtonId != R.id.extensionsStatusAvailable
            val statusIndex = ExtensionPages.statusIndexOf(installed)
            val page = ExtensionPages.pageOf(type, installed)
            val previousPage = lastDisplayedPage

            val typeChanged = pagerAdapter.setType(type)
            currentType = type

            if (page != previousPage) {
                lastDisplayedPage = page
                if (searchView.text.isNotEmpty()) {
                    // During a swipe ViewPager2 has already moved to the new page, so reset
                    // the outgoing page's filter too before clearing the search box.
                    (supportFragmentManager.findFragmentByTag("f$previousPage") as? SearchQueryHandler)
                        ?.updateContentBasedOnQuery("")
                    searchView.setText("")
                }
                searchView.clearFocus()
            }

            if (typeChanged) {
                viewPager.setCurrentItem(statusIndex, false)
            } else if (viewPager.currentItem != statusIndex) {
                viewPager.setCurrentItem(statusIndex, smoothStatusSwitch)
            }
            applyPageChrome(type, installed)
        }

        viewPager.setCurrentItem(startStatus, false)
        applyPageChrome(currentType, startInstalled)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val installed = ExtensionPages.isInstalledStatusIndex(position)
                val targetButton = if (installed) {
                    R.id.extensionsStatusInstalled
                } else {
                    R.id.extensionsStatusAvailable
                }
                if (binding.extensionsStatusToggle.checkedButtonId != targetButton) {
                    binding.extensionsStatusToggle.check(targetButton)
                } else {
                    selectPage(smoothStatusSwitch = false)
                }
            }
        })

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectPage(smoothStatusSwitch = false)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        binding.extensionsStatusToggle.addOnButtonCheckedListener { group, id, isChecked ->
            markSegment(group.findViewById(id), null, isChecked)
            if (isChecked) selectPage(smoothStatusSwitch = true)
        }

        binding.extensionsLanguageChip.setOnClickListener {
            // The chip toggles itself on tap; keep its checked state tied to the active filter.
            updateLanguageChip()
            chooseLanguage()
        }

        binding.extensionsReposButton.setOnClickListener {
            openRepositories(currentType)
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
        outState.putInt(KEY_PAGE, currentPage())
    }

    private fun currentPage(): Int =
        ExtensionPages.pageOfStatus(currentType, binding.viewPager.currentItem)

    private fun currentPageFragment(): Fragment? =
        supportFragmentManager.findFragmentByTag("f${currentPage()}")

    private fun updatePagerUserInput() {
        binding.viewPager.isUserInputEnabled = !reorderDragging && !verticalSwipeLocked
    }

    internal fun setReorderDragging(dragging: Boolean) {
        if (reorderDragging == dragging) return
        reorderDragging = dragging
        if (::binding.isInitialized) updatePagerUserInput()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::binding.isInitialized && binding.viewPager.isVisible) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipePointerId = ev.getPointerId(0)
                    swipeDownX = ev.getX(0)
                    swipeDownY = ev.getY(0)
                    swipeAxis = if (binding.viewPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) {
                        ExtensionPages.SwipeAxis.HORIZONTAL
                    } else {
                        ExtensionPages.SwipeAxis.NONE
                    }
                    if (verticalSwipeLocked) {
                        verticalSwipeLocked = false
                        updatePagerUserInput()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (swipeAxis == ExtensionPages.SwipeAxis.NONE && swipePointerId != MotionEvent.INVALID_POINTER_ID) {
                        val idx = ev.findPointerIndex(swipePointerId)
                        if (idx >= 0) {
                            val dx = abs(ev.getX(idx) - swipeDownX)
                            val dy = abs(ev.getY(idx) - swipeDownY)
                            val axis = ExtensionPages.classifySwipeAxis(dx, dy, swipeTouchSlop)
                            if (axis != ExtensionPages.SwipeAxis.NONE) {
                                swipeAxis = axis
                                if (axis == ExtensionPages.SwipeAxis.VERTICAL) {
                                    verticalSwipeLocked = true
                                    updatePagerUserInput()
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val liftedIndex = ev.actionIndex
                    if (ev.getPointerId(liftedIndex) == swipePointerId) {
                        val replacementIndex = if (liftedIndex == 0) 1 else 0
                        if (replacementIndex < ev.pointerCount) {
                            swipePointerId = ev.getPointerId(replacementIndex)
                            swipeDownX = ev.getX(replacementIndex)
                            swipeDownY = ev.getY(replacementIndex)
                        } else {
                            swipePointerId = MotionEvent.INVALID_POINTER_ID
                        }
                    }
                }
            }
        }
        val shouldReset =
            ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        return try {
            super.dispatchTouchEvent(ev)
        } finally {
            if (shouldReset) {
                swipeAxis = ExtensionPages.SwipeAxis.NONE
                swipePointerId = MotionEvent.INVALID_POINTER_ID
                if (verticalSwipeLocked) {
                    verticalSwipeLocked = false
                    if (::binding.isInitialized) updatePagerUserInput()
                }
            }
        }
    }

    private fun showMenu(anchor: View) {
        val page = currentPage()
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
        if (show) {
            binding.searchViewText.clearFocus()
            swipeAxis = ExtensionPages.SwipeAxis.NONE
            swipePointerId = MotionEvent.INVALID_POINTER_ID
            verticalSwipeLocked = false
            reorderDragging = false
            updatePagerUserInput()
        }
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

    private class StatusPagerAdapter(
        activity: AppCompatActivity,
        private var type: MediaType,
    ) : FragmentStateAdapter(activity) {
        @SuppressLint("NotifyDataSetChanged")
        fun setType(newType: MediaType): Boolean {
            if (type == newType) return false
            type = newType
            notifyDataSetChanged()
            return true
        }

        override fun getItemCount(): Int = ExtensionPages.STATUS_PAGE_COUNT

        override fun getItemId(position: Int): Long =
            ExtensionPages.pageOfStatus(type, position).toLong()

        override fun containsItem(itemId: Long): Boolean =
            ExtensionPages.belongsToType(itemId, type)

        override fun createFragment(position: Int): Fragment =
            when (ExtensionPages.pageOfStatus(type, position)) {
                0 -> InstalledAnimeExtensionsFragment()
                1 -> AnimeExtensionsFragment()
                2 -> InstalledMangaExtensionsFragment()
                3 -> MangaExtensionsFragment()
                4 -> InstalledNovelExtensionsFragment()
                5 -> NovelExtensionsFragment()
                else -> AnimeExtensionsFragment()
            }
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
