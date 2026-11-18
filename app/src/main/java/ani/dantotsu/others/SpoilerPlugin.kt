package ani.dantotsu.others

import android.graphics.Color
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.utils.ColorUtils
import java.util.regex.Pattern

class SpoilerPlugin(private val anilist: Boolean = false) : AbstractMarkwonPlugin() {
    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        val spannable = markdown as? Spannable ?: return
        applySpoilerSpans(spannable, ARE)
        if (!anilist) applySpoilerSpans(spannable, RE)
    }

    class RedditSpoilerSpan : CharacterStyle() {
        var revealed = false
            private set
        override fun updateDrawState(tp: TextPaint) {
            if (!revealed) {
                // use the same text color
                tp.bgColor = Color.DKGRAY
                tp.color = Color.DKGRAY
            } else {
                // for example keep a bit of black background to remind that it is a spoiler
                tp.bgColor = ColorUtils.applyAlpha(Color.DKGRAY, 25)
            }
        }

        fun setRevealed(revealed: Boolean) {
            this.revealed = revealed
        }
    }

    // we also could make text size smaller (but then MetricAffectingSpan should be used)
    private class HideSpoilerSyntaxSpan : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            // set transparent color
            tp.color = 0
        }
    }

    companion object {
        // DOTALL: a spoiler may run over several lines or paragraphs.
        internal val RE: Pattern = Pattern.compile("\\|\\|.+?\\|\\|", Pattern.DOTALL)
        // ~!text!~ or ~!{label}text!~ (labelled spoilers): the label stays readable.
        internal val ARE: Pattern = Pattern.compile("~!(?:\\{([^}\r\n<>]*)\\})?(.+?)!~", Pattern.DOTALL)

        /** Hidden range of each spoiler and the syntax around it, for [applySpoilerSpans] and tests. */
        internal data class SpoilerRange(val start: Int, val end: Int, val hidden: List<IntRange>)

        internal fun spoilerRanges(text: String, regex: Pattern): List<SpoilerRange> {
            val out = mutableListOf<SpoilerRange>()
            val m = regex.matcher(text)
            while (m.find()) {
                val s = m.start()
                val e = m.end()
                val labelled = m.groupCount() >= 1 && m.group(1) != null
                // with a label: hide "~!{" and "}", keep the label, cover only what follows it
                val bodyStart = if (labelled) m.end(1) + 1 else s
                val hidden = if (labelled) listOf(s until m.start(1), m.end(1) until m.end(1) + 1, e - 2 until e)
                else listOf(s until s + 2, e - 2 until e)
                out += SpoilerRange(bodyStart, e, hidden)
            }
            return out
        }

        private fun applySpoilerSpans(spannable: Spannable, regex: Pattern = RE) {
            for (r in spoilerRanges(spannable.toString(), regex)) {
                val spoilerSpan = RedditSpoilerSpan()
                val clickableSpan: ClickableSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        spoilerSpan.setRevealed(true)
                        if (widget is TextView && widget.text is Spannable) {
                            (widget.text as Spannable).removeSpan(this)
                        }
                        widget.postInvalidateOnAnimation()
                    }

                    override fun updateDrawState(ds: TextPaint) {}
                }
                spannable.setSpan(spoilerSpan, r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(clickableSpan, r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                r.hidden.forEach {
                    spannable.setSpan(HideSpoilerSyntaxSpan(), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }
}
