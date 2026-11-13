package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet

/**
 * Makes a source field open the app's choice sheet instead of its own drop-down. The field
 * keeps its adapter and item click listener, so the screens that own it need no other change:
 * a pick sets the text and runs the listener with the picked position.
 */
internal object SourcePicker {
    private const val OWN_ICON_SCALE = 1.15f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        row: View,
        iconView: ImageView,
        field: AutoCompleteTextView,
        title: CharSequence,
        /** The icon of the source at an adapter position, or null for the stock one. */
        icon: (Int) -> Drawable? = { null },
        onOpen: () -> Unit = {},
    ) {
        val quiet = ColorStateList.valueOf(
            field.context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        val inset = (8 * field.resources.displayMetrics.density).toInt()
        val tile = iconView.parent as? androidx.cardview.widget.CardView
        val tileColor = field.context.getThemeColor(com.google.android.material.R.attr.colorSurface)
        fun names() = field.adapter?.let { a -> (0 until a.count).map { a.getItem(it).toString() } }.orEmpty()
        fun showIcon() {
            val own = icon(names().indexOf(field.text.toString()))
            if (own != null) {
                // an extension's own icon fills the tile; the stock glyph sits inset and tinted
                // launcher icons carry their own shape and margin: no tile behind them, drawn a little larger
                tile?.setCardBackgroundColor(Color.TRANSPARENT)
                iconView.setPadding(0, 0, 0, 0)
                iconView.scaleX = OWN_ICON_SCALE
                iconView.scaleY = OWN_ICON_SCALE
                iconView.imageTintList = null
                iconView.setImageDrawable(own)
            } else {
                tile?.setCardBackgroundColor(tileColor)
                iconView.scaleX = 1f
                iconView.scaleY = 1f
                iconView.setPadding(inset, inset, inset, inset)
                iconView.imageTintList = quiet
                iconView.setImageResource(R.drawable.ic_round_source_24)
            }
        }
        showIcon()
        // the name is also set from code (next source when one has no match): keep the icon with it
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = showIcon()
        })
        attachSheet(row, field, { title }, onOpen)
    }

    /** The sheet half of [attach], for a field with no icon of its own (language). */
    @SuppressLint("ClickableViewAccessibility")
    fun attachSheet(row: View, field: AutoCompleteTextView, title: () -> CharSequence, onOpen: () -> Unit = {}) {
        row.setOnClickListener {
            val names = field.adapter?.let { a -> (0 until a.count).map { a.getItem(it).toString() } }.orEmpty()
            if (names.isEmpty()) return@setOnClickListener
            onOpen()
            ChoiceSheet.single(
                field.context,
                title(),
                names.map { ChoiceOption(it) },
                // the owner may keep the position in the tag: names can repeat
                (field.tag as? Int)?.takeIf { it in names.indices } ?: names.indexOf(field.text.toString()),
            ) { picked ->
                if (field.tag is Int) field.tag = picked
                field.setText(names[picked], false)
                field.onItemClickListener?.onItemClick(null, field, picked, picked.toLong())
            }
        }
        // the field would open its own drop-down on touch; hand the touch to the row
        field.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) row.performClick()
            true
        }
    }

    /**
     * What to call an extension's sources in the picker under the source card: their languages
     * when those tell them apart, else their own names, numbered where even those repeat.
     */
    fun variantLabels(languages: List<String>, names: List<String>): List<String> {
        if (languages.distinct().size == languages.size) return languages
        if (names.size == languages.size && names.distinct().size == names.size) return names
        return languages.indices.map { "${names.getOrNull(it) ?: languages[it]} ${it + 1}" }
    }
}
