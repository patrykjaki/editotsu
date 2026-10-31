package ani.dantotsu.media.anime.selector

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * One fixed line of tags. Children are tags in priority order; whatever
 * does not fit is hidden and a "+N" chip ([overflowView]) is shown at
 * the end instead. Nothing wraps and nothing scrolls sideways. The fit
 * itself is the pure [TagLineBudget].
 */
class TagLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Shown as "+N" when tags overflow; set by the binder, not a tag itself. */
    var overflowView: TextView? = null
        set(value) {
            field?.let { removeView(it) }
            field = value
            if (value != null) addView(value)
        }

    var gapPx: Int = (4 * resources.displayMetrics.density).toInt()

    /** Number of tags visible after the last measure. */
    var visibleCount: Int = 0
        private set

    private fun tags(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it !== overflowView }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val tags = tags()
        tags.forEach { it.measure(unspecified, unspecified) }
        val widths = tags.map { it.measuredWidth }
        val overflow = overflowView
        val plusWidth: (Int) -> Int = { n ->
            if (overflow == null) 0 else {
                overflow.text = "+$n"
                overflow.measure(unspecified, unspecified)
                overflow.measuredWidth
            }
        }
        val fitsAll = widths.sum() + gapPx * (widths.size - 1).coerceAtLeast(0) <= available
        visibleCount = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || fitsAll) {
            widths.size
        } else {
            TagLineBudget.fit(widths, gapPx, available, plusWidth)
        }
        val hidden = widths.size - visibleCount
        if (overflow != null) {
            if (hidden > 0) {
                overflow.text = "+$hidden"
                overflow.visibility = View.VISIBLE
                overflow.measure(unspecified, unspecified)
            } else {
                overflow.visibility = View.GONE
            }
        }
        var used = 0
        var height = 0
        tags.take(visibleCount).forEachIndexed { i, v ->
            if (i > 0) used += gapPx
            used += v.measuredWidth
            height = maxOf(height, v.measuredHeight)
        }
        if (overflow != null && hidden > 0) {
            used += gapPx + overflow.measuredWidth
            height = maxOf(height, overflow.measuredHeight)
        }
        val width = resolveSize(used + paddingLeft + paddingRight, widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft
        val innerHeight = b - t - paddingTop - paddingBottom
        fun place(v: View) {
            val top = paddingTop + (innerHeight - v.measuredHeight) / 2
            v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
            x += v.measuredWidth + gapPx
        }
        tags().forEachIndexed { i, v -> if (i < visibleCount) place(v) else v.layout(0, 0, 0, 0) }
        overflowView?.let { if (it.visibility == View.VISIBLE) place(it) }
    }
}
