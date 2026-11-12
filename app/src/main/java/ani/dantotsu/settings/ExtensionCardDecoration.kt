package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.settings.rows.SettingsCards
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.roundToInt

/**
 * Draws the extension lists as one settings-style card group: tonal fill, large corners
 * on the first and last row, small ones between, a 2dp gap. The shape follows the row's
 * position at draw time, so it stays right while rows are dragged, filtered or paged in.
 */
class ExtensionCardDecoration : RecyclerView.ItemDecoration() {
    private val shapes = HashMap<Int, MaterialShapeDrawable>()

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val d = parent.resources.displayMetrics.density
        val position = parent.getChildAdapterPosition(view)
        outRect.top = ((if (position == 0) FIRST_TOP_DP else GAP_DP) * d).roundToInt()
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.adapter?.itemCount ?: return
        val fill = ColorStateList.valueOf(SettingsCards.fill(parent))
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            // A row being dragged carries its own card (see lift).
            if (child.elevation > 0f) continue
            val shape = shape(parent, first = position == 0, last = position == count - 1)
            shape.fillColor = fill
            shape.alpha = (child.alpha * 255).roundToInt()
            shape.setBounds(
                (child.left + child.translationX).roundToInt(),
                (child.top + child.translationY).roundToInt(),
                (child.right + child.translationX).roundToInt(),
                (child.bottom + child.translationY).roundToInt(),
            )
            shape.draw(c)
        }
    }

    private fun shape(parent: RecyclerView, first: Boolean, last: Boolean): MaterialShapeDrawable {
        val key = (if (first) 1 else 0) or (if (last) 2 else 0)
        return shapes.getOrPut(key) {
            val d = parent.resources.displayMetrics.density
            val top = (if (first) OUTER_DP else INNER_DP) * d
            val bottom = (if (last) OUTER_DP else INNER_DP) * d
            MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(top).setTopRightCornerSize(top)
                    .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
                    .build()
            )
        }
    }

    companion object {
        /** Gives a row its own rounded card while it is dragged, so it covers the rows it passes. */
        fun lift(view: View) {
            val d = view.resources.displayMetrics.density
            view.background = MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(OUTER_DP * d).build()
            ).apply { fillColor = ColorStateList.valueOf(SettingsCards.fill(view)) }
            view.elevation = 8f * d
        }

        fun drop(view: View) {
            view.background = null
            view.elevation = 0f
            view.translationZ = 0f
        }

        // Same numbers as the settings cards.
        private const val OUTER_DP = 20f
        private const val INNER_DP = 4f
        private const val GAP_DP = 2f
        private const val FIRST_TOP_DP = 8f
    }
}
