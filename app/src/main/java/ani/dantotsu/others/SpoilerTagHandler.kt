package ani.dantotsu.others

import android.text.Spannable
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler

/**
 * AniList's html spoilers: `<span class="markdown-spoiler">`, `<div class="markdown-spoiler">`
 * and `<div rel="spoiler">`. Any other span or div is left alone.
 */
class SpoilerTagHandler : SimpleTagHandler() {
    override fun getSpans(configuration: MarkwonConfiguration, renderProps: RenderProps, tag: HtmlTag): Any? {
        val attrs = tag.attributes()
        if (!isSpoiler(attrs["class"], attrs["rel"])) return null
        val spoilerSpan = SpoilerPlugin.RedditSpoilerSpan()
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                spoilerSpan.setRevealed(true)
                if (widget is TextView && widget.text is Spannable) {
                    (widget.text as Spannable).removeSpan(this)
                }
                widget.postInvalidateOnAnimation()
            }

            override fun updateDrawState(ds: TextPaint) {}
        }
        return arrayOf(spoilerSpan, clickableSpan)
    }

    override fun supportedTags(): Collection<String> = setOf("span", "div")

    companion object {
        internal fun isSpoiler(cls: String?, rel: String?): Boolean =
            cls?.split(' ')?.contains("markdown-spoiler") == true || rel == "spoiler"
    }
}
