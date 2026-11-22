package ani.dantotsu.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.databinding.FragmentStatisticsBinding
import ani.dantotsu.others.setDropdownText
import ani.dantotsu.profile.ChartBuilder.MediaType
import ani.dantotsu.profile.ChartBuilder.StatType
import ani.dantotsu.setBaseline
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.markSegment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var activity: ProfileActivity
    private lateinit var user: Query.UserProfile
    private lateinit var adapter: StatsAdapter

    private var primaryStats: Query.StatisticsUser? = null
    private var compareStats: Query.StatisticsUser? = null
    private var isComparing: Boolean = false
    private var hasLoadedOnce: Boolean = false

    private var mediaType: MediaType = MediaType.ANIME
    private var statType: StatType = StatType.COUNT

    private var fetchJob: Job? = null
    private var buildJob: Job? = null
    private var compareJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as ProfileActivity
        user = arguments?.getSerializableCompat<Query.UserProfile>("user") as Query.UserProfile

        mediaType = MediaType.fromOrdinal(PrefManager.getVal(PrefName.StatsMediaType))
        statType = StatType.fromOrdinal(PrefManager.getVal(PrefName.StatsMetric))

        adapter = StatsAdapter { section ->
            SingleStatActivity.chartSection = section
            startActivity(Intent(requireContext(), SingleStatActivity::class.java))
        }

        binding.statisticList.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
            adapter = this@StatsFragment.adapter
            itemAnimator = null
            isNestedScrollingEnabled = true
            setBaseline(activity.binding.profileNavBarContainer)
        }

        setupControls()

        binding.statsSwipeRefresh.setOnRefreshListener {
            fetchStatistics(forceRefresh = true)
        }

        fetchStatistics(forceRefresh = false)
    }

    private fun setupControls() {
        val b = binding
        val initialSegmentId = if (mediaType == MediaType.ANIME) R.id.segmentAnime else R.id.segmentManga
        b.mediaTypeGroup.check(initialSegmentId)
        listOf(b.segmentAnime, b.segmentManga).forEach { markSegment(it, null, it.isChecked) }

        b.mediaTypeGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            group.findViewById<com.google.android.material.button.MaterialButton>(checkedId)?.let {
                markSegment(it, null, isChecked)
            }
            if (!isChecked) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.segmentManga) MediaType.MANGA else MediaType.ANIME
            if (next != mediaType) {
                mediaType = next
                PrefManager.setVal(PrefName.StatsMediaType, mediaType.ordinal)
                updateMetricChipLabel()
                rebuildCharts()
            }
        }

        updateMetricChipLabel()
        b.metricChip.setOnClickListener {
            b.metricChip.isChecked = true
            val options = StatType.entries.map { ChoiceOption(metricLabel(it)) }
            ChoiceSheet.single(
                requireContext(),
                getString(R.string.stats_view_by),
                options,
                statType.ordinal,
            ) { picked ->
                val next = StatType.fromOrdinal(picked)
                if (next != statType) {
                    statType = next
                    PrefManager.setVal(PrefName.StatsMetric, statType.ordinal)
                    updateMetricChipLabel()
                    rebuildCharts()
                }
            }
        }

        val canCompare = Anilist.userid != null && user.id != Anilist.userid
        b.compareChip.isVisible = canCompare
        b.compareChip.isChecked = isComparing
        b.compareChip.setOnCheckedChangeListener { _, checked ->
            if (checked == isComparing) return@setOnCheckedChangeListener
            if (checked) {
                enableCompare()
            } else {
                compareJob?.cancel()
                isComparing = false
                rebuildCharts()
            }
        }
    }

    private fun metricLabel(metric: StatType): String = when (metric) {
        StatType.COUNT -> getString(R.string.stats_metric_count)
        StatType.TIME -> getString(
            if (mediaType == MediaType.ANIME) R.string.stats_metric_hours
            else R.string.stats_metric_chapters
        )
        StatType.AVG_SCORE -> getString(R.string.stats_metric_score)
    }

    private fun updateMetricChipLabel() {
        val label = metricLabel(statType)
        binding.metricChip.setDropdownText(label)
        binding.metricChip.isChecked = true
    }

    private fun fetchStatistics(forceRefresh: Boolean) {
        if (!forceRefresh && hasLoadedOnce && primaryStats != null) {
            binding.filterContainer.isVisible = true
            binding.statisticProgressBar.isVisible = false
            rebuildCharts()
            return
        }
        fetchJob?.cancel()
        if (!forceRefresh) {
            binding.statisticProgressBar.isVisible = true
            binding.statsEmptyContainer.isVisible = false
        }
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val fetchedPrimary = withContext(Dispatchers.IO) {
                runCatching { Anilist.query.getUserStatistics(user.id)?.data?.user }.getOrNull()
            }
            val fetchedCompare = if (isComparing && Anilist.userid != null && Anilist.userid != user.id) {
                withContext(Dispatchers.IO) {
                    runCatching { Anilist.query.getUserStatistics(Anilist.userid!!)?.data?.user }.getOrNull()
                }
            } else {
                compareStats
            }
            val b = _binding ?: return@launch
            b.statsSwipeRefresh.isRefreshing = false
            b.statisticProgressBar.isVisible = false
            hasLoadedOnce = true

            if (fetchedPrimary != null) {
                primaryStats = fetchedPrimary
                if (isComparing) compareStats = fetchedCompare
                b.filterContainer.isVisible = true
                rebuildCharts()
            } else if (primaryStats == null) {
                b.filterContainer.isVisible = false
                showErrorState()
            } else {
                snackString(R.string.stats_error_title)
            }
        }
    }

    private fun enableCompare() {
        val selfId = Anilist.userid ?: return
        if (compareStats != null) {
            isComparing = true
            rebuildCharts()
            return
        }
        compareJob?.cancel()
        binding.statisticProgressBar.isVisible = true
        compareJob = viewLifecycleOwner.lifecycleScope.launch {
            val selfStats = withContext(Dispatchers.IO) {
                runCatching { Anilist.query.getUserStatistics(selfId)?.data?.user }.getOrNull()
            }
            val b = _binding ?: return@launch
            b.statisticProgressBar.isVisible = false
            if (selfStats != null) {
                compareStats = selfStats
                isComparing = true
                rebuildCharts()
            } else {
                isComparing = false
                b.compareChip.isChecked = false
                snackString(R.string.stats_compare_failed)
            }
        }
    }

    private fun rebuildCharts() {
        val primary = primaryStats ?: return
        val compare = if (isComparing) compareStats else null
        val activeMedia = mediaType
        val activeMetric = statType
        val youLabel = getString(R.string.stats_compare_you)

        buildJob?.cancel()
        buildJob = viewLifecycleOwner.lifecycleScope.launch {
            val payload = withContext(Dispatchers.Default) {
                ChartBuilder.buildProfileStats(
                    users = listOfNotNull(primary, compare),
                    mediaType = activeMedia,
                    statType = activeMetric,
                    compareLabelOverride = youLabel,
                )
            }
            val b = _binding ?: return@launch
            if (payload.isEmpty) {
                adapter.submit(null, emptyList())
                b.statisticList.isVisible = false
                showEmptyMediaState(payload.hasOtherMediaStats)
            } else {
                b.statsEmptyContainer.isVisible = false
                b.statisticList.isVisible = true
                adapter.submit(payload.summary, payload.sections)
            }
        }
    }

    private fun showEmptyMediaState(hasOtherMediaStats: Boolean) {
        val b = binding
        val isAnime = mediaType == MediaType.ANIME
        b.statsEmptyTitle.setText(if (isAnime) R.string.stats_empty_anime_title else R.string.stats_empty_manga_title)
        b.statsEmptyBody.setText(R.string.stats_empty_body)
        b.statsEmptyAction.isVisible = hasOtherMediaStats
        if (hasOtherMediaStats) {
            b.statsEmptyAction.setText(if (isAnime) R.string.stats_switch_to_manga else R.string.stats_switch_to_anime)
            b.statsEmptyAction.setOnClickListener {
                b.mediaTypeGroup.check(if (isAnime) R.id.segmentManga else R.id.segmentAnime)
            }
        }
        b.statsEmptyContainer.isVisible = true
    }

    private fun showErrorState() {
        val b = binding
        b.statisticList.isVisible = false
        b.statsEmptyTitle.setText(R.string.stats_error_title)
        b.statsEmptyBody.setText(R.string.stats_error_body)
        b.statsEmptyAction.setText(R.string.stats_retry)
        b.statsEmptyAction.isVisible = true
        b.statsEmptyAction.setOnClickListener { fetchStatistics(forceRefresh = true) }
        b.statsEmptyContainer.isVisible = true
    }

    override fun onResume() {
        super.onResume()
        _binding?.statisticList?.setBaseline(activity.binding.profileNavBarContainer)
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        buildJob?.cancel()
        compareJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(user: Query.UserProfile): StatsFragment {
            val args = Bundle().apply {
                putSerializable("user", user)
            }
            return StatsFragment().apply {
                arguments = args
            }
        }
    }
}
