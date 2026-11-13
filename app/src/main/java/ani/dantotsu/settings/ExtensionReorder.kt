package ani.dantotsu.settings

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import ani.dantotsu.R

/**
 * Reordering on an installed-extensions list that also has pull-to-refresh: a touch on a row's
 * handle starts the drag at once, and the refresh gesture is off until the finger lifts, so
 * dragging the top row down moves the row instead of pulling the spinner.
 */
internal object ExtensionReorder {
    fun attach(callback: ItemTouchHelper.Callback, list: RecyclerView, swipe: SwipeRefreshLayout) {
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(list)
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val row = rv.findChildViewUnder(e.x, e.y) ?: return false
                        val handle = row.findViewById<View>(R.id.extensionPinImageView) ?: return false
                        if (handle.visibility == View.VISIBLE && e.x - row.left in handle.left.toFloat()..handle.right.toFloat()) {
                            swipe.isEnabled = false
                            helper.startDrag(rv.getChildViewHolder(row))
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipe.isEnabled = true
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    swipe.isEnabled = true
                }
            }
        })
    }
}
