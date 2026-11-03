package ani.dantotsu.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityUserInterfaceSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.reloadActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.search.SettingsSearchAdapter
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorDialog

/** Settings > Appearance: theme, start-up behaviour, home layout, animations, blur. */
class UserInterfaceSettingsActivity : AppCompatActivity(), SimpleDialog.OnDialogResultListener {
    lateinit var binding: ActivityUserInterfaceSettingsBinding
    // True only after a theme change recreated this screen.
    private var reload = PrefManager.getCustomVal("reload", false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityUserInterfaceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.uiSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.uiSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val context = this
        val highlightKey = intent.getStringExtra(SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
        binding.apply {
            // Off unless the app must restart, so plain back shows the predictive preview.
            onBackPressedDispatcher.addCallback(context, enabled = reload) {
                if (reload) {
                    val packageName = context.packageName
                    val mainIntent = Intent.makeRestartActivityTask(
                        packageManager.getLaunchIntentForPackage(packageName)!!.component
                    )
                    val component =
                        ComponentName(packageName, SettingsActivity::class.qualifiedName!!)
                    try {
                        startActivity(Intent().setComponent(component))
                    } catch (e: Exception) {
                        startActivity(mainIntent)
                    }
                    finishAndRemoveTask()
                    reload = false
                } else {
                    finish()
                }
            }
            var previous: View = when (PrefManager.getVal<Int>(PrefName.DarkMode)) {
                0 -> settingsUiAuto
                1 -> settingsUiLight
                2 -> settingsUiDark
                else -> settingsUiAuto
            }
            previous.alpha = 1f
            fun uiTheme(mode: Int, current: View) {
                previous.alpha = 0.33f
                previous = current
                current.alpha = 1f
                PrefManager.setVal(PrefName.DarkMode, mode)
                reloadTheme()
            }

            settingsUiAuto.setOnClickListener {
                uiTheme(0, it)
            }

            settingsUiLight.setOnClickListener {
                PrefManager.setVal(PrefName.UseOLED, false)
                uiTheme(1, it)
            }

            settingsUiDark.setOnClickListener {
                uiTheme(2, it)
            }

            val themeString: String = PrefManager.getVal(PrefName.Theme)
            val themeText = themeString.substring(0, 1) + themeString.substring(1).lowercase()
            themeSwitcher.apply {
                setText(themeText)
                setAdapter(
                    ArrayAdapter(context,
                        R.layout.item_dropdown,
                        ThemeManager.Companion.Theme.entries.map {
                            it.theme.substring(
                                0,
                                1
                            ) + it.theme.substring(1).lowercase()
                        })
                )
                setOnItemClickListener { _, _, i, _ ->
                    PrefManager.setVal(
                        PrefName.Theme,
                        ThemeManager.Companion.Theme.entries[i].theme
                    )
                    clearFocus()
                    reloadTheme()
                }
            }

            appearanceThemeRows.apply {
                adapter = SettingsAdapter(
                    arrayListOf(
                        Settings(
                            type = 2,
                            name = getString(R.string.oled_theme_variant),
                            desc = getString(R.string.oled_theme_variant_desc),
                            icon = R.drawable.ic_round_brightness_4_24,
                            isChecked = PrefManager.getVal(PrefName.UseOLED),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.UseOLED, isChecked)
                                reloadTheme()
                            }
                        ),
                        // MAX MERGE M3 (upstream 3dbce385): opt-in device font.
                        Settings(
                            type = 2,
                            name = getString(R.string.use_system_font),
                            desc = getString(R.string.use_system_font_desc),
                            icon = R.drawable.ic_round_font_size_24,
                            isChecked = PrefManager.getVal(PrefName.UseSystemFont),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.UseSystemFont, isChecked)
                                reloadTheme()
                            }
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.use_material_you),
                            desc = getString(R.string.use_material_you_desc),
                            icon = R.drawable.ic_round_new_releases_24,
                            isChecked = PrefManager.getVal(PrefName.UseMaterialYou),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.UseMaterialYou, isChecked)
                                if (isChecked) PrefManager.setVal(PrefName.UseCustomTheme, false)
                                reloadTheme()
                            },
                            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.use_unique_theme_for_each_item),
                            desc = getString(R.string.use_unique_theme_for_each_item_desc),
                            icon = R.drawable.ic_palette,
                            isChecked = PrefManager.getVal(PrefName.UseSourceTheme),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.UseSourceTheme, isChecked)
                            },
                            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.use_custom_theme),
                            desc = getString(R.string.use_custom_theme_desc),
                            icon = R.drawable.ic_palette,
                            isChecked = PrefManager.getVal(PrefName.UseCustomTheme),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.UseCustomTheme, isChecked)
                                if (isChecked) PrefManager.setVal(PrefName.UseMaterialYou, false)
                                reloadTheme()
                            },
                            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.color_picker),
                            desc = getString(R.string.color_picker_desc),
                            icon = R.drawable.ic_palette,
                            onClick = {
                                val originalColor: Int = PrefManager.getVal(PrefName.CustomThemeInt)

                                class CustomColorDialog : SimpleColorDialog() {
                                    override fun onPositiveButtonClick() {
                                        reloadTheme()
                                        super.onPositiveButtonClick()
                                    }
                                }

                                val tag = "colorPicker"
                                CustomColorDialog().title(R.string.custom_theme)
                                    .colorPreset(originalColor)
                                    .colors(context, SimpleColorDialog.MATERIAL_COLOR_PALLET)
                                    .allowCustom(true).showOutline(0x46000000).gridNumColumn(5)
                                    .choiceMode(SimpleColorDialog.SINGLE_CHOICE).neg()
                                    .show(context, tag)
                            },
                            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
                        )
                    ),
                    highlightKey = highlightKey
                )
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            }
            appearanceGeneralRows.apply {
                adapter = SettingsAdapter(
                    ArrayList(CommonSettingsRows.general(this@UserInterfaceSettingsActivity)),
                    highlightKey = highlightKey
                )
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            }
        }

        binding.uiSettingsHomeLayout.setOnClickListener {
            val currentVisibility = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout).toMutableList()
            var currentOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder).toMutableList()
            val views = resources.getStringArray(R.array.home_layouts)
            val fixedIndex = 7

            if (currentVisibility.size < views.size) {
                repeat(views.size - currentVisibility.size) { currentVisibility.add(true) }
            } else if (currentVisibility.size > views.size) {
                currentVisibility.subList(views.size, currentVisibility.size).clear()
            }

            val reorderable = views.indices.filter { it != fixedIndex }
            if (currentOrder.isEmpty()) {
                currentOrder = reorderable.toMutableList()
            } else {
                val sanitizedOrder = currentOrder.filter { it in reorderable }.distinct().toMutableList()
                val missing = reorderable.filterNot { it in sanitizedOrder }
                sanitizedOrder.addAll(missing)
                currentOrder = sanitizedOrder
            }

            val displayList = mutableListOf(fixedIndex)
            displayList.addAll(currentOrder.filter { it != fixedIndex })

            val recyclerView = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@UserInterfaceSettingsActivity)
                setPadding(0, 32, 0, 0)
                clipToPadding = false
            }
            val adapter = HomeLayoutAdapter(displayList, views, currentVisibility)
            recyclerView.adapter = adapter

            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition

                    if (fromPos == 0 || toPos == 0) return false

                    val item = displayList.removeAt(fromPos)
                    displayList.add(toPos, item)
                    adapter.notifyItemMoved(fromPos, toPos)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.elevation = 0f
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.elevation = 8f
                    }
                }
            })
            itemTouchHelper.attachToRecyclerView(recyclerView)

            customAlertDialog().apply {
                setTitle(getString(R.string.home_layout_show))
                setCustomView(recyclerView)
                setPosButton(R.string.ok) {
                    PrefManager.setVal(PrefName.HomeLayout, currentVisibility)
                    PrefManager.setVal(PrefName.HomeLayoutOrder, displayList.drop(1))
                    restartApp()
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        binding.uiSettingsSmallView.isChecked = PrefManager.getVal(PrefName.SmallView)
        binding.uiSettingsSmallView.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.SmallView, isChecked)
            restartApp()
        }

        binding.uiSettingsClearLogo.isChecked = PrefManager.getVal(PrefName.CarouselClearLogo)
        binding.uiSettingsClearLogo.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.CarouselClearLogo, isChecked)
            restartApp()
        }

        binding.uiSettingsHideSpoilerTags.isChecked = PrefManager.getVal(PrefName.HideSpoilerTags)
        binding.uiSettingsHideSpoilerTags.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.HideSpoilerTags, isChecked)
        }
        binding.uiSettingsHideEpisodeSpoilers.isChecked = PrefManager.getVal(PrefName.HideEpisodeSpoilers)
        binding.uiSettingsHideEpisodeSpoilers.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.HideEpisodeSpoilers, isChecked)
        }

        binding.uiSettingsImmersive.isChecked = PrefManager.getVal(PrefName.ImmersiveMode)
        binding.uiSettingsImmersive.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ImmersiveMode, isChecked)
            restartApp()
        }
        binding.uiSettingsHideRedDot.isChecked =
            !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot)
        binding.uiSettingsHideRedDot.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowNotificationRedDot, !isChecked)
        }
        binding.uiSettingsBannerAnimation.isChecked = PrefManager.getVal(PrefName.BannerAnimations)
        binding.uiSettingsBannerAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BannerAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsLayoutAnimation.isChecked = PrefManager.getVal(PrefName.LayoutAnimations)
        binding.uiSettingsLayoutAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.LayoutAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsTrendingScroller.isChecked = PrefManager.getVal(PrefName.TrendingScroller)
        binding.uiSettingsTrendingScroller.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.TrendingScroller, isChecked)
        }

        val map = mapOf(
            2f to 0.5f,
            1.75f to 0.625f,
            1.5f to 0.75f,
            1.25f to 0.875f,
            1f to 1f,
            0.75f to 1.25f,
            0.5f to 1.5f,
            0.25f to 1.75f,
            0f to 0f
        )
        val mapReverse = map.map { it.value to it.key }.toMap()
        binding.uiSettingsAnimationSpeed.value =
            mapReverse[PrefManager.getVal(PrefName.AnimationSpeed)] ?: 1f
        binding.uiSettingsAnimationSpeed.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.AnimationSpeed, map[value] ?: 1f)
            restartApp()
        }
        binding.uiSettingsBlurBanners.isChecked = PrefManager.getVal(PrefName.BlurBanners)
        binding.uiSettingsBlurBanners.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BlurBanners, isChecked)
            restartApp()
        }
        binding.uiSettingsBlurRadius.value = (PrefManager.getVal(PrefName.BlurRadius) as Float)
        binding.uiSettingsBlurRadius.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurRadius, value)
            restartApp()
        }
        binding.uiSettingsBlurSampling.value = (PrefManager.getVal(PrefName.BlurSampling) as Float)
        binding.uiSettingsBlurSampling.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurSampling, value)
            restartApp()
        }
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) {
            if (dialogTag == "colorPicker") {
                val color = extras.getInt(SimpleColorDialog.COLOR)
                PrefManager.setVal(PrefName.CustomThemeInt, color)
                Logger.log("Custom Theme: $color")
            }
        }
        return true
    }

    /** Theme changes recreate the screen; back then restarts into Settings. */
    private fun reloadTheme() {
        PrefManager.setCustomVal("reload", true)
        Handler(Looper.getMainLooper()).postDelayed({
            reloadActivity()
            finishAndRemoveTask()
        }, 100)
    }

    inner class HomeLayoutAdapter(
        private val displayList: MutableList<Int>,
        private val views: Array<String>,
        private val currentVisibility: MutableList<Boolean>
    ) : RecyclerView.Adapter<HomeLayoutAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.itemHomeLayoutDragHandle)
            val title: TextView = view.findViewById(R.id.itemHomeLayoutTitle)
            val switch: MaterialSwitch = view.findViewById(R.id.itemHomeLayoutSwitch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val originalIndex = displayList[position]
            holder.title.text = views[originalIndex]
            
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = currentVisibility[originalIndex]
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                currentVisibility[originalIndex] = isChecked
            }

            if (position == 0) {
                holder.dragHandle.visibility = View.INVISIBLE
            } else {
                holder.dragHandle.visibility = View.VISIBLE
            }
        }

        override fun getItemCount(): Int = displayList.size
    }
}
