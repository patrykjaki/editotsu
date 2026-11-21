package ani.dantotsu.settings

import android.content.Context
import android.content.ContextWrapper
import android.view.HapticFeedbackConstants
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Long-press reordering on an installed-extensions list that also has pull-to-refresh
 * and horizontal tab swiping. Disables [SwipeRefreshLayout] and pager swiping while a
 * drag is active so dragging moves the row cleanly, and re-enables both once settled.
 */
internal object ExtensionReorder {
    fun attach(callback: ItemTouchHelper.Callback, list: RecyclerView, swipe: SwipeRefreshLayout) {
        var dragging = false
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
                        dragging = true
                        swipe.isEnabled = false
                        list.context.extensionsActivity()?.setReorderDragging(true)
                        viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    ItemTouchHelper.ACTION_STATE_IDLE -> {
                        swipe.isEnabled = true
                        if (dragging) {
                            dragging = false
                            list.context.extensionsActivity()?.setReorderDragging(false)
                        }
                    }
                }
                callback.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                swipe.isEnabled = true
                if (dragging) {
                    dragging = false
                    list.context.extensionsActivity()?.setReorderDragging(false)
                }
                callback.clearView(recyclerView, viewHolder)
            }
        }
        ItemTouchHelper(wrapped).attachToRecyclerView(list)
    }

    private fun Context.extensionsActivity(): ExtensionsActivity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is ExtensionsActivity) return c
            c = c.baseContext
        }
        return null
    }
}
