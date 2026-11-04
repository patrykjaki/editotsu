package ani.dantotsu.animethemes

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Grouped theme rows (Themes tab, Themes screen): one card split by thin gaps, large
 * corners on the first and last row, rounded inner corners so the rows read as one card.
 */
object ThemeRowShape {
    private const val OUTER_DP = 20f
    private const val INNER_DP = 8f

    fun apply(view: View, first: Boolean, last: Boolean) {
        val d = view.resources.displayMetrics.density
        val top = (if (first) OUTER_DP else INNER_DP) * d
        val bottom = (if (last) OUTER_DP else INNER_DP) * d
        val model = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(top).setTopRightCornerSize(top)
            .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
            .build()
        view.background = RippleDrawable(
            ColorStateList.valueOf(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorControlHighlight)),
            MaterialShapeDrawable(model).apply {
                fillColor = ColorStateList.valueOf(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceVariant))
            },
            MaterialShapeDrawable(model),
        )
        view.clipToOutline = true
    }
}
