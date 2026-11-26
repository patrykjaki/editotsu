package ani.dantotsu.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemFilterMoreToggleBinding
import ani.dantotsu.databinding.ItemFilterOptionBinding
import ani.dantotsu.databinding.ItemYearCellBinding
import ani.dantotsu.databinding.SheetFilterPickerBinding
import ani.dantotsu.databinding.SheetYearPickerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.Calendar

/**
 * Focused, content-sized Material 3 pickers for Search Filters:
 * - [showOptions]: lightweight 48dp option list (with optional collapsible "More" group for long lists like Source).
 * - [showYearGrid]: compact 4-column Year grid with decade quick-jump chips and zero repeated years.
 */
object SearchFilterPickers {

    data class Option(val key: String, val label: String)

    fun showOptions(
        context: Context,
        title: CharSequence,
        primaryOptions: List<Option>,
        selectedKey: String?,
        moreOptions: List<Option> = emptyList(),
        moreTitle: CharSequence? = null,
        allowClear: Boolean = true,
        onPick: (String?) -> Unit,
    ): BottomSheetDialog? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
        val dialog = BottomSheetDialog(context)
        val b = SheetFilterPickerBinding.inflate(LayoutInflater.from(context))

        b.filterPickerTitle.text = title
        val clearCd = context.getString(R.string.search_filter_clear_cd, title)
        b.filterPickerClear.isVisible = allowClear && selectedKey != null
        b.filterPickerClear.contentDescription = clearCd
        TooltipCompat.setTooltipText(b.filterPickerClear, clearCd)
        b.filterPickerClear.setOnClickListener {
            onPick(null)
            dialog.dismiss()
        }

        val initialExpanded = selectedKey != null && moreOptions.any { it.key == selectedKey }
        val adapter = OptionAdapter(
            primaryOptions = primaryOptions,
            moreOptions = moreOptions,
            moreTitle = moreTitle ?: context.getString(R.string.search_filters_more),
            selectedKey = selectedKey,
            initiallyExpanded = initialExpanded,
        ) { pickedKey ->
            val next = if (allowClear && pickedKey == selectedKey) null else pickedKey
            onPick(next)
            dialog.dismiss()
        }

        val layoutManager = LinearLayoutManager(context)
        b.filterPickerList.layoutManager = layoutManager
        b.filterPickerList.adapter = adapter
        b.filterPickerList.itemAnimator = null

        val selectedPos = adapter.positionOfKey(selectedKey)
        if (selectedPos > 5) {
            layoutManager.scrollToPositionWithOffset(selectedPos, (48 * context.resources.displayMetrics.density).toInt())
        }

