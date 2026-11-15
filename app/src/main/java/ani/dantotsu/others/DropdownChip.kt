package ani.dantotsu.others

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import com.google.android.material.chip.Chip

/**
 * Sets a dropdown chip's label with the arrow drawn after it. The arrow lives in the text, not in the
 * chip's close-icon slot, so the chip has one touch target and one ripple, like Google's own filter rows.
 * The plain label stays the content description. Sized like the Play Store's chips (measured): a 24dp
 * arrow, whose own whitespace makes the visible gaps about 15dp before it and 13dp after it.
 */
fun Chip.setDropdownText(label: CharSequence) {
    val density = resources.displayMetrics.density
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_round_arrow_drop_down_24)!!.mutate()
    text = SpannableStringBuilder(label).append(
        "￼",
        TrailingIconSpan(icon, (24 * density).toInt(), (6 * density).toInt()),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    contentDescription = label
}

/** An icon after the label, drawn in the label's current colour so it follows the selected state. */
private class TrailingIconSpan(private val icon: Drawable, private val size: Int, private val gap: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = gap + size

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        icon.setBounds(0, 0, size, size)
        icon.setTint(paint.color)
        canvas.save()
        canvas.translate(x + gap, (top + bottom - size) / 2f)
        icon.draw(canvas)
        canvas.restore()
    }
}
