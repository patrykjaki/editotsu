package ani.dantotsu.profile

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySingleStatBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.ChartBuilder.ChartKind
import ani.dantotsu.profile.ChartBuilder.ChartSection
import ani.dantotsu.profile.ChartBuilder.MediaType
import ani.dantotsu.profile.ChartBuilder.StatType
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import ani.dantotsu.toast
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

class SingleStatActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySingleStatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySingleStatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()

        val section = chartSection
        if (section == null || section.entries.isEmpty()) {
            toast(R.string.no_chart_data)
            finish()
            return
        }

        binding.singleStatAppBar.updatePadding(top = statusBarHeight)
        binding.singleStatScroll.updatePadding(bottom = navBarHeight + dp(24))
        binding.singleStatBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.singleStatTitle.text = section.title
        binding.singleStatSubtitle.text = buildSubtitle(section)

        if (section.isComparing) {
            binding.singleStatLegend.isVisible = true
            val primaryColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            val compareColor = StatsAdapter.mutedCompareColor(binding.root)
            binding.singleLegendPrimaryDot.backgroundTintList = ColorStateList.valueOf(primaryColor)
            binding.singleLegendCompareDot.backgroundTintList = ColorStateList.valueOf(compareColor)
            binding.singleLegendPrimaryName.text = section.primaryUser
            binding.singleLegendCompareName.text = section.compareUser
        }

        val chartView: BaseStatsChartView = when (section.kind) {
            ChartKind.RANKED_BARS -> RankedBarChartView(this).apply {
                bind(section, maxRows = Int.MAX_VALUE)
            }
            ChartKind.SCORE_HISTOGRAM -> ScoreHistogramView(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
                bind(section)
            }
            ChartKind.YEAR_TIMELINE -> YearTimelineView(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
                bind(section)
            }
        }

        if (chartView.layoutParams == null) {
            chartView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        chartView.onEntrySelected = { entry ->
            if (entry == null) {
                binding.singleStatSelectionBanner.isVisible = false
            } else {
                binding.singleStatSelectionText.text = ChartBuilder.formatEntryDetail(section, entry)
                binding.singleStatSelectionBanner.isVisible = true
            }
        }
        binding.singleStatSelectionBanner.setOnClickListener {
            chartView.clearSelection()
            binding.singleStatSelectionBanner.isVisible = false
        }

        binding.singleStatChartHost.removeAllViews()
        binding.singleStatChartHost.addView(chartView)

        if (section.kind != ChartKind.RANKED_BARS) {
            populateBreakdown(section)
        }
    }

    private fun buildSubtitle(section: ChartSection): String {
        val media = getString(if (section.mediaType == MediaType.ANIME) R.string.anime else R.string.manga)
        val metric = when (section.statType) {
            StatType.COUNT -> getString(R.string.stats_metric_count)
            StatType.TIME -> getString(
                if (section.mediaType == MediaType.ANIME) R.string.stats_metric_hours
                else R.string.stats_metric_chapters
            )
            StatType.AVG_SCORE -> getString(R.string.stats_metric_score)
        }
        return if (section.isComparing) {
            "$media · $metric · ${section.primaryUser} vs ${section.compareUser}"
        } else {
            "$media · $metric"
        }
    }

    private fun populateBreakdown(section: ChartSection) {
        val rows = section.entries.filter { it.value > 0.0 || (it.compareValue ?: 0.0) > 0.0 }
            .let { if (section.kind == ChartKind.YEAR_TIMELINE) it.reversed() else it }
        if (rows.isEmpty()) return

        binding.singleStatBreakdownSection.isVisible = true
        binding.singleStatBreakdownList.removeAllViews()

        val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val dividerColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        val semiBold = runCatching { ResourcesCompat.getFont(this, R.font.poppins_semi_bold) }.getOrNull()
        val regular = runCatching { ResourcesCompat.getFont(this, R.font.poppins) }.getOrNull()

        rows.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(0, dp(8), 0, dp(8))
            }
            val labelView = TextView(this).apply {
                text = entry.label
                textSize = 14f
                typeface = semiBold ?: Typeface.DEFAULT_BOLD
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueText = if (section.isComparing) {
                val p = ChartBuilder.formatMetricValue(entry.value, section.statType, section.mediaType)
                val c = ChartBuilder.formatMetricValue(entry.compareValue ?: 0.0, section.statType, section.mediaType)
                "$p  /  $c"
            } else {
                val p = ChartBuilder.formatMetricValue(entry.value, section.statType, section.mediaType)
                val share = entry.sharePercent?.takeIf { section.statType != StatType.AVG_SCORE }
                if (share != null) "$p · ${ChartBuilder.formatSharePercent(share)}" else p
            }
            val valueView = TextView(this).apply {
                text = valueText
                textSize = 13.5f
                typeface = regular ?: Typeface.DEFAULT
                setTextColor(onSurfaceVariant)
            }
            row.addView(labelView)
            row.addView(valueView)
            binding.singleStatBreakdownList.addView(row)

            if (index < rows.lastIndex) {
                val divider = View(this).apply {
                    setBackgroundColor(dividerColor)
                    alpha = 0.4f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                }
                binding.singleStatBreakdownList.addView(divider)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        var chartSection: ChartSection? = null
    }
}
