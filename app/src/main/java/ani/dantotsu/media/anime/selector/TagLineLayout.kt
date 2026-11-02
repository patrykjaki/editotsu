package ani.dantotsu.media.anime.selector

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * One fixed line of tags. Children are tags in priority order; whatever
 * does not fit is hidden and a "+N" chip ([overflowView]) is shown at
 * the end instead. Nothing scrolls sideways. The fit itself is the pure
 * [TagLineBudget]. With [expanded] every tag shows, wrapping onto more lines.
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

    /** All tags, wrapped onto as many lines as needed (the "+N" chip was tapped). */
    var expanded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** true when some tags are hidden behind "+N" (after the last measure) */
    val hasHidden: Boolean get() = !expanded && visibleCount < tags().size

    private var wrapped: List<IntArray> = emptyList() // x, y per tag when expanded

    /** Number of tags visible after the last measure. */
    var visibleCount: Int = 0
        private set

    private fun tags(): List<View> = (0 until childCount).map { getChildAt(it) }.filter { it !== overflowView }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val tags = tags()
        tags.forEach { it.measure(unspecified, unspecified) }
        if (expanded && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            overflowView?.visibility = View.GONE
            visibleCount = tags.size
            val pos = ArrayList<IntArray>(tags.size)
            var x = 0
            var y = 0
            var lineH = 0
            for (v in tags) {
                if (x > 0 && x + v.measuredWidth > available) {
                    x = 0
                    y += lineH + gapPx
                    lineH = 0
                }
                pos += intArrayOf(x, y)
                x += v.measuredWidth + gapPx
                lineH = maxOf(lineH, v.measuredHeight)
            }
            wrapped = pos
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec),
            )
            return
        }
        wrapped = emptyList()
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
        if (wrapped.isNotEmpty()) {
            tags().forEachIndexed { i, v ->
                val p = wrapped.getOrNull(i) ?: return@forEachIndexed
                val x = paddingLeft + p[0]
                val y = paddingTop + p[1]
                v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            }
            return
        }
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
