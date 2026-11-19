package ani.dantotsu.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Pill-rounded storage bar:
 * - Track: device free space (`?attr/colorSecondaryContainer`)
 * - Used space: Editotsu downloads + other phone storage used (`?attr/colorPrimary`),
 *   separated by a 2dp notch when both are non-zero.
 */
class SegmentedStorageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var downloadsFraction: Float = 0f
    private var otherUsedFraction: Float = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val downloadsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val otherUsedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()

    private val density = resources.displayMetrics.density
    private val minSliverPx = 4f * density
    private val segmentGapPx = 2f * density

    init {
        refreshColors()
    }

    private fun refreshColors() {
        trackPaint.color = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondaryContainer,
            0,
        )
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0)
        downloadsPaint.color = primary
        otherUsedPaint.color = primary
    }

    fun setSegments(downloadsFraction: Float, otherUsedFraction: Float) {
        val d = downloadsFraction.coerceIn(0f, 1f)
        val o = otherUsedFraction.coerceIn(0f, 1f - d)
        if (this.downloadsFraction != d || this.otherUsedFraction != o) {
            this.downloadsFraction = d
            this.otherUsedFraction = o
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = h / 2f
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)

        canvas.drawRect(0f, 0f, w, h, trackPaint)

        val rawDownloadsPx = w * downloadsFraction
        val downloadsPx = if (downloadsFraction > 0f) {
            rawDownloadsPx.coerceAtLeast(minSliverPx).coerceAtMost(w)
        } else {
            0f
        }
        val totalUsedPx = (w * (downloadsFraction + otherUsedFraction)).coerceIn(downloadsPx, w)

        if (downloadsPx > 0f) {
            canvas.drawRect(0f, 0f, downloadsPx, h, downloadsPaint)
        }
        val otherStart = if (downloadsPx > 0f) downloadsPx + segmentGapPx else 0f
        if (otherUsedFraction > 0f && totalUsedPx > otherStart) {
            canvas.drawRect(otherStart, 0f, totalUsedPx, h, otherUsedPaint)
        }

        canvas.restoreToCount(save)
    }
}
