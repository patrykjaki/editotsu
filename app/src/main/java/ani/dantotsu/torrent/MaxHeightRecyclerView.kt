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
 *
 * The cap never widens the parent's constraint: an AT_MOST/EXACTLY spec
 * smaller than the cap (landscape, large font) is kept, so the sheet's
 * weighted layout can shrink the list below 320dp and leave room for the
 * action row instead of the list claiming 320dp unconditionally.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (MAX_LIST_HEIGHT_DP * resources.displayMetrics.density).toInt()
        super.onMeasure(widthMeasureSpec, cappedHeightSpec(heightMeasureSpec, cap))
    }

    companion object {
        /** Matches the sheet layout's original (ineffective) maxHeight. */
        const val MAX_LIST_HEIGHT_DP = 320

        // MeasureSpec layout: top 2 bits = mode, low 30 bits = size. Bit math on
        // the compile-time constants (instead of MeasureSpec.getSize/getMode/
        // makeMeasureSpec) keeps this pure and JVM-unit-testable.
        private const val MODE_MASK = 0x3 shl 30

        /** Caps [spec] at [capPx] without ever loosening a tighter parent constraint. */
        internal fun cappedHeightSpec(spec: Int, capPx: Int): Int {
            val size = spec and MODE_MASK.inv()
            return when (spec and MODE_MASK) {
                MeasureSpec.EXACTLY -> minOf(size, capPx) or MeasureSpec.EXACTLY
                MeasureSpec.AT_MOST -> minOf(size, capPx) or MeasureSpec.AT_MOST
                else -> (capPx and MODE_MASK.inv()) or MeasureSpec.AT_MOST
            }
        }
    }
}
