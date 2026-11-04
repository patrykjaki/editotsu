package ani.dantotsu.settings.rows

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSettingAdvancedBinding
import ani.dantotsu.databinding.ItemSettingHeaderBinding
import ani.dantotsu.databinding.ItemSettingRowBinding
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** Draws a screen's [ScreenItem]s. Values are read from prefs on every bind. */
class RowAdapter(
    private val host: SettingsHost,
    private val onToggleAdvanced: () -> Unit,
) : ListAdapter<ScreenItem, RecyclerView.ViewHolder>(Diff) {

    /** Row id to flash once when it is first bound (search result). */
    var highlightId: String? = null

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ScreenItem.Header -> TYPE_HEADER
        is ScreenItem.AdvancedToggle -> TYPE_ADVANCED
        is ScreenItem.RowItem -> (item.row.kind as? Kind.Custom)?.layout ?: TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            TYPE_HEADER -> ItemSettingHeaderBinding.inflate(inflater, parent, false).root
            TYPE_ADVANCED -> ItemSettingAdvancedBinding.inflate(inflater, parent, false).root
            TYPE_ROW -> ItemSettingRowBinding.inflate(inflater, parent, false).root
            else -> inflater.inflate(viewType, parent, false)
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ScreenItem.Header ->
                ItemSettingHeaderBinding.bind(holder.itemView).headerTitle.setText(item.title)
            is ScreenItem.AdvancedToggle -> bindAdvanced(ItemSettingAdvancedBinding.bind(holder.itemView), item)
            is ScreenItem.RowItem -> {
                val row = item.row
                val kind = row.kind
                if (kind is Kind.Custom) {
                    kind.bind(host, holder.itemView)
                    (holder.itemView as? com.google.android.material.card.MaterialCardView)
                        ?.let(SettingsCards::applyToCard)
                } else {
                    bindRow(ItemSettingRowBinding.bind(holder.itemView), row)
                    // Neighbours decide the corners; rows rebind on every refresh (see Diff),
                    // so a row that becomes first or last after a visibility change is redrawn.
                    val list = currentList
                    SettingsCards.apply(
                        holder.itemView,
                        first = !SettingsCards.isCardRow(list.getOrNull(position - 1)),
                        last = !SettingsCards.isCardRow(list.getOrNull(position + 1)),
                    )
                }
                if (row.id == highlightId) {
                    highlightId = null
                    flash(holder.itemView)
                }
            }
        }
    }

    private fun bindAdvanced(b: ItemSettingAdvancedBinding, item: ScreenItem.AdvancedToggle) {
        val context = b.root.context
        val label = context.getString(
            if (item.expanded) R.string.settings_advanced_hide else R.string.settings_advanced
        )
        b.advancedText.text = label
        b.advancedToggle.contentDescription = label
        // Turn the chevron when the state changes on the same view; set it outright on first bind.
        val target = if (item.expanded) 180f else 0f
        val shown = b.advancedChevron.getTag(R.id.advancedChevron) as? Boolean
        b.advancedChevron.animate().cancel()
        if (shown != null && shown != item.expanded) {
            // ViewPropertyAnimator follows the system animator duration scale.
            b.advancedChevron.animate().rotation(target).setDuration(200).start()
        } else {
            b.advancedChevron.rotation = target
        }
        b.advancedChevron.setTag(R.id.advancedChevron, item.expanded)
        ViewCompat.setStateDescription(
            b.advancedToggle,
            context.getString(if (item.expanded) R.string.expanded else R.string.collapsed)
        )
        b.advancedToggle.setOnClickListener { onToggleAdvanced() }
        SettingsCards.apply(b.advancedToggle, first = true, last = true, topMarginDp = 24)
    }

    private fun bindRow(b: ItemSettingRowBinding, row: Row) {
        // The host activity, so value lambdas can reach screen state.
        val context: Context = host.activity
        val kind = row.kind
        val enabled = row.enabled()

        b.rowTitle.setText(row.title)
        if (row.icon != null) {
            b.rowIcon.setImageResource(row.icon)
            b.rowIcon.visibility = View.VISIBLE
        } else {
            b.rowIcon.visibility = View.INVISIBLE
        }
        setSummary(b, context, row)

        // Reset every optional part; each kind turns on what it uses.
        b.rowSwitch.isVisible = false
        b.rowChevron.isVisible = false
        b.rowValue.isVisible = false
        b.rowMinus.isVisible = false
        b.rowPlus.isVisible = false
        b.rowSlider.isVisible = false
        b.rowSegments.isVisible = false
        b.rowSlider.clearOnChangeListeners()
        b.rowSegments.clearOnButtonCheckedListeners()
        b.root.setOnClickListener(null)
        b.root.isClickable = false
        ViewCompat.setAccessibilityDelegate(b.root, null)

        val rowView = object : RowView {
            override fun setValue(text: CharSequence?) {
                b.rowSummary.text = text
                b.rowSummary.isVisible = !text.isNullOrBlank()
            }
            override val extra: ViewGroup get() = b.rowExtra
        }

        when (kind) {
            is Kind.Switch -> {
                b.rowSwitch.isVisible = true
                b.rowSwitch.isChecked = kind.get()
                b.root.setOnClickListener {
                    val now = !b.rowSwitch.isChecked
                    b.rowSwitch.isChecked = now
                    kind.set(host, now)
                    b.root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
                ViewCompat.setAccessibilityDelegate(b.root, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Switch::class.java.name
                        info.isCheckable = true
                        info.isChecked = b.rowSwitch.isChecked
                    }
                })
            }

            is Kind.Choice -> b.root.setOnClickListener {
                val labels = kind.labels(context)
                context.customAlertDialog().apply {
                    setTitle(context.getString(row.title))
                    singleChoiceItems(labels.toTypedArray(), kind.get()) { which ->
                        kind.set(host, which)
                        setSummary(b, context, row)
                    }
                    show()
                }
            }

            is Kind.Slider -> {
                b.rowSlider.isVisible = true
                b.rowValue.isVisible = true
                b.rowSlider.valueFrom = kind.from
                b.rowSlider.valueTo = kind.to
                b.rowSlider.stepSize = kind.step
                val value = snap(kind.get(), kind.from, kind.to, kind.step)
                b.rowSlider.value = value
                b.rowValue.text = kind.format(context, value)
                b.rowSlider.contentDescription = context.getString(row.title)
                // A drag saves once, on release (some sliders restart the app); keyboard
                // and accessibility steps save at once.
                var dragging = false
                b.rowSlider.clearOnSliderTouchListeners()
                b.rowSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        dragging = true
                    }
                    override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        dragging = false
                        if (slider.value != kind.get()) kind.set(host, slider.value)
                    }
                })
                b.rowSlider.addOnChangeListener { _, v, fromUser ->
                    b.rowValue.text = kind.format(context, v)
                    if (fromUser && !dragging) kind.set(host, v)
                }
            }

            is Kind.Stepper -> {
                b.rowMinus.isVisible = true
                b.rowPlus.isVisible = true
                b.rowValue.isVisible = true
                fun show() {
                    val v = kind.get()
                    b.rowValue.text = kind.format(context, v)
                    b.rowMinus.isEnabled = enabled && v > kind.from
                    b.rowPlus.isEnabled = enabled && v < kind.to
                    b.rowMinus.alpha = if (b.rowMinus.isEnabled) 1f else DISABLED_ALPHA
                    b.rowPlus.alpha = if (b.rowPlus.isEnabled) 1f else DISABLED_ALPHA
                }
                fun step(by: Float) {
                    val next = snap(kind.get() + by, kind.from, kind.to, kind.step)
                    kind.set(host, next)
                    show()
                }
                b.rowMinus.setOnClickListener { step(-kind.step) }
                b.rowPlus.setOnClickListener { step(kind.step) }
                show()
            }

            is Kind.Segmented -> {
                b.rowSegments.isVisible = true
                b.rowSegments.removeAllViews()
                kind.options.forEachIndexed { i, option ->
                    val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        id = View.generateViewId()
                        setIconResource(option.icon)
                        iconPadding = 0
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        contentDescription = context.getString(option.label)
                        isCheckable = true
                        minWidth = 0
                        minimumWidth = 0
                        val pad = (12 * context.resources.displayMetrics.density).roundToInt()
                        setPadding(pad, paddingTop, pad, paddingBottom)
                        tag = i
                    }
                    b.rowSegments.addView(button)
                    if (i == kind.get()) b.rowSegments.check(button.id)
                }
                b.rowSegments.addOnButtonCheckedListener { group, checkedId, isChecked ->
                    if (!isChecked) return@addOnButtonCheckedListener
                    val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@addOnButtonCheckedListener
                    if (index != kind.get()) {
                        kind.set(host, index)
                        setSummary(b, context, row)
                    }
                }
            }

            is Kind.Action -> {
                b.rowChevron.isVisible = kind.opensScreen
                b.root.setOnClickListener { kind.onClick(host, rowView) }
            }

            is Kind.Custom -> Unit
        }

        val longClick = row.onLongClick
        if (longClick != null) {
            b.root.setOnLongClickListener { longClick(host); true }
        } else {
            b.root.setOnLongClickListener(null)
            b.root.isLongClickable = false
        }

        val extra = row.extra
        b.rowExtra.removeAllViews()
        b.rowExtra.isVisible = extra != null
        extra?.invoke(host, b.rowExtra)

        val trailing = row.trailing
        b.rowTrailing.removeAllViews()
        b.rowTrailing.isVisible = trailing != null
        trailing?.invoke(host, b.rowTrailing)

        setEnabled(b, enabled)
    }

    private fun setSummary(b: ItemSettingRowBinding, context: Context, row: Row) {
        // Sliders and steppers show their value on the right; the second line keeps the summary.
        val value = when (row.kind) {
            is Kind.Slider, is Kind.Stepper -> null
            else -> row.kind.value(context)
        }
        val text = value ?: row.summary?.let(context::getString)
        b.rowSummary.text = text
        b.rowSummary.isVisible = !text.isNullOrBlank()
    }

    private fun setEnabled(b: ItemSettingRowBinding, enabled: Boolean) {
        b.root.isEnabled = enabled
        b.rowSlider.isEnabled = enabled
        for (i in 0 until b.rowSegments.childCount) b.rowSegments.getChildAt(i).isEnabled = enabled
        val alpha = if (enabled) 1f else DISABLED_ALPHA
        b.rowTitle.alpha = alpha
        b.rowIcon.alpha = alpha
        b.rowSwitch.alpha = alpha
        b.rowSlider.alpha = alpha
        b.rowSegments.alpha = alpha
        b.rowSummary.alpha = alpha
    }

    private fun flash(view: View) {
        view.alpha = 0.3f
        view.animate().alpha(1f).setDuration(600).start()
        view.isPressed = true
        view.postDelayed({ view.isPressed = false }, 600)
    }

    private object Diff : DiffUtil.ItemCallback<ScreenItem>() {
        override fun areItemsTheSame(old: ScreenItem, new: ScreenItem) = old.key == new.key
        override fun areContentsTheSame(old: ScreenItem, new: ScreenItem) = when {
            // Rows read their value on bind, so always rebind them on refresh.
            old is ScreenItem.RowItem || new is ScreenItem.RowItem -> false
            else -> old == new
        }
    }

    companion object {
        const val TYPE_HEADER = 1
        const val TYPE_ROW = 2
        const val TYPE_ADVANCED = 3
        private const val DISABLED_ALPHA = 0.38f

        /** Clamp to the range and to a whole step, as the Slider requires. */
        fun snap(value: Float, from: Float, to: Float, step: Float): Float {
            if (step <= 0f) return value.coerceIn(from, to)
            val steps = ((value - from) / step).roundToInt()
            return (from + steps * step).coerceIn(from, to)
        }
    }
}
