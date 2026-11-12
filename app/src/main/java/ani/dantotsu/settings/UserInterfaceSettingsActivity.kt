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
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.initActivity
import ani.dantotsu.reloadActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.SettingsScreenActivity
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.sliderRow
import ani.dantotsu.settings.rows.textSegment
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorDialog

/** Settings > Appearance ([AppearanceScreen]). Theme changes recreate it. */
class UserInterfaceSettingsActivity : SettingsScreenActivity(), SimpleDialog.OnDialogResultListener {
    override val screen get() = AppearanceScreen

    // True only after a theme change recreated this screen.
    private var reload = PrefManager.getCustomVal("reload", false)

    override fun onScreenCreate(savedInstanceState: Bundle?) {
        // Off unless the app must restart, so plain back shows the predictive preview.
        onBackPressedDispatcher.addCallback(this, enabled = reload) {
            if (reload) {
                val mainIntent = Intent.makeRestartActivityTask(
                    packageManager.getLaunchIntentForPackage(packageName)!!.component
                )
                val component = ComponentName(packageName, SettingsActivity::class.qualifiedName!!)
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
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE && dialogTag == COLOR_PICKER) {
            val color = extras.getInt(SimpleColorDialog.COLOR)
            PrefManager.setVal(PrefName.CustomThemeInt, color)
            Logger.log("Custom Theme: $color")
        }
        return true
    }

    /** Theme changes recreate the screen; back then restarts into Settings. */
    fun reloadTheme() {
        PrefManager.setCustomVal("reload", true)
        Handler(Looper.getMainLooper()).postDelayed({
            reloadActivity()
            finishAndRemoveTask()
        }, 100)
    }

    companion object {
        const val COLOR_PICKER = "colorPicker"
    }
}

/** Settings > Appearance: theme, general, home, animations, blur. */
object AppearanceScreen : SettingsScreen("appearance", R.string.settings_appearance, R.drawable.ic_palette, R.string.settings_appearance_desc) {

    override val activity get() = UserInterfaceSettingsActivity::class.java

    private val sdkS = { Build.VERSION.SDK_INT > Build.VERSION_CODES.R }

    private fun reloadTheme(host: SettingsHost) = (host.activity as? UserInterfaceSettingsActivity)?.reloadTheme()

    private val restart: (SettingsHost, Any) -> Unit = { host, _ -> host.activity.restartApp() }

    private fun themeNames() = ThemeManager.Companion.Theme.entries.map {
        it.theme.substring(0, 1) + it.theme.substring(1).lowercase()
    }

    /** Animation speed: slider position (multiplier) to stored duration scale. */
    private val SPEED_TO_PREF = mapOf(
        2f to 0.5f, 1.75f to 0.625f, 1.5f to 0.75f, 1.25f to 0.875f, 1f to 1f,
        0.75f to 1.25f, 0.5f to 1.5f, 0.25f to 1.75f, 0f to 0f,
    )
    private val PREF_TO_SPEED = SPEED_TO_PREF.entries.associate { it.value to it.key }

