package ani.dantotsu.others

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import ani.dantotsu.getThemeColor

/**
 * Placeholder shown while a source's episode or chapter list loads: episode cards with a
 * thumbnail block and text bars, and a highlight sweeping across them. Drawn rather than
 * inflated so it costs one view.
 */
class EpisodeSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val cardColor = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    private val ink = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
    private val blockColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 26), cardColor)
    private val shineColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 8), cardColor)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blockColor }
    private val shift = Matrix()
    private var shader: LinearGradient? = null
    private var sweep = 0f
    private val animator = ValueAnimator.ofFloat(-1f, 2f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (CARDS * CARD_DP + (CARDS - 1) * GAP_DP) * dp
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // a band a third of the width wide; the matrix slides it across
        shader = LinearGradient(
            0f, 0f, w / 3f, 0f,
            intArrayOf(blockColor, shineColor, blockColor), null, Shader.TileMode.CLAMP
        )
        blockPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        shader?.let {
            shift.setTranslate(sweep * width, 0f)
            it.setLocalMatrix(shift)
        }
        val side = SIDE_DP * dp
        val radius = 16 * dp
        val bar = 12 * dp
        val thumbWidth = THUMB_W_DP * dp
        val thumbHeight = THUMB_H_DP * dp
        for (card in 0 until CARDS) {
            val top = card * (CARD_DP + GAP_DP) * dp
            val bottom = top + CARD_DP * dp
            val end = width - side
            canvas.drawRoundRect(side, top, end, bottom, radius, radius, cardPaint)
            canvas.drawRoundRect(side, top, side + thumbWidth, top + thumbHeight, radius, radius, blockPaint)
            val textStart = side + thumbWidth + 16 * dp
            val textEnd = end - 20 * dp
            val middle = top + thumbHeight / 2
            pill(canvas, textStart, middle - bar - 6 * dp, textStart + (textEnd - textStart) * 0.9f, bar)
            pill(canvas, textStart, middle + 6 * dp, textStart + (textEnd - textStart) * 0.5f, bar)
            val lineStart = side + 16 * dp
            val lineTop = top + thumbHeight + 18 * dp
            pill(canvas, lineStart, lineTop, textEnd, bar)
            pill(canvas, lineStart, lineTop + bar + 10 * dp, lineStart + (textEnd - lineStart) * 0.6f, bar)
        }
    }

    private fun pill(canvas: Canvas, left: Float, top: Float, right: Float, height: Float) {
        canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, blockPaint)
    }

    private fun sync() {
        val run = isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        if (run && !animator.isStarted) animator.start() else if (!run && animator.isStarted) animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    private companion object {
        const val CARDS = 2
        const val CARD_DP = 190
        const val GAP_DP = 16
        const val SIDE_DP = 32
        const val THUMB_W_DP = 150
        const val THUMB_H_DP = 100
    }
}