        dialog.setContentView(b.root)
        applyNavBarInset(b.root)
        dialog.setTitle(title)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        applySurfaceBackground(dialog, b.root)
        ViewCompat.requestApplyInsets(b.root)
        return dialog
    }

    fun showYearGrid(
        context: Context,
        selectedYear: Int?,
        maxYear: Int = Calendar.getInstance().get(Calendar.YEAR) + 1,
        onPick: (Int?) -> Unit,
    ): BottomSheetDialog? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
        val years = SearchFilterPolicy.yearsDescending(maxYear)
        val decades = SearchFilterPolicy.decades(years)
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val b = SheetYearPickerBinding.inflate(inflater)

        val title = b.yearPickerTitle.text
        val clearCd = context.getString(R.string.search_filter_clear_cd, title)
        b.yearPickerClear.isVisible = selectedYear != null
        b.yearPickerClear.contentDescription = clearCd
        TooltipCompat.setTooltipText(b.yearPickerClear, clearCd)
        b.yearPickerClear.setOnClickListener {
            onPick(null)
            dialog.dismiss()
        }

        val activeDecade = selectedYear?.let(SearchFilterPolicy::decadeOf)
            ?: SearchFilterPolicy.lastYearDecade?.takeIf { d -> decades.any { it.decade == d } }
            ?: decades.firstOrNull()?.decade

        // The grid shows one decade at a time (at most 10 years, no scrolling); the decade
        // chips wrap onto a second row instead of running off the screen edge.
        val adapter = YearGridAdapter(SearchFilterPolicy.yearsInDecade(years, activeDecade), selectedYear) { picked ->
            SearchFilterPolicy.lastYearDecade = SearchFilterPolicy.decadeOf(picked)
            val next = if (picked == selectedYear) null else picked
            onPick(next)
            dialog.dismiss()
        }
        b.yearPickerGrid.layoutManager = GridLayoutManager(context, YEAR_COLUMNS)
        b.yearPickerGrid.adapter = adapter
        b.yearPickerGrid.itemAnimator = null

        val decadeChips = LinkedHashMap<Int, Chip>(decades.size)
        decades.forEach { jump ->
            val chip = inflater.inflate(R.layout.item_search_chip, b.yearPickerDecades, false) as Chip
            chip.id = View.generateViewId()
            chip.text = context.getString(R.string.search_filter_decade, jump.decade)
            chip.isChecked = jump.decade == activeDecade
            chip.setOnClickListener {
                SearchFilterPolicy.lastYearDecade = jump.decade
                decadeChips.forEach { (d, c) -> c.isChecked = d == jump.decade }
                adapter.submit(SearchFilterPolicy.yearsInDecade(years, jump.decade))
            }
            decadeChips[jump.decade] = chip
            b.yearPickerDecades.addView(chip)
        }

        dialog.setContentView(b.root)
        applyNavBarInset(b.root)
        dialog.setTitle(title)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        applySurfaceBackground(dialog, b.root)
        ViewCompat.requestApplyInsets(b.root)
        return dialog
    }

    private fun applyNavBarInset(root: View) {
        val basePadding = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = basePadding + nav)
            insets
        }
    }

    private fun applySurfaceBackground(dialog: BottomSheetDialog, root: View) {
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(
                MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface),
            )
    }

    private fun roundedRippleBackground(
        view: View,
        cornerDp: Float,
        fillColor: Int,
        strokeColor: Int? = null,
    ): RippleDrawable {
        val density = view.resources.displayMetrics.density
        val shape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(cornerDp * density)
            .build()
        val content = MaterialShapeDrawable(shape).apply {
            setFillColor(ColorStateList.valueOf(fillColor))
            if (strokeColor != null) {
                setStroke(1f * density, strokeColor)
            }
        }
        val mask = MaterialShapeDrawable(shape).apply {
            setFillColor(ColorStateList.valueOf(Color.WHITE))
        }
        val ripple = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    private sealed interface Row {
        class Choice(val option: Option) : Row
        data object MoreToggle : Row
    }

    private class OptionAdapter(
        private val primaryOptions: List<Option>,
        private val moreOptions: List<Option>,
        private val moreTitle: CharSequence,
        private val selectedKey: String?,
        initiallyExpanded: Boolean,
        private val onPick: (String) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var expanded = initiallyExpanded
        private var rows: List<Row> = buildRows()

        private fun buildRows(): List<Row> = buildList(primaryOptions.size + moreOptions.size + 1) {
            primaryOptions.forEach { add(Row.Choice(it)) }
            if (moreOptions.isNotEmpty()) {
                add(Row.MoreToggle)
                if (expanded) {
                    moreOptions.forEach { add(Row.Choice(it)) }
                }
            }
        }

        fun positionOfKey(key: String?): Int {
            if (key == null) return -1
            return rows.indexOfFirst { it is Row.Choice && it.option.key == key }
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.MoreToggle) TYPE_MORE else TYPE_OPTION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_MORE) {
                MoreHolder(ItemFilterMoreToggleBinding.inflate(inflater, parent, false))
            } else {
                OptionHolder(ItemFilterOptionBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Choice -> bindOption(holder as OptionHolder, row.option)
                Row.MoreToggle -> bindMoreToggle(holder as MoreHolder)
            }
        }

        private fun bindOption(holder: OptionHolder, option: Option) {
            val b = holder.binding
            val selected = option.key == selectedKey
            val fill = if (selected) {
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSecondaryContainer)
            } else {
                Color.TRANSPARENT
            }
            val textColor = MaterialColors.getColor(
                b.root,
                if (selected) {
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            )
            b.filterOptionRoot.background = roundedRippleBackground(b.root, OPTION_CORNER_DP, fill)
            b.filterOptionLabel.text = option.label
            b.filterOptionLabel.setTextColor(textColor)
            b.filterOptionCheck.isVisible = selected
            b.filterOptionRoot.setOnClickListener {
                b.filterOptionRoot.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                onPick(option.key)
            }
            ViewCompat.setAccessibilityDelegate(b.filterOptionRoot, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            })
        }

        private fun bindMoreToggle(holder: MoreHolder) {
            val b = holder.binding
            val ctx = b.root.context
            b.filterMoreTitle.text = moreTitle
            b.filterMoreChevron.rotation = if (expanded) 180f else 0f
            ViewCompat.setStateDescription(
                b.filterMoreHeader,
                ctx.getString(if (expanded) R.string.genre_tag_expanded else R.string.genre_tag_collapsed),
            )
            b.filterMoreHeader.setOnClickListener {
                expanded = !expanded
                rows = buildRows()
                notifyDataSetChanged()
            }
        }

        private class OptionHolder(val binding: ItemFilterOptionBinding) : RecyclerView.ViewHolder(binding.root)
        private class MoreHolder(val binding: ItemFilterMoreToggleBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            private const val TYPE_OPTION = 0
            private const val TYPE_MORE = 1
        }
    }

    private class YearGridAdapter(
        private var years: List<Int>,
        private val selectedYear: Int?,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<YearGridAdapter.YearHolder>() {

        override fun getItemCount(): Int = years.size

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<Int>) {
            years = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): YearHolder =
            YearHolder(ItemYearCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: YearHolder, position: Int) {
            val year = years[position]
            val b = holder.binding
            val selected = year == selectedYear
            val fill = if (selected) {
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSecondaryContainer)
            } else {
                Color.TRANSPARENT
            }
            val stroke = if (selected) {
                null
            } else {
                MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOutlineVariant)
            }
            val textColor = MaterialColors.getColor(
                b.root,
                if (selected) {
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            )
            b.yearCellRoot.background = roundedRippleBackground(b.root, YEAR_CORNER_DP, fill, stroke)
            b.yearCellCheck.isVisible = selected
            b.yearCellLabel.text = year.toString()
            b.yearCellLabel.setTextColor(textColor)
            b.yearCellRoot.contentDescription = year.toString()
            b.yearCellRoot.setOnClickListener {
                b.yearCellRoot.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                onPick(year)
            }
            ViewCompat.setAccessibilityDelegate(b.yearCellRoot, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            })
        }

        class YearHolder(val binding: ItemYearCellBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private const val YEAR_COLUMNS = 4
    private const val OPTION_CORNER_DP = 12f
    private const val YEAR_CORNER_DP = 12f
}
