package ani.dantotsu.others

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import ani.dantotsu.getThemeColor
import kotlin.math.ceil

/**
 * Placeholder for content that is loading, with a highlight sweeping left to right across it.
 * Drawn rather than inflated, so a screen pays for one view. The layout picks the shape with
 * android:tag:
 *  - none: episode cards (thumbnail block, text bars)
 *  - "page": a source card and a heading above the episode cards, for a tab not built yet
 *  - "covers": one row of cover tiles with a title bar under each
 *  - "rows": avatar and two text bars per row
 *  - "circles": one row of round avatars with a name bar under each
 *  - "grid": rows of cover tiles, three across
 *  - "lines": label and value bars, for a details page
 *  - "tabs": a strip of tab labels
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val cardColor = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    private val surface = context.getThemeColor(com.google.android.material.R.attr.colorSurface)
    private val ink = context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)

    /** Blocks on a card are a tone darker than the card; blocks on the page are the card tone. */
    private val onCard get() = tag == null || tag == PAGE

    // On the bare page the tone comes from the text colour over the surface, which reads in
    // every theme; a card keeps the card colour the real rows use.
    private val blockColor
        get() = if (onCard) ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 30), cardColor)
        else ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 28), surface)
    private val shineColor
        get() = if (onCard) ColorUtils.blendARGB(cardColor, surface, 0.7f)
        else ColorUtils.compositeColors(ColorUtils.setAlphaComponent(ink, 8), surface)

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shift = Matrix()
    private var shader: LinearGradient? = null
    private var shown = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun wantedDp() = when (tag) {
        COVERS -> COVER_ROW_DP
        CIRCLES -> CIRCLE_ROW_DP
        GRID -> 2 * GRID_ROW_DP
        LINES -> LINE_COUNT * LINE_DP
        ROWS -> ROW_COUNT * ROW_DP
        TABS -> 48
        PAGE -> HEAD_DP + CARDS * CARD_DP + (CARDS - 1) * GAP_DP
        else -> CARDS * CARD_DP + (CARDS - 1) * GAP_DP
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((wantedDp() * dp).toInt(), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // a wide soft band, leaning; the matrix slides it left to right
        shader = LinearGradient(
            0f, 0f, w * BAND, w * BAND * 0.35f,
            intArrayOf(blockColor, shineColor, blockColor), null, Shader.TileMode.CLAMP
        )
        blockPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        // Driven by the clock, not an animator: this is the only sign that loading is going on,
        // so it keeps moving when the system's animation scale is zero.
        val phase = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        shader?.let {
            shift.setTranslate((-BAND + phase * (1f + 2 * BAND)) * width, 0f)
            it.setLocalMatrix(shift)
        }
        when (tag) {
            COVERS -> drawCovers(canvas)
            ROWS -> drawRows(canvas)
            CIRCLES -> drawCircles(canvas)
            GRID -> drawGrid(canvas)
            LINES -> drawLines(canvas)
            TABS -> drawTabs(canvas)
            else -> drawCards(canvas)
        }
        if (shown) postInvalidateOnAnimation()
    }

    private fun drawCards(canvas: Canvas) {
        val side = SIDE_DP * dp
        val radius = 16 * dp
        val bar = 12 * dp
        val thumbWidth = THUMB_W_DP * dp
        val thumbHeight = THUMB_H_DP * dp
        val end = width - side
        var head = 0f
        if (tag == PAGE) {
            // the tab before it has loaded: the source card and the "Episodes" line come first
            head = HEAD_DP * dp
            cardPaint.alpha = 255
            blockPaint.alpha = 255
            canvas.drawRoundRect(side, 0f, end, SOURCE_DP * dp, radius, radius, cardPaint)
            val tile = 40 * dp
            val tileTop = (SOURCE_DP * dp - tile) / 2
            canvas.drawRoundRect(side + 12 * dp, tileTop, side + 12 * dp + tile, tileTop + tile, 10 * dp, 10 * dp, blockPaint)
            pill(canvas, side + 64 * dp, SOURCE_DP * dp / 2 - bar / 2, side + 64 * dp + (end - side) * 0.4f, bar)
            pill(canvas, side, (SOURCE_DP + 34) * dp, side + 96 * dp, 16 * dp)
        }
        for (card in 0 until CARDS) {
            val top = head + card * (CARD_DP + GAP_DP) * dp
            val bottom = top + CARD_DP * dp
            // the list trails off: each card is fainter than the one above
            val fade = 255 - card * (255 / CARDS)
            cardPaint.alpha = fade
            blockPaint.alpha = fade
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

    private fun drawCovers(canvas: Canvas) {
        val tileWidth = COVER_W_DP * dp
        val tileHeight = COVER_H_DP * dp
        val gap = 16 * dp
        val bar = 12 * dp
        val top = ((height - (COVER_H_DP + 36) * dp) / 2).coerceAtLeast(0f)
        blockPaint.alpha = 255
        for (i in 0 until ceil(width / (tileWidth + gap)).toInt()) {
            val left = 24 * dp + i * (tileWidth + gap)
            canvas.drawRoundRect(left, top, left + tileWidth, top + tileHeight, 16 * dp, 16 * dp, blockPaint)
            pill(canvas, left, top + tileHeight + 12 * dp, left + tileWidth * 0.8f, bar)
        }
    }

    private fun drawCircles(canvas: Canvas) {
        val size = 64 * dp
        val gap = 20 * dp
        blockPaint.alpha = 255
        for (i in 0 until ceil(width / (size + gap)).toInt()) {
            val left = 24 * dp + i * (size + gap)
            canvas.drawRoundRect(left, 4 * dp, left + size, 4 * dp + size, size / 2, size / 2, blockPaint)
            pill(canvas, left + 6 * dp, size + 14 * dp, left + size - 6 * dp, 10 * dp)
        }
    }

    // follows the list screens: three cells across, a cover of at most 108dp centred in each
    // (narrower on small screens), a two-line title and a progress line under it
    private fun drawGrid(canvas: Canvas) {
        val cell = (width - 14 * dp) / 3
        val tileWidth = minOf(108 * dp, cell - 20 * dp)
        val tileHeight = tileWidth * 1.48f
        val pitch = tileHeight + GRID_TEXT_DP * dp
        val rows = ceil(height / pitch).toInt().coerceAtLeast(1)
        for (row in 0 until rows) {
            val top = row * pitch + 14 * dp
            blockPaint.alpha = (255 - row * (255 / (rows + 1))).coerceAtLeast(40)
            for (column in 0 until 3) {
                val left = 7 * dp + column * cell + (cell - tileWidth) / 2
                canvas.drawRoundRect(left, top, left + tileWidth, top + tileHeight, 16 * dp, 16 * dp, blockPaint)
                pill(canvas, left, top + tileHeight + 14 * dp, left + tileWidth * 0.9f, 12 * dp)
                pill(canvas, left, top + tileHeight + 40 * dp, left + tileWidth * 0.45f, 10 * dp)
            }
        }
    }

    private fun drawTabs(canvas: Canvas) {
        blockPaint.alpha = 255
        val bar = 12 * dp
        val top = (height - bar) / 2
        var left = 48 * dp
        for (w in intArrayOf(96, 112, 84, 72)) {
            if (left > width) break
            pill(canvas, left, top, left + w * dp, bar)
            left += (w + 32) * dp
        }
    }

    private fun drawLines(canvas: Canvas) {
        val side = SIDE_DP * dp
        val bar = 12 * dp
        val lines = ceil(height / (LINE_DP * dp)).toInt()
        for (line in 0 until lines) {
            val top = line * LINE_DP * dp + 8 * dp
            blockPaint.alpha = (255 - line * (255 / (lines + 1))).coerceAtLeast(40)
            // label left, value right, widths varied so it does not read as a table of equals
            val label = width * (0.22f + 0.06f * (line % 3))
            val value = width * (0.18f + 0.08f * ((line + 1) % 3))
            pill(canvas, side, top, side + label, bar)
            pill(canvas, width - side - value, top, width - side, bar)
        }
    }

    private fun drawRows(canvas: Canvas) {
        val side = 24 * dp
        val avatar = 40 * dp
        val bar = 12 * dp
        val rows = ceil(height / (ROW_DP * dp)).toInt()
        val textStart = side + avatar + 16 * dp
        val textWidth = width - side - textStart
        for (row in 0 until rows) {
            val top = row * ROW_DP * dp + 12 * dp
            blockPaint.alpha = (255 - row * (255 / rows)).coerceAtLeast(40)
            canvas.drawRoundRect(side, top, side + avatar, top + avatar, avatar / 2, avatar / 2, blockPaint)
            pill(canvas, textStart, top + 4 * dp, textStart + textWidth * 0.45f, bar)
            pill(canvas, textStart, top + 4 * dp + bar + 10 * dp, textStart + textWidth * 0.9f, bar)
        }
    }

    private fun pill(canvas: Canvas, left: Float, top: Float, right: Float, height: Float) {
        canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, blockPaint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) postInvalidateOnAnimation()
    }

    private companion object {
        const val PAGE = "page"
        const val COVERS = "covers"
        const val ROWS = "rows"
        const val CIRCLES = "circles"
        const val GRID = "grid"
        const val LINES = "lines"
        const val TABS = "tabs"
        const val GRID_ROW_DP = 236
        const val GRID_TEXT_DP = 92
        const val LINE_DP = 30
        const val LINE_COUNT = 10
        const val CIRCLE_ROW_DP = 96
        const val CARDS = 3
        const val SOURCE_DP = 64
        const val HEAD_DP = SOURCE_DP + 34 + 16 + 24
        const val SWEEP_MS = 1400L
        const val BAND = 0.6f
        const val CARD_DP = 190
        const val GAP_DP = 16
        const val SIDE_DP = 32
        const val THUMB_W_DP = 150
        const val THUMB_H_DP = 100
        const val COVER_W_DP = 102
        const val COVER_H_DP = 154
        const val COVER_ROW_DP = 228
        const val ROW_DP = 72
        const val ROW_COUNT = 4
    }
}