    override fun sections() = listOf(
        Section(R.string.theme, listOf(
            segmentedRow(PrefName.DarkMode, R.string.theme_mode, R.drawable.ic_round_brightness_auto_24,
                options = listOf(
                    Segment(R.drawable.ic_round_brightness_auto_24, R.string.theme_mode_auto),
                    Segment(R.drawable.ic_round_brightness_high_24, R.string.theme_mode_light),
                    Segment(R.drawable.ic_round_brightness_4_24, R.string.theme_mode_dark),
                ),
                onChange = { host, mode ->
                    if (mode == 1) PrefManager.setVal(PrefName.UseOLED, false)
                    reloadTheme(host)
                }),
            Row(
                id = PrefName.Theme.name, title = R.string.theme, icon = R.drawable.ic_palette,
                keys = listOf(PrefName.Theme),
                kind = Kind.Choice(
                    labels = { themeNames() },
                    get = {
                        val current = PrefManager.getVal<String>(PrefName.Theme)
                        ThemeManager.Companion.Theme.entries.indexOfFirst { it.theme == current }
                    },
                    set = { host, i ->
                        PrefManager.setVal(PrefName.Theme, ThemeManager.Companion.Theme.entries[i].theme)
                        reloadTheme(host)
                    },
                ),
            ),
            switchRow(PrefName.UseOLED, R.string.oled_theme_variant, R.string.oled_theme_variant_desc,
                R.drawable.ic_round_brightness_4_24, onChange = { host, _ -> reloadTheme(host) }),
            switchRow(PrefName.UseSystemFont, R.string.use_system_font, icon = R.drawable.ic_round_font_size_24,
                onChange = { host, _ -> reloadTheme(host) }),
            switchRow(PrefName.UseMaterialYou, R.string.use_material_you, R.string.use_material_you_desc,
                R.drawable.ic_round_new_releases_24, visible = sdkS,
                onChange = { host, on ->
                    if (on) PrefManager.setVal(PrefName.UseCustomTheme, false)
                    reloadTheme(host)
                }),
            switchRow(PrefName.UseSourceTheme, R.string.use_unique_theme_for_each_item,
                R.string.use_unique_theme_for_each_item_desc, R.drawable.ic_palette, visible = sdkS),
            switchRow(PrefName.UseCustomTheme, R.string.use_custom_theme, icon = R.drawable.ic_palette, visible = sdkS,
                onChange = { host, on ->
                    if (on) PrefManager.setVal(PrefName.UseMaterialYou, false)
                    reloadTheme(host)
                }),
            actionRow(PrefName.CustomThemeInt.name, R.string.color_picker, icon = R.drawable.ic_palette,
                visible = sdkS, keys = listOf(PrefName.CustomThemeInt),
                onClick = { host, _ -> pickColor(host) }),
        )),
        Section(R.string.general, listOf(
            segmentedRow(PrefName.DefaultStartUpTab, R.string.startUpTab, R.drawable.ic_round_home_24,
                listOf(textSegment(R.string.anime), textSegment(R.string.home), textSegment(R.string.manga)),
                onChange = { host, _ -> initActivity(host.activity) }),
            switchRow(PrefName.ContinueMedia, R.string.always_continue_content, R.string.always_continue_content_desc,
                R.drawable.ic_round_play_arrow_24),
            switchRow(PrefName.RecentlyListOnly, R.string.recentlyListOnly, R.string.recentlyListOnly_desc,
                R.drawable.ic_round_new_releases_24),
        )),
        Section(R.string.app, listOf(
            switchRow(PrefName.HideSpoilerTags, R.string.hide_spoiler_tags, icon = R.drawable.format_spoiler_24),
            switchRow(PrefName.HideEpisodeSpoilers, R.string.hide_episode_spoilers, icon = R.drawable.format_spoiler_24),
            switchRow(PrefName.ImmersiveMode, R.string.immersive_mode, R.string.immersive_mode_info,
                R.drawable.ic_round_fullscreen_24, onChange = restart),
            Row(
                id = PrefName.ShowNotificationRedDot.name, title = R.string.hide_notification_dot,
                icon = R.drawable.ic_round_notifications_active_24, keys = listOf(PrefName.ShowNotificationRedDot),
                kind = Kind.Switch(
                    get = { !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) },
                    set = { _, hide -> PrefManager.setVal(PrefName.ShowNotificationRedDot, !hide) },
                ),
            ),
            actionRow(PrefName.HomeLayout.name, R.string.home_layout_show, icon = R.drawable.ic_round_playlist_add_24,
                keys = listOf(PrefName.HomeLayout, PrefName.HomeLayoutOrder),
                onClick = { host, _ -> editHomeLayout(host) }),
            switchRow(PrefName.SmallView, R.string.small_view, icon = R.drawable.ic_round_art_track_24, onChange = restart),
            switchRow(PrefName.CarouselClearLogo, R.string.carousel_clearlogo, icon = R.drawable.ic_round_auto_awesome_24,
                onChange = restart),
        )),
        Section(R.string.animations, listOf(
            switchRow(PrefName.BannerAnimations, R.string.banner_animations,
                icon = R.drawable.ic_round_photo_size_select_actual_24, onChange = restart),
            switchRow(PrefName.LayoutAnimations, R.string.layout_animations, icon = R.drawable.ic_round_animation_24,
                onChange = restart),
            switchRow(PrefName.TrendingScroller, R.string.trending_scroller, icon = R.drawable.trail_length_short,
                advanced = true),
            Row(
                id = PrefName.AnimationSpeed.name, title = R.string.animation_speed,
                icon = R.drawable.ic_round_slow_motion_video_24, keys = listOf(PrefName.AnimationSpeed),
                kind = Kind.Slider(
                    from = 0f, to = 2f, step = 0.25f,
                    get = { PREF_TO_SPEED[PrefManager.getVal<Float>(PrefName.AnimationSpeed)] ?: 1f },
                    set = { host, v ->
                        PrefManager.setVal(PrefName.AnimationSpeed, SPEED_TO_PREF[v] ?: 1f)
                        host.activity.restartApp()
                    },
                    format = { c, v ->
                        if (v == 0f) c.getString(R.string.disabled)
                        else c.getString(R.string.speed_multiplier, v.toString().removeSuffix(".0"))
                    },
                ),
            ),
        )),
        Section(R.string.blur, listOf(
            switchRow(PrefName.BlurBanners, R.string.blur_banners, icon = R.drawable.blur_on, onChange = restart),
            sliderRow(PrefName.BlurRadius, R.string.radius, icon = R.drawable.adjust, from = 1f, to = 10f, step = 1f,
                format = { _, v -> v.toInt().toString() }, advanced = true,
                onChange = { host, _ -> host.activity.restartApp() }),
            sliderRow(PrefName.BlurSampling, R.string.sampling, icon = R.drawable.stacks, from = 1f, to = 10f, step = 1f,
                format = { _, v -> v.toInt().toString() }, advanced = true,
                onChange = { host, _ -> host.activity.restartApp() }),
        )),
    )

