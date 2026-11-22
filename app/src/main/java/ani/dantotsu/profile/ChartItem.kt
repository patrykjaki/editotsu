package ani.dantotsu.profile

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChartBinding
import ani.dantotsu.databinding.ItemStatsSummaryBinding
import ani.dantotsu.profile.ChartBuilder.ChartEntry
import ani.dantotsu.profile.ChartBuilder.ChartKind
import ani.dantotsu.profile.ChartBuilder.ChartSection
import ani.dantotsu.profile.ChartBuilder.MediaType
import ani.dantotsu.profile.ChartBuilder.SectionId
import ani.dantotsu.profile.ChartBuilder.StatsSummary
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * Lazy, fully recyclable RecyclerView adapter for the native Profile Stats screen.
 * Each chart kind has its own view type so native Canvas views are reused on scroll with zero
 * inflation churn or WebView overhead.
 */
class StatsAdapter(
    private val onOpenFullScreen: (ChartSection) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Item {
        data class Summary(val summary: StatsSummary) : Item
        data class Chart(val section: ChartSection) : Item
    }

    private val items = mutableListOf<Item>()
    private val expandedSections = mutableSetOf<SectionId>()
    private val selectedEntryKeys = mutableMapOf<SectionId, String>()

    fun submit(summary: StatsSummary?, sections: List<ChartSection>) {
        items.clear()
        if (summary != null) items.add(Item.Summary(summary))
        sections.forEach { items.add(Item.Chart(it)) }
        // Drop stale selections for sections that disappeared after a filter change
        val activeIds = sections.mapTo(mutableSetOf()) { it.id }
        selectedEntryKeys.keys.retainAll(activeIds)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (val item = items[position]) {
        is Item.Summary -> TYPE_SUMMARY
        is Item.Chart -> when (item.section.kind) {
            ChartKind.RANKED_BARS -> TYPE_RANKED
            ChartKind.SCORE_HISTOGRAM -> TYPE_HISTOGRAM
            ChartKind.YEAR_TIMELINE -> TYPE_TIMELINE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUMMARY -> SummaryHolder(ItemStatsSummaryBinding.inflate(inflater, parent, false))
            else -> {
                val binding = ItemChartBinding.inflate(inflater, parent, false)
                val chartView: BaseStatsChartView = when (viewType) {
                    TYPE_HISTOGRAM -> ScoreHistogramView(parent.context)
                    TYPE_TIMELINE -> YearTimelineView(parent.context)
                    else -> RankedBarChartView(parent.context)
                }
                binding.chartHost.addView(
                    chartView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                ChartHolder(binding, chartView)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Summary -> (holder as SummaryHolder).bind(item.summary)
            is Item.Chart -> (holder as ChartHolder).bind(item.section)
        }
    }

    private class SummaryHolder(
        private val b: ItemStatsSummaryBinding,
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(summary: StatsSummary) {
            val context = b.root.context
            val isAnime = summary.mediaType == MediaType.ANIME

            b.summaryLabel1.setText(R.string.profile_titles)
            b.summaryLabel2.setText(if (isAnime) R.string.profile_episodes else R.string.profile_chapters)
            b.summaryLabel3.setText(if (isAnime) R.string.days_watched else R.string.profile_volumes)
            b.summaryLabel4.setText(R.string.profile_mean_score)

            b.summaryValue1.text = ChartBuilder.formatInt(summary.count)
            b.summaryValue2.text = ChartBuilder.formatInt(summary.episodesOrChapters)
            b.summaryValue3.text = if (isAnime) {
                ChartBuilder.formatDays(summary.minutesOrVolumes)
            } else {
                ChartBuilder.formatInt(summary.minutesOrVolumes)
            }
            b.summaryValue4.text = ChartBuilder.formatScore(summary.meanScore)

            val comparing = summary.compareUser != null
            b.summaryCompareLegend.isVisible = comparing
            b.summaryCompare1.isVisible = comparing
            b.summaryCompare2.isVisible = comparing
            b.summaryCompare3.isVisible = comparing
            b.summaryCompare4.isVisible = comparing

            if (comparing) {
                val primaryColor = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary)
                val compareColor = mutedCompareColor(b.root)
                b.summaryPrimaryDot.backgroundTintList = ColorStateList.valueOf(primaryColor)
                b.summaryCompareDot.backgroundTintList = ColorStateList.valueOf(compareColor)
                b.summaryPrimaryName.text = summary.primaryUser
                b.summaryCompareName.text = summary.compareUser

                b.summaryCompare1.text = context.getString(
                    R.string.stats_compare_vs,
                    ChartBuilder.formatInt(summary.compareCount ?: 0)
                )
                b.summaryCompare2.text = context.getString(
                    R.string.stats_compare_vs,
                    ChartBuilder.formatInt(summary.compareEpisodesOrChapters ?: 0)
                )
                val cmp3 = if (isAnime) {
                    ChartBuilder.formatDays(summary.compareMinutesOrVolumes ?: 0)
                } else {
                    ChartBuilder.formatInt(summary.compareMinutesOrVolumes ?: 0)
                }
                b.summaryCompare3.text = context.getString(R.string.stats_compare_vs, cmp3)
                b.summaryCompare4.text = context.getString(
                    R.string.stats_compare_vs,
                    ChartBuilder.formatScore(summary.compareMeanScore ?: 0f)
                )
            }
        }
    }

    private inner class ChartHolder(
        private val b: ItemChartBinding,
        private val chartView: BaseStatsChartView,
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(section: ChartSection) {
            val context = b.root.context
            b.typeText.text = section.title

            val isExpanded = section.id in expandedSections
            val canExpand = section.kind == ChartKind.RANKED_BARS &&
                section.entries.size > section.defaultCollapsedLimit

            val subtitleText = when {
                canExpand && !isExpanded -> context.getString(
                    R.string.stats_top_of,
                    section.defaultCollapsedLimit,
                    section.entries.size
                )
                canExpand && isExpanded -> "${section.entries.size}"
                else -> section.subtitle
            }
            b.chartSubtitle.text = subtitleText
            b.chartSubtitle.isVisible = !subtitleText.isNullOrBlank()

            b.chartLegend.isVisible = section.isComparing
            if (section.isComparing) {
                val primaryColor = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary)
                val compareColor = mutedCompareColor(b.root)
                b.legendPrimaryDot.backgroundTintList = ColorStateList.valueOf(primaryColor)
                b.legendCompareDot.backgroundTintList = ColorStateList.valueOf(compareColor)
                b.legendPrimaryName.text = section.primaryUser
                b.legendCompareName.text = section.compareUser
            }

            val selectedKey = selectedEntryKeys[section.id]
            when (chartView) {
                is RankedBarChartView -> {
                    val limit = if (isExpanded) Int.MAX_VALUE else section.defaultCollapsedLimit
                    chartView.bind(section, maxRows = limit, selectedKey = selectedKey)
                }
                is ScoreHistogramView -> chartView.bind(section, selectedKey = selectedKey)
                is YearTimelineView -> chartView.bind(section, selectedKey = selectedKey)
            }

            val selectedEntry = selectedKey?.let { key -> section.entries.firstOrNull { it.key == key } }
            updateSelectionBanner(section, selectedEntry)

            chartView.onEntrySelected = { entry ->
                if (entry == null) {
                    selectedEntryKeys.remove(section.id)
                } else {
                    selectedEntryKeys[section.id] = entry.key
                }
                updateSelectionBanner(section, entry)
            }

            b.chartSelectionBanner.setOnClickListener {
                selectedEntryKeys.remove(section.id)
                chartView.clearSelection()
                b.chartSelectionBanner.isVisible = false
            }

            b.chartExpandButton.isVisible = canExpand
            if (canExpand) {
                b.chartExpandButton.text = if (isExpanded) {
                    context.getString(R.string.stats_show_fewer)
                } else {
                    context.getString(R.string.stats_show_all, section.entries.size)
                }
                b.chartExpandButton.setIconResource(
                    if (isExpanded) R.drawable.ic_round_arrow_drop_up_24
                    else R.drawable.ic_round_expand_more_24
                )
                b.chartExpandButton.setOnClickListener {
                    if (section.id in expandedSections) {
                        expandedSections.remove(section.id)
                    } else {
                        expandedSections.add(section.id)
                    }
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos)
                    }
                }
            }

            b.openButton.setOnClickListener { onOpenFullScreen(section) }
        }

        private fun updateSelectionBanner(section: ChartSection, entry: ChartEntry?) {
            if (entry == null) {
                b.chartSelectionBanner.isVisible = false
            } else {
                b.chartSelectionText.text = ChartBuilder.formatEntryDetail(section, entry)
                b.chartSelectionBanner.isVisible = true
            }
        }
    }

    companion object {
        private const val TYPE_SUMMARY = 0
        private const val TYPE_RANKED = 1
        private const val TYPE_HISTOGRAM = 2
        private const val TYPE_TIMELINE = 3

        internal fun mutedCompareColor(view: View): Int {
            val base = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val alpha = (0.52f * 255f).roundToInt()
            return (base and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}
