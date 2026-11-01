package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import androidx.appcompat.widget.PopupMenu
import java.util.Locale

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight
                }
            } else {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight + navBarHeight
                }
            }
        }

        binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = statusBarHeight + navBarHeight
        }

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

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)

        val typeButtons = mapOf(
            MediaType.ANIME to R.id.extensionsTypeAnime,
            MediaType.MANGA to R.id.extensionsTypeManga,
            MediaType.NOVEL to R.id.extensionsTypeNovel,
        )
        val startPage = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        binding.extensionsTypeToggle.check(typeButtons.getValue(ExtensionPages.typeOf(startPage)))
        binding.extensionsStatusChips.check(
            if (ExtensionPages.isInstalled(startPage)) R.id.extensionsChipInstalled
            else R.id.extensionsChipAvailable
        )
        fun selectPage() {
            val type = typeButtons.entries.firstOrNull {
                it.value == binding.extensionsTypeToggle.checkedButtonId
            }?.key ?: MediaType.ANIME
            val installed =
                binding.extensionsStatusChips.checkedChipId != R.id.extensionsChipAvailable
            val page = ExtensionPages.pageOf(type, installed)
            if (viewPager.currentItem != page) {
                searchView.setText("")
                searchView.clearFocus()
                viewPager.setCurrentItem(page, false)
            }
        }
        viewPager.setCurrentItem(startPage, false)
        binding.extensionsTypeToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) selectPage()
        }
        binding.extensionsStatusChips.setOnCheckedStateChangeListener { _, _ -> selectPage() }
        binding.extensionsMenuButton.setOnClickListener { showMenu(it) }

        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentFragment =
                    supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.updateContentBasedOnQuery(s?.toString()?.trim())
                }
            }
        })

        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, binding.viewPager.currentItem)
    }

    private fun currentPageFragment(): Fragment? =
        supportFragmentManager.findFragmentByTag("f${binding.viewPager.currentItem}")

    private fun showMenu(anchor: View) {
        val page = binding.viewPager.currentItem
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_REPOS, 0, R.string.extensions_menu_repositories)
            if (!ExtensionPages.isInstalled(page)) {
                menu.add(0, MENU_LANGUAGE, 1, R.string.extensions_menu_language)
            }
            menu.add(0, MENU_TEST, 2, R.string.extensions_menu_test)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_REPOS -> openRepositories(ExtensionPages.typeOf(page))
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

    private fun chooseLanguage() {
        val languageOptions =
            LanguageMapper.Companion.Language.entries.map { entry ->
                entry.name.lowercase().replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }.toTypedArray()
        val listOrder: String = PrefManager.getVal(PrefName.LangSort)
        val index = LanguageMapper.Companion.Language.entries.toTypedArray()
            .indexOfFirst { it.code == listOrder }
        customAlertDialog().apply {
            setTitle(getString(R.string.extensions_menu_language))
            singleChoiceItems(languageOptions, index) { selected ->
                PrefManager.setVal(
                    PrefName.LangSort,
                    LanguageMapper.Companion.Language.entries[selected].code
                )
                (currentPageFragment() as? SearchQueryHandler)?.notifyDataChanged()
            }
            show()
        }
    }

    private fun openRepositories(type: MediaType) {
        val repos: Set<String> = when (type) {
            MediaType.ANIME -> PrefManager.getVal(PrefName.AnimeExtensionRepos)
            MediaType.MANGA -> PrefManager.getVal(PrefName.MangaExtensionRepos)
            MediaType.NOVEL -> PrefManager.getVal(PrefName.NovelExtensionRepos)
        }
        AddRepositoryBottomSheet.newInstance(
            type,
            repos.toList(),
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
        binding.extensionsFilterBar.isVisible = !show
        binding.extensionsMenuButton.isVisible = !show
        binding.searchView.isVisible = !show
        binding.fragmentExtensionsContainer.isGone = !show
        binding.extensions.text = if (show && title != null) title else getString(R.string.extensions)
    }

    private companion object {
        const val KEY_PAGE = "extensions_page"
        const val MENU_REPOS = 1
        const val MENU_LANGUAGE = 2
        const val MENU_TEST = 3
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
