package ani.dantotsu.media

import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import ani.dantotsu.InputFilterMinMax
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.ScoreFormats
import ani.dantotsu.connections.anilist.api.ScoreFormat
import ani.dantotsu.connections.anilist.userScoreFormat
import com.google.android.material.textfield.TextInputLayout

/**
 * Binds the list editor's score field to the user's AniList score format.
 * Numbers for 100/10/decimal/stars, a dropdown for smileys.
 */
class ScoreFieldBinder(
    private val field: AutoCompleteTextView,
    private val layout: TextInputLayout,
    private val initialRaw: Int,
    private val format: ScoreFormat = userScoreFormat(),
) {
    fun bind() {
        layout.suffixText = when (format) {
            ScoreFormat.POINT_100 -> " / 100"
            ScoreFormat.POINT_10_DECIMAL, ScoreFormat.POINT_10 -> " / 10"
            ScoreFormat.POINT_5 -> " / 5"
            ScoreFormat.POINT_3 -> null
        }
        val text = ScoreFormats.display(initialRaw, format)
        if (format == ScoreFormat.POINT_3) {
            field.inputType = InputType.TYPE_NULL
            field.filters = arrayOf()
            val none = field.context.getString(R.string.none)
            val options = listOf(none) + ScoreFormats.SMILEYS.drop(1)
            field.setAdapter(NoFilterAdapter(field, options))
            field.setText(text, false)
            field.setOnClickListener { field.showDropDown() }
            field.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) field.setText("", false)
            }
        } else {
            field.inputType = if (ScoreFormats.allowsDecimal(format)) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            field.filters = arrayOf()
            field.setText(text, false)
            field.filters = arrayOf(
                InputFilterMinMax(0.0, ScoreFormats.max(format)),
                InputFilter.LengthFilter(ScoreFormats.inputLength(format))
            )
        }
    }

    /** Always lists every option; a plain adapter would filter by the current text. */
    private class NoFilterAdapter(field: AutoCompleteTextView, private val items: List<String>) :
        ArrayAdapter<String>(field.context, R.layout.item_dropdown, items) {
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
                notifyDataSetChanged()
        }
    }

    /** Raw score to send to AniList, or null when the user didn't change it. */
    fun rawToSave(): Int? = ScoreFormats.editedRaw(initialRaw, field.text.toString(), format)

    /** Raw score to mirror to MAL: the edited one, else the stored one if scored. */
    fun rawForMal(): Int? = rawToSave() ?: initialRaw.takeIf { it > 0 }
}