    private fun pickColor(host: SettingsHost) {
        val activity = host.activity as? UserInterfaceSettingsActivity ?: return
        val originalColor: Int = PrefManager.getVal(PrefName.CustomThemeInt)

        class CustomColorDialog : SimpleColorDialog() {
            override fun onPositiveButtonClick() {
                activity.reloadTheme()
                super.onPositiveButtonClick()
            }
        }
        CustomColorDialog().title(R.string.custom_theme)
            .colorPreset(originalColor)
            .colors(activity, SimpleColorDialog.MATERIAL_COLOR_PALLET)
            .allowCustom(true).showOutline(0x46000000).gridNumColumn(5)
            .choiceMode(SimpleColorDialog.SINGLE_CHOICE).neg()
            .show(activity, UserInterfaceSettingsActivity.COLOR_PICKER)
    }

    /** Home sections: show/hide each, drag to reorder; the first one stays first. */
    private fun editHomeLayout(host: SettingsHost) = with(host.activity) {
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
            sanitizedOrder.addAll(reorderable.filterNot { it in sanitizedOrder })
            currentOrder = sanitizedOrder
        }

        val displayList = mutableListOf(fixedIndex)
        displayList.addAll(currentOrder.filter { it != fixedIndex })

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@with)
            setPadding(0, 32, 0, 0)
            clipToPadding = false
        }
        val adapter = HomeLayoutAdapter(displayList, views, currentVisibility)
        recyclerView.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
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
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.elevation = 8f
            }
        }).attachToRecyclerView(recyclerView)

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

    private class HomeLayoutAdapter(
        private val displayList: MutableList<Int>,
        private val views: Array<String>,
        private val currentVisibility: MutableList<Boolean>
    ) : RecyclerView.Adapter<HomeLayoutAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dragHandle: ImageView = view.findViewById(R.id.itemHomeLayoutDragHandle)
            val title: TextView = view.findViewById(R.id.itemHomeLayoutTitle)
            val switch: MaterialSwitch = view.findViewById(R.id.itemHomeLayoutSwitch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_home_layout, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val originalIndex = displayList[position]
            holder.title.text = views[originalIndex]
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = currentVisibility[originalIndex]
            holder.switch.setOnCheckedChangeListener { _, isChecked -> currentVisibility[originalIndex] = isChecked }
            holder.dragHandle.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        }

        override fun getItemCount(): Int = displayList.size
    }
}
