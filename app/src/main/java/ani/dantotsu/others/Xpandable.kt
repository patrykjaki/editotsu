package ani.dantotsu.others

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.RotateDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.children
import ani.dantotsu.R


class Xpandable @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private var expanded: Boolean = false
    private var listeners: ArrayList<OnChangeListener> = arrayListOf()

    init {
        context.withStyledAttributes(attrs, R.styleable.Xpandable) {
            expanded = getBoolean(R.styleable.Xpandable_isExpanded, expanded)
        }
    }

    /** The header's end arrow, wrapped so it can turn: level 0 points down, 10000 points up. */
    private var arrow: RotateDrawable? = null

    /**
     * Wraps a TextView header's end drawable once. Animated through the drawable level, so the
     * header layout stays as it is in XML.
     */
    private fun setUpArrow() {
        if (arrow != null) return
        val header = getChildAt(0) as? TextView ?: return
        val d = header.compoundDrawablesRelative
        val end = d[2] ?: return
        val rotate = RotateDrawable().apply {
            drawable = end
            fromDegrees = 0f
            toDegrees = 180f
            setBounds(0, 0, end.intrinsicWidth, end.intrinsicHeight)
            level = if (expanded) 10000 else 0
        }
        header.setCompoundDrawablesRelative(d[0], d[1], rotate, d[3])
        arrow = rotate
    }

    /** Turns the arrow over 200 ms; ObjectAnimator follows the system animator duration scale. */
    private fun turnArrow(expanding: Boolean) {
        val a = arrow ?: return
        ObjectAnimator.ofInt(a, "level", a.level, if (expanding) 10000 else 0).setDuration(200).start()
    }

    private fun describeHeader(expanded: Boolean) {
        val header = getChildAt(0) ?: return
        ViewCompat.setStateDescription(
            header, context.getString(if (expanded) R.string.expanded else R.string.collapsed)
        )
    }

    override fun onAttachedToWindow() {
        setUpArrow()
        describeHeader(expanded)

        getChildAt(0)!!.setOnClickListener {
            val expanding = !expanded
            if (expanded) hideAll() else showAll()
            turnArrow(expanding)
            describeHeader(expanding)
            postDelayed({
                expanded = !expanded
            }, 300)
        }

        if (!expanded) children.forEach {
            if (it != getChildAt(0)) {
                it.visibility = GONE
            }
        }
        super.onAttachedToWindow()
    }

    private fun hideAll() {
        children.forEach {
            if (it != getChildAt(0)) {
                ObjectAnimator.ofFloat(it, "scaleY", 1f, 0.5f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "translationY", 0f, -32f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "alpha", 1f, 0f).setDuration(200).start()
                it.postDelayed({
                    it.visibility = GONE
                }, 300)
            }
        }
        postDelayed({
            listeners.forEach {
                it.onRetract()
            }
        }, 300)
    }

    companion object {
        /**
         * Opt-out tag for expand visibility restore. Children carrying this
         * tag keep whatever visibility their owner set (e.g. conditionally
         * GONE settings rows) instead of being forced VISIBLE on expand.
         * Collapse ([hideAll]) still hides everything.
         */
        const val KEEP_HIDDEN_TAG = "xpand_keep_hidden"
    }

    private fun showAll() {
        children.forEach {
            if (it != getChildAt(0) && it.tag != KEEP_HIDDEN_TAG) {
                it.visibility = VISIBLE
                ObjectAnimator.ofFloat(it, "scaleY", 0.5f, 1f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "translationY", -32f, 0f).setDuration(200).start()
                ObjectAnimator.ofFloat(it, "alpha", 0f, 1f).setDuration(200).start()
            }
        }
        postDelayed({
            listeners.forEach {
                it.onExpand()
            }
        }, 300)
    }

    @Suppress("unused")
    fun addOnChangeListener(listener: OnChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnChangeListener) {
        listeners.remove(listener)
    }

    interface OnChangeListener {
        fun onExpand()
        fun onRetract()
    }

}