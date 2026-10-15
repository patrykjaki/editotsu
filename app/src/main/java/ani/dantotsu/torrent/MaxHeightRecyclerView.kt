package ani.dantotsu.torrent

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView with a working vertical cap (M7A G2 runtime P0).
 *
 * `android:maxHeight` is silently ignored by RecyclerView, so the manual
 * torrent sheet's file list grew unbounded inside a non-scrolling
 * container and pushed Play/Download/Cancel off-screen for torrents with
 * more than a handful of files. This caps the measured height at
 * [MAX_LIST_HEIGHT_DP] (the original author's ineffective `maxHeight`
 * value): short lists still wrap naturally, long lists scroll internally,
 * and the trailing action row always stays reachable. No behavior change
 * beyond measurement.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(
            (MAX_LIST_HEIGHT_DP * resources.displayMetrics.density).toInt(),
            MeasureSpec.AT_MOST
        )
        super.onMeasure(widthMeasureSpec, capped)
    }

    companion object {
        /** Matches the sheet layout's original (ineffective) maxHeight. */
        const val MAX_LIST_HEIGHT_DP = 320
    }
}
