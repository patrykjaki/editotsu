package ani.dantotsu.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.profile.ChartBuilder.ChartEntry
import ani.dantotsu.profile.ChartBuilder.ChartSection
import ani.dantotsu.profile.ChartBuilder.StatType
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight native Canvas chart views for the Profile Stats screen.
 *
 * Design invariants:
 * - Zero WebViews; zero object allocations inside [onDraw].
 * - Single theme accent (`colorPrimary`), muted neutral compare series (`colorOnSurfaceVariant` at
 *   50% alpha so it stays distinct even in Monochrome), and subtle `colorOutlineVariant` hairlines.
 * - Never calls `requestDisallowInterceptTouchEvent(true)`, so ViewPager2 tab swiping and vertical
 *   RecyclerView scrolling are never blocked.
 */
internal sealed class BaseStatsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    protected var section: ChartSection? = null
    protected var selectedIndex: Int = -1
    var onEntrySelected: ((ChartEntry?) -> Unit)? = null

    protected val density: Float = resources.displayMetrics.density
    protected val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    protected val fontRegular: Typeface? = runCatching {
        ResourcesCompat.getFont(context, R.font.poppins)
    }.getOrNull()
    protected val fontSemiBold: Typeface? = runCatching {
        ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
    }.getOrNull()

    protected var colorPrimary: Int = Color.CYAN
    protected var colorCompare: Int = Color.GRAY
    protected var colorCompareStroke: Int = Color.GRAY
    protected var colorTrack: Int = Color.DKGRAY
    protected var colorSelection: Int = Color.DKGRAY
    protected var colorOnSurface: Int = Color.WHITE
    protected var colorOnSurfaceVariant: Int = Color.LTGRAY
    protected var colorGrid: Int = Color.GRAY
    protected var colorSurfaceCard: Int = Color.BLACK

    private var downX = 0f
    private var downY = 0f
    private var trackingTap = false

    protected fun dp(value: Float): Float = value * density
    protected fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    protected fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    protected fun resolveThemeColors() {
        colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.CYAN)
        colorOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        colorOnSurfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.LTGRAY
        )
        colorTrack = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            Color.DKGRAY
        )
        colorSelection = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.DKGRAY
        )
        colorSurfaceCard = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            Color.BLACK
        )
        val outlineVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            colorOnSurfaceVariant
        )
        colorGrid = withAlpha(outlineVariant, 0.45f)
        // Secondary series uses muted onSurfaceVariant so there is no rainbow and it stays
        // visually distinct from colorPrimary in every theme (including Monochrome).
        colorCompare = withAlpha(colorOnSurfaceVariant, 0.48f)
        colorCompareStroke = withAlpha(colorOnSurfaceVariant, 0.72f)
        onColorsResolved()
    }

    protected open fun onColorsResolved() = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveThemeColors()
    }

    fun clearSelection() {
        if (selectedIndex != -1) {
            selectedIndex = -1
            invalidate()
        }
    }

    fun setSelectedKey(key: String?) {
        val entries = visibleEntries()
        val idx = if (key == null) -1 else entries.indexOfFirst { it.key == key }
        if (idx != selectedIndex) {
            selectedIndex = idx
            invalidate()
        }
    }

    protected open fun visibleEntries(): List<ChartEntry> = section?.entries.orEmpty()

    protected abstract fun hitTestIndex(x: Float, y: Float): Int

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val entries = visibleEntries()
        if (entries.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingTap = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingTap && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    trackingTap = false
                }
                return trackingTap
            }
            MotionEvent.ACTION_UP -> {
                if (trackingTap) {
                    trackingTap = false
                    val hit = hitTestIndex(event.x, event.y)
                    if (hit in entries.indices) {
                        selectedIndex = if (selectedIndex == hit) -1 else hit
                        invalidate()
                        onEntrySelected?.invoke(entries.getOrNull(selectedIndex))
                        performClick()
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingTap = false
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Ranked horizontal bar list. Every label and exact value is legible without tapping; tapping a row
 * highlights it and reports the entry for a full multi-metric readout.
 */
internal class RankedBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseStatsChartView(context, attrs, defStyleAttr) {

    private var entries: List<ChartEntry> = emptyList()
    private var maxValue: Double = 1.0
    private var isComparing: Boolean = false
    private var rowHeightPx: Float = dp(48f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val comparePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13.5f)
        typeface = fontSemiBold ?: Typeface.DEFAULT_BOLD
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12.5f)
        typeface = fontSemiBold ?: Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val sharePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11.5f)
        typeface = fontRegular ?: Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()
    private val selectionRect = RectF()

    private val barHeightSingle = dp(8f)
    private val barHeightCompare = dp(6f)
    private val barCompareGap = dp(4f)
    private val barCorner = dp(4f)
    private val minNonZeroBarPx = dp(6f)
    private val labelValueGap = dp(12f)
    private val rowHighlightInset = dp(6f)
    private val rowHighlightCorner = dp(8f)

    init {
        resolveThemeColors()
    }

    override fun onColorsResolved() {
        barPaint.color = colorPrimary
        comparePaint.color = colorCompare
        trackPaint.color = colorTrack
        selectionPaint.color = withAlpha(colorSelection, 0.72f)
        labelPaint.color = colorOnSurface
        valuePaint.color = colorOnSurface
        sharePaint.color = colorOnSurfaceVariant
    }

    fun bind(chartSection: ChartSection, maxRows: Int = Int.MAX_VALUE, selectedKey: String? = null) {
        section = chartSection
        isComparing = chartSection.isComparing
        entries = if (maxRows < chartSection.entries.size) {
            chartSection.entries.take(maxRows)
        } else {
            chartSection.entries
        }
        maxValue = max(
            1.0,
            entries.maxOfOrNull { max(it.value, it.compareValue ?: 0.0) } ?: 1.0
        )
        val textLineHeight = labelPaint.fontSpacing
        rowHeightPx = if (isComparing) {
            max(dp(58f), textLineHeight + dp(28f))
        } else {
            max(dp(48f), textLineHeight + dp(22f))
        }
        selectedIndex = if (selectedKey == null) -1 else entries.indexOfFirst { it.key == selectedKey }
        contentDescription = buildString {
            append(chartSection.title)
            entries.firstOrNull()?.let { top ->
                append(", top: ${top.label} ")
                append(ChartBuilder.formatMetricValue(top.value, chartSection.statType, chartSection.mediaType))
            }
        }
        requestLayout()
        invalidate()
    }

    override fun visibleEntries(): List<ChartEntry> = entries

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (paddingTop + paddingBottom + entries.size * rowHeightPx).roundToInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun hitTestIndex(x: Float, y: Float): Int {
        if (entries.isEmpty()) return -1
        val localY = y - paddingTop
        if (localY < 0f) return -1
        val index = (localY / rowHeightPx).toInt()
        return if (index in entries.indices) index else -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sec = section ?: return
        if (entries.isEmpty()) return

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val chartWidth = (right - left).coerceAtLeast(1f)
        val fm = labelPaint.fontMetrics

        for (i in entries.indices) {
            val entry = entries[i]
            val rowTop = paddingTop + i * rowHeightPx
            val rowBottom = rowTop + rowHeightPx

            if (i == selectedIndex) {
                selectionRect.set(
                    (left - rowHighlightInset).coerceAtLeast(0f),
                    rowTop + dp(2f),
                    (right + rowHighlightInset).coerceAtMost(width.toFloat()),
                    rowBottom - dp(2f)
                )
                canvas.drawRoundRect(selectionRect, rowHighlightCorner, rowHighlightCorner, selectionPaint)
            }

            val baseline = rowTop + dp(6f) - fm.ascent

            val rightTextWidth: Float
            if (isComparing) {
                val pText = ChartBuilder.formatMetricValue(entry.value, sec.statType, sec.mediaType)
                val cText = ChartBuilder.formatMetricValue(entry.compareValue ?: 0.0, sec.statType, sec.mediaType)
                val cSuffix = " / $cText"
                val cWidth = sharePaint.measureText(cSuffix)
                val pWidth = valuePaint.measureText(pText)
                canvas.drawText(cSuffix, right, baseline, sharePaint)
                canvas.drawText(pText, right - cWidth, baseline, valuePaint)
                rightTextWidth = pWidth + cWidth
            } else {
                val pText = ChartBuilder.formatMetricValue(entry.value, sec.statType, sec.mediaType)
                val share = entry.sharePercent?.takeIf { sec.statType != StatType.AVG_SCORE }
                if (share != null) {
                    val shareText = " · ${ChartBuilder.formatSharePercent(share)}"
                    val shareWidth = sharePaint.measureText(shareText)
                    val valWidth = valuePaint.measureText(pText)
                    canvas.drawText(shareText, right, baseline, sharePaint)
                    canvas.drawText(pText, right - shareWidth, baseline, valuePaint)
                    rightTextWidth = valWidth + shareWidth
                } else {
                    canvas.drawText(pText, right, baseline, valuePaint)
                    rightTextWidth = valuePaint.measureText(pText)
                }
            }

            val availLabelWidth = (chartWidth - rightTextWidth - labelValueGap).coerceAtLeast(dp(40f))
            val ellipsizedLabel = TextUtils.ellipsize(
                entry.label,
                labelPaint,
                availLabelWidth,
                TextUtils.TruncateAt.END
            )
            canvas.drawText(ellipsizedLabel, 0, ellipsizedLabel.length, left, baseline, labelPaint)

            val barTop = baseline + fm.descent + dp(5f)
            if (isComparing) {
                drawBar(canvas, left, barTop, chartWidth, barHeightCompare, entry.value, barPaint)
                val bar2Top = barTop + barHeightCompare + barCompareGap
                drawBar(canvas, left, bar2Top, chartWidth, barHeightCompare, entry.compareValue ?: 0.0, comparePaint)
            } else {
                drawBar(canvas, left, barTop, chartWidth, barHeightSingle, entry.value, barPaint)
            }
        }
    }

    private fun drawBar(
        canvas: Canvas,
        left: Float,
        top: Float,
        fullWidth: Float,
        height: Float,
        value: Double,
        fillPaint: Paint,
    ) {
        rect.set(left, top, left + fullWidth, top + height)
        canvas.drawRoundRect(rect, barCorner, barCorner, trackPaint)
        if (value > 0.0) {
            val fraction = (value / maxValue).coerceIn(0.0, 1.0).toFloat()
            val fillWidth = max(minNonZeroBarPx, fullWidth * fraction).coerceAtMost(fullWidth)
            rect.set(left, top, left + fillWidth, top + height)
            canvas.drawRoundRect(rect, barCorner, barCorner, fillPaint)
        }
    }
}

/**
 * Clean vertical column histogram for score distribution.
 * Every non-zero bar in single-user mode displays its exact compact value right above the bar.
 */
internal class ScoreHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseStatsChartView(context, attrs, defStyleAttr) {

    private var entries: List<ChartEntry> = emptyList()
    private var axisMax: Double = 1.0
    private var isComparing: Boolean = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val comparePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = fontRegular ?: Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val yAxisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        typeface = fontRegular ?: Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
    }
    private val barValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        typeface = fontSemiBold ?: Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val barPath = Path()
    private val topRadii = floatArrayOf(dp(4f), dp(4f), dp(4f), dp(4f), 0f, 0f, 0f, 0f)

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f

    init {
        resolveThemeColors()
    }

    override fun onColorsResolved() {
        barPaint.color = colorPrimary
        comparePaint.color = colorCompare
        gridPaint.color = colorGrid
        selectionPaint.color = withAlpha(colorSelection, 0.75f)
        axisLabelPaint.color = colorOnSurfaceVariant
        yAxisPaint.color = colorOnSurfaceVariant
        barValuePaint.color = colorOnSurface
    }

    fun bind(chartSection: ChartSection, selectedKey: String? = null) {
        section = chartSection
        entries = chartSection.entries
        isComparing = chartSection.isComparing
        val rawMax = entries.maxOfOrNull { max(it.value, it.compareValue ?: 0.0) } ?: 0.0
        axisMax = if (chartSection.statType == StatType.AVG_SCORE) {
            max(100.0, ChartBuilder.niceAxisMax(rawMax))
        } else {
            ChartBuilder.niceAxisMax(rawMax)
        }
        selectedIndex = if (selectedKey == null) -1 else entries.indexOfFirst { it.key == selectedKey }
        contentDescription = buildString {
            append(chartSection.title)
            chartSection.subtitle?.let { append(", $it") }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val defaultHeight = dp(196f).roundToInt()
        setMeasuredDimension(width, resolveSize(defaultHeight, heightMeasureSpec))
    }

    override fun hitTestIndex(x: Float, y: Float): Int {
        if (entries.isEmpty() || plotRight <= plotLeft) return -1
        if (x < plotLeft || x > plotRight) return -1
        val slotWidth = (plotRight - plotLeft) / entries.size
        val index = ((x - plotLeft) / slotWidth).toInt().coerceIn(0, entries.lastIndex)
        return index
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sec = section ?: return
        if (entries.isEmpty()) return

        val yGutter = dp(34f)
        plotLeft = paddingLeft + yGutter
        plotRight = (width - paddingRight - dp(4f)).coerceAtLeast(plotLeft + 1f)
        plotTop = paddingTop + dp(20f)
        plotBottom = (height - paddingBottom - dp(24f)).coerceAtLeast(plotTop + 1f)
        val plotHeight = plotBottom - plotTop
        val plotWidth = plotRight - plotLeft

        // Horizontal hairlines at 0%, 50%, 100%
        val yFm = yAxisPaint.fontMetrics
        val yTextOffset = -(yFm.ascent + yFm.descent) / 2f
        val ticks = floatArrayOf(0f, 0.5f, 1f)
        for (frac in ticks) {
            val y = plotBottom - plotHeight * frac
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            val tickVal = axisMax * frac
            val label = ChartBuilder.formatCompactValue(tickVal, sec.statType, sec.mediaType)
            canvas.drawText(label, plotLeft - dp(6f), y + yTextOffset, yAxisPaint)
        }

        val slotWidth = plotWidth / entries.size
        val xLabelY = plotBottom + dp(16f)

        for (i in entries.indices) {
            val entry = entries[i]
            val slotLeft = plotLeft + i * slotWidth
            val slotCenter = slotLeft + slotWidth / 2f

            if (i == selectedIndex) {
                rect.set(slotLeft + dp(1f), plotTop - dp(4f), slotLeft + slotWidth - dp(1f), plotBottom)
                canvas.drawRoundRect(rect, dp(6f), dp(6f), selectionPaint)
            }

            if (isComparing) {
                val groupWidth = min(slotWidth * 0.78f, dp(28f))
                val gap = dp(2f)
                val singleBarW = ((groupWidth - gap) / 2f).coerceAtLeast(dp(3f))
                val leftBarStart = slotCenter - groupWidth / 2f
                drawTopRoundedColumn(canvas, leftBarStart, singleBarW, entry.value, plotHeight, barPaint)
                drawTopRoundedColumn(
                    canvas,
                    leftBarStart + singleBarW + gap,
                    singleBarW,
                    entry.compareValue ?: 0.0,
                    plotHeight,
                    comparePaint
                )
            } else {
                val barW = min(slotWidth * 0.62f, dp(26f)).coerceAtLeast(dp(6f))
                val barTop = drawTopRoundedColumn(
                    canvas,
                    slotCenter - barW / 2f,
                    barW,
                    entry.value,
                    plotHeight,
                    barPaint
                )
                if (entry.value > 0.0) {
                    val valueText = ChartBuilder.formatCompactValue(entry.value, sec.statType, sec.mediaType)
                    canvas.drawText(valueText, slotCenter, barTop - dp(5f), barValuePaint)
                }
            }

            axisLabelPaint.color = if (i == selectedIndex) colorOnSurface else colorOnSurfaceVariant
            axisLabelPaint.typeface = if (i == selectedIndex) {
                fontSemiBold ?: Typeface.DEFAULT_BOLD
            } else {
                fontRegular ?: Typeface.DEFAULT
            }
            canvas.drawText(entry.label, slotCenter, xLabelY, axisLabelPaint)
        }
    }

    private fun drawTopRoundedColumn(
        canvas: Canvas,
        left: Float,
        barWidth: Float,
        value: Double,
        plotHeight: Float,
        paint: Paint,
    ): Float {
        if (value <= 0.0) return plotBottom
        val frac = (value / axisMax).coerceIn(0.0, 1.0).toFloat()
        val barH = max(dp(3f), plotHeight * frac)
        val top = plotBottom - barH
        barPath.reset()
        rect.set(left, top, left + barWidth, plotBottom)
        barPath.addRoundRect(rect, topRadii, Path.Direction.CW)
        canvas.drawPath(barPath, paint)
        return top
    }
}

/**
 * Crisp native area + line timeline for Release year and Start year.
 * Handles 1-point, sparse, and dense timelines cleanly, annotates the peak value, and supports
 * tap-to-inspect snapping to the nearest year without fighting horizontal tab swipes.
 */
internal class YearTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseStatsChartView(context, attrs, defStyleAttr) {

    private var entries: List<ChartEntry> = emptyList()
    private var axisMax: Double = 1.0
    private var isComparing: Boolean = false
    private var peakIndex: Int = -1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val compareLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val compareDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val axisLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = fontRegular ?: Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val yAxisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        typeface = fontRegular ?: Typeface.DEFAULT
        textAlign = Paint.Align.RIGHT
    }
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        typeface = fontSemiBold ?: Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val primaryPath = Path()
    private val areaPath = Path()
    private val comparePath = Path()

    private var cachedShaderTop = Float.NaN
    private var cachedShaderBottom = Float.NaN
    private var cachedShaderColor = 0

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f

    init {
        resolveThemeColors()
    }

    override fun onColorsResolved() {
        linePaint.color = colorPrimary
        compareLinePaint.color = colorCompareStroke
        dotPaint.color = colorPrimary
        dotHaloPaint.color = colorSurfaceCard
        compareDotPaint.color = colorCompareStroke
        gridPaint.color = colorGrid
        guidePaint.color = withAlpha(colorPrimary, 0.4f)
        axisLabelPaint.color = colorOnSurfaceVariant
        yAxisPaint.color = colorOnSurfaceVariant
        badgeTextPaint.color = colorOnSurface
        cachedShaderColor = 0
    }

    fun bind(chartSection: ChartSection, selectedKey: String? = null) {
        section = chartSection
        entries = chartSection.entries
        isComparing = chartSection.isComparing
        val rawMax = entries.maxOfOrNull { max(it.value, it.compareValue ?: 0.0) } ?: 0.0
        axisMax = if (chartSection.statType == StatType.AVG_SCORE) {
            max(100.0, ChartBuilder.niceAxisMax(rawMax))
        } else {
            ChartBuilder.niceAxisMax(rawMax)
        }
        peakIndex = entries.indices.maxByOrNull { entries[it].value }?.takeIf { entries[it].value > 0.0 } ?: -1
        selectedIndex = if (selectedKey == null) -1 else entries.indexOfFirst { it.key == selectedKey }
        contentDescription = buildString {
            append(chartSection.title)
            chartSection.subtitle?.let { append(", $it") }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val defaultHeight = dp(196f).roundToInt()
        setMeasuredDimension(width, resolveSize(defaultHeight, heightMeasureSpec))
    }

    private fun xForIndex(index: Int): Float {
        if (entries.size <= 1) return (plotLeft + plotRight) / 2f
        val frac = index.toFloat() / (entries.size - 1).toFloat()
        return plotLeft + frac * (plotRight - plotLeft)
    }

    private fun yForValue(value: Double): Float {
        val frac = (value / axisMax).coerceIn(0.0, 1.0).toFloat()
        return plotBottom - frac * (plotBottom - plotTop)
    }

    override fun hitTestIndex(x: Float, y: Float): Int {
        if (entries.isEmpty() || plotRight <= plotLeft) return -1
        if (entries.size == 1) return 0
        val clamped = x.coerceIn(plotLeft, plotRight)
        val frac = (clamped - plotLeft) / (plotRight - plotLeft)
        return (frac * (entries.size - 1)).roundToInt().coerceIn(0, entries.lastIndex)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sec = section ?: return
        if (entries.isEmpty()) return

        val yGutter = dp(34f)
        plotLeft = paddingLeft + yGutter + dp(8f)
        plotRight = (width - paddingRight - dp(12f)).coerceAtLeast(plotLeft + 1f)
        plotTop = paddingTop + dp(22f)
        plotBottom = (height - paddingBottom - dp(24f)).coerceAtLeast(plotTop + 1f)
        val plotHeight = plotBottom - plotTop

        if (cachedShaderTop != plotTop || cachedShaderBottom != plotBottom || cachedShaderColor != colorPrimary) {
            cachedShaderTop = plotTop
            cachedShaderBottom = plotBottom
            cachedShaderColor = colorPrimary
            areaPaint.shader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                withAlpha(colorPrimary, 0.24f),
                withAlpha(colorPrimary, 0.0f),
                Shader.TileMode.CLAMP
            )
        }

        // Horizontal gridlines & Y ticks
        val yFm = yAxisPaint.fontMetrics
        val yTextOffset = -(yFm.ascent + yFm.descent) / 2f
        val yTicks = floatArrayOf(0f, 0.5f, 1f)
        for (frac in yTicks) {
            val y = plotBottom - plotHeight * frac
            canvas.drawLine(plotLeft - dp(4f), y, plotRight, y, gridPaint)
            val label = ChartBuilder.formatCompactValue(axisMax * frac, sec.statType, sec.mediaType)
            canvas.drawText(label, plotLeft - dp(8f), y + yTextOffset, yAxisPaint)
        }

        // Compare series dashed line (drawn behind primary)
        if (isComparing && entries.size >= 2) {
            comparePath.reset()
            for (i in entries.indices) {
                val x = xForIndex(i)
                val y = yForValue(entries[i].compareValue ?: 0.0)
                if (i == 0) comparePath.moveTo(x, y) else comparePath.lineTo(x, y)
            }
            canvas.drawPath(comparePath, compareLinePaint)
        }

        // Primary series area + line
        if (entries.size >= 2) {
            primaryPath.reset()
            areaPath.reset()
            for (i in entries.indices) {
                val x = xForIndex(i)
                val y = yForValue(entries[i].value)
                if (i == 0) {
                    primaryPath.moveTo(x, y)
                    areaPath.moveTo(x, plotBottom)
                    areaPath.lineTo(x, y)
                } else {
                    primaryPath.lineTo(x, y)
                    areaPath.lineTo(x, y)
                }
            }
            areaPath.lineTo(xForIndex(entries.lastIndex), plotBottom)
            areaPath.close()
            canvas.drawPath(areaPath, areaPaint)
            canvas.drawPath(primaryPath, linePaint)
        } else {
            // Single year fallback: draw a clean stem + dot so it reads unmistakably
            val x = xForIndex(0)
            val y = yForValue(entries[0].value)
            canvas.drawLine(x, plotBottom, x, y, linePaint)
        }

        // Dots for small timelines (<= 14 points)
        if (entries.size <= 14) {
            val r = dp(3f)
            for (i in entries.indices) {
                if (i == selectedIndex) continue
                val x = xForIndex(i)
                val y = yForValue(entries[i].value)
                canvas.drawCircle(x, y, r + dp(1.2f), dotHaloPaint)
                canvas.drawCircle(x, y, r, dotPaint)
                if (isComparing) {
                    val cy = yForValue(entries[i].compareValue ?: 0.0)
                    canvas.drawCircle(x, cy, dp(2.4f), compareDotPaint)
                }
            }
        }

        // Highlight either the tapped point or the peak point with an inline value label
        val focusIndex = if (selectedIndex in entries.indices) selectedIndex else peakIndex
        if (focusIndex in entries.indices) {
            val focusEntry = entries[focusIndex]
            val fx = xForIndex(focusIndex)
            val fy = yForValue(focusEntry.value)
            if (selectedIndex in entries.indices) {
                canvas.drawLine(fx, plotTop, fx, plotBottom, guidePaint)
                if (isComparing) {
                    val cy = yForValue(focusEntry.compareValue ?: 0.0)
                    canvas.drawCircle(fx, cy, dp(5f), dotHaloPaint)
                    canvas.drawCircle(fx, cy, dp(3.5f), compareDotPaint)
                }
            }
            canvas.drawCircle(fx, fy, dp(6f), dotHaloPaint)
            canvas.drawCircle(fx, fy, dp(4f), dotPaint)

            val valueText = if (selectedIndex in entries.indices) {
                "${focusEntry.label}: ${ChartBuilder.formatMetricValue(focusEntry.value, sec.statType, sec.mediaType)}"
            } else {
                ChartBuilder.formatCompactValue(focusEntry.value, sec.statType, sec.mediaType)
            }
            val halfW = badgeTextPaint.measureText(valueText) / 2f
            val clampedX = fx.coerceIn(plotLeft + halfW, plotRight - halfW)
            val labelY = (fy - dp(8f)).coerceAtLeast(paddingTop + dp(12f))
            canvas.drawText(valueText, clampedX, labelY, badgeTextPaint)
        }

        // Non-overlapping X-axis year ticks
        drawYearTicks(canvas)
    }

    private fun drawYearTicks(canvas: Canvas) {
        val labelY = plotBottom + dp(16f)
        val minSpacing = max(axisLabelPaint.measureText("2026") + dp(12f), dp(42f))
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val maxTicks = max(2, (plotWidth / minSpacing).toInt())

        val indicesToDraw = linkedSetOf<Int>()
        if (entries.size <= maxTicks) {
            for (i in entries.indices) indicesToDraw.add(i)
        } else {
            val step = ((entries.size - 1).toFloat() / (maxTicks - 1).toFloat())
            for (k in 0 until maxTicks) {
                indicesToDraw.add((k * step).roundToInt().coerceIn(0, entries.lastIndex))
            }
            indicesToDraw.add(entries.lastIndex)
        }

        var lastDrawnX = Float.NEGATIVE_INFINITY
        val sortedIndices = indicesToDraw.sorted()
        for (idx in sortedIndices) {
            val x = xForIndex(idx)
            val label = entries[idx].label
            val halfW = axisLabelPaint.measureText(label) / 2f
            val drawX = x.coerceIn(plotLeft + halfW - dp(4f), plotRight - halfW + dp(4f))
            if (drawX - halfW < lastDrawnX + dp(6f)) continue
            axisLabelPaint.color = if (idx == selectedIndex) colorOnSurface else colorOnSurfaceVariant
            canvas.drawText(label, drawX, labelY, axisLabelPaint)
            lastDrawnX = drawX + halfW
        }
    }
}
