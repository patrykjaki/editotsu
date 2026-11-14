package ani.dantotsu.media

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.ActivityCalendarBinding
import ani.dantotsu.databinding.ItemCalendarDayBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.media.calendar.CalendarDayFragment
import ani.dantotsu.media.calendar.CalendarWeek
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The release calendar: a strip with the days of the week on top, and under it the episodes
 * of the chosen day in airing order. One filter narrows it to the user's list or to dubbed
 * shows; a day it empties stays in the strip.
 */
class CalendarActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalendarBinding
    private val model: OtherDetailsViewModel by viewModels()
    /** index into [filterLabels]: everything, the user's list, dubbed */
    private var filter = 0
    private val showOnlyLibrary get() = filter == 1
    private val showOnlyDubbed get() = filter == 2
    private val filterLabels by lazy {
        listOf(R.string.calendar_everything, R.string.calendar_my_list, R.string.calendar_dubbed).map(::getString)
    }

    /** the date the user is on, kept across reloads; null until the first load picks today */
    private var selectedKey: String? = null
    private var days: List<CalendarWeek.Day> = emptyList()
    private val pageListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            selectedKey = days.getOrNull(position)?.key ?: return
            markSelected(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCalendarBinding.inflate(layoutInflater)

        val surface = getThemeColor(com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        if (!(PrefManager.getVal(PrefName.ImmersiveMode) as Boolean)) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.calendarHeader.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)

        filter = (savedInstanceState?.getInt(STATE_FILTER) ?: 0).coerceIn(0, 2)
        selectedKey = savedInstanceState?.getString(STATE_DAY)
        showFilter()
        binding.calendarFilter.setOnClickListener {
            showFilter() // the chip toggles itself on a tap; it shows the choice, not the tap
            ChoiceSheet.single(
                this, getString(R.string.calendar_filter_title), filterLabels.map { ChoiceOption(it) }, filter,
            ) { picked ->
                if (picked == filter) return@single
                filter = picked
                showFilter()
                reload()
            }
        }
        binding.calendarFilter.setOnCloseIconClickListener { binding.calendarFilter.performClick() }
        binding.calendarPager.registerOnPageChangeCallback(pageListener)

        model.getCalendar().observe(this) { week ->
            if (week == null) return@observe
            binding.calendarLoading.visibility = View.GONE
            binding.calendarFailed.visibility = if (week.isEmpty()) View.VISIBLE else View.GONE
            show(week)
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { model.loadCalendar(showOnlyLibrary, showOnlyDubbed) }
                    live.postValue(false)
                }
            }
        }
    }

    /** the chip names the current choice and is filled while something is filtered out */
    private fun showFilter() {
        binding.calendarFilter.text = filterLabels[filter]
        binding.calendarFilter.isChecked = filter != 0
    }

    private fun reload() {
        lifecycleScope.launch(Dispatchers.IO) { model.loadCalendar(showOnlyLibrary, showOnlyDubbed) }
    }

    private fun show(week: Map<String, MutableList<Media>>) {
        days = CalendarWeek.days(week.mapValues { it.value.size })
        val filtered = showOnlyLibrary || showOnlyDubbed
        val index = days.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: CalendarWeek.startIndex(days)
        selectedKey = days.getOrNull(index)?.key

        binding.calendarWeek.removeAllViews()
        days.forEachIndexed { i, day ->
            val cell = ItemCalendarDayBinding.inflate(LayoutInflater.from(this), binding.calendarWeek, true)
            cell.calendarDayWeekday.text = day.weekday
            cell.calendarDayNumber.text = day.number
            cell.calendarDayCount.text = if (day.count > 0) day.count.toString() else ""
            cell.root.contentDescription =
                resources.getQuantityString(R.plurals.calendar_day_description, day.count, day.key, day.count)
            cell.root.setOnClickListener { binding.calendarPager.setCurrentItem(i, true) }
        }
        binding.calendarPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = days.size
            override fun createFragment(position: Int): Fragment = CalendarDayFragment.newInstance(position, filtered)
        }
        if (days.isNotEmpty()) binding.calendarPager.setCurrentItem(index, false)
        markSelected(index)
    }

    /** the chosen day is a filled circle; today, when not chosen, has its number in the accent colour */
    private fun markSelected(selected: Int) {
        val primary = getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        for (i in 0 until binding.calendarWeek.childCount) {
            val cell = ItemCalendarDayBinding.bind(binding.calendarWeek.getChildAt(i))
            val isSelected = i == selected
            cell.root.isSelected = isSelected
            cell.calendarDayNumber.backgroundTintList =
                ColorStateList.valueOf(if (isSelected) primary else android.graphics.Color.TRANSPARENT)
            cell.calendarDayNumber.setTextColor(
                when {
                    isSelected -> onPrimary
                    days.getOrNull(i)?.today == true -> primary
                    else -> onSurface
                },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FILTER, filter)
        outState.putString(STATE_DAY, selectedKey)
    }

    override fun onDestroy() {
        super.onDestroy()
        Refresh.activity.remove(this.hashCode())
        if (::binding.isInitialized) {
            binding.calendarPager.unregisterOnPageChangeCallback(pageListener)
            binding.calendarPager.adapter = null
        }
    }

    private companion object {
        const val STATE_FILTER = "filter"
        const val STATE_DAY = "day"
    }
}
