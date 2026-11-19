package ani.dantotsu.settings

import android.view.HapticFeedbackConstants
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Long-press reordering on an installed-extensions list that also has pull-to-refresh.
 * Disables [SwipeRefreshLayout] while a drag is active so dragging the top row down moves
 * the row instead of pulling the refresh spinner, and re-enables it once the drag settles.
 */
internal object ExtensionReorder {
    fun attach(callback: ItemTouchHelper.Callback, list: RecyclerView, swipe: SwipeRefreshLayout) {
        val wrapped = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = callback.getMovementFlags(recyclerView, viewHolder)

            override fun isLongPressDragEnabled(): Boolean = true

            override fun isItemViewSwipeEnabled(): Boolean = callback.isItemViewSwipeEnabled

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = callback.onMove(recyclerView, viewHolder, target)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                callback.onSwiped(viewHolder, direction)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                when (actionState) {
                    ItemTouchHelper.ACTION_STATE_DRAG -> {
                        swipe.isEnabled = false
                        viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    ItemTouchHelper.ACTION_STATE_IDLE -> swipe.isEnabled = true
                }
                callback.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                swipe.isEnabled = true
                callback.clearView(recyclerView, viewHolder)
            }
        }
        ItemTouchHelper(wrapped).attachToRecyclerView(list)
    }
}
