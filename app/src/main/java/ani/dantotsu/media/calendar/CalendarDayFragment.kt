package ani.dantotsu.media.calendar

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentCalendarDayBinding
import ani.dantotsu.databinding.ItemCalendarEntryBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.OtherDetailsViewModel
import java.io.Serializable

/** One day of the calendar: the episodes airing on it, in airing order, one per row. */
class CalendarDayFragment : Fragment() {
    private var _binding: FragmentCalendarDayBinding? = null
    private val binding get() = _binding!!
    private val model: OtherDetailsViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalendarDayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val position = requireArguments().getInt(ARG_POSITION)
        val filtered = requireArguments().getBoolean(ARG_FILTERED)
        binding.calendarDayList.layoutManager = LinearLayoutManager(requireContext())
        model.getCalendar().observe(viewLifecycleOwner) { week ->
            val day = week?.entries?.toList()?.getOrNull(position) ?: return@observe
            val past = CalendarWeek.isPast(day.key)
            binding.calendarDayList.adapter = Adapter(day.value.toList(), past)
            binding.calendarDayEmpty.visibility = if (day.value.isEmpty()) View.VISIBLE else View.GONE
            binding.calendarDayEmpty.setText(
                if (filtered) R.string.calendar_day_empty_filtered else R.string.calendar_day_empty,
            )
        }
    }

    override fun onDestroyView() {
        binding.calendarDayList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private inner class Adapter(private val items: List<Media>, private val past: Boolean) :
        RecyclerView.Adapter<Adapter.Row>() {
        inner class Row(val b: ItemCalendarEntryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Row(ItemCalendarEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val media = items[position]
            val slot = CalendarWeek.slot(media.relation)
            val b = holder.b
            // "1:30 AM" goes on two lines ("1:30" over "AM") so every row's time is the same shape
            b.calendarEntryTime.text = slot.time.replace(Regex("[\\s\u00a0\u202f]+"), "\n")
            b.calendarEntryTitle.text = media.userPreferredName
            b.calendarEntryEpisode.text = slot.episode
            b.calendarEntryCover.loadImage(media.cover)
            // the same badge the cover grids use: the user's score when there is one, else the average
            b.calendarEntryScore.text = ani.dantotsu.connections.anilist.cardScoreText(media.userScore, media.meanScore)
            b.calendarEntryScoreBG.background = ContextCompat.getDrawable(
                requireContext(),
                if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score,
            )
            val seen = media.userProgress
            b.calendarEntryMine.visibility = if (media.userStatus != null) View.VISIBLE else View.GONE
            b.calendarEntryMine.text = when {
                media.userStatus == null -> ""
                seen != null && seen > 0 -> getString(R.string.calendar_on_list_seen, seen)
                else -> getString(R.string.calendar_on_list)
            }
            // a day that is over reads as history
            b.root.alpha = if (past) 0.7f else 1f
            b.root.setOnClickListener {
                if (media.id < 0) return@setOnClickListener
                ContextCompat.startActivity(
                    requireContext(),
                    Intent(requireContext(), MediaDetailsActivity::class.java).putExtra("media", media as Serializable),
                    null,
                )
            }
        }
    }

    companion object {
        private const val ARG_POSITION = "position"
        private const val ARG_FILTERED = "filtered"

        fun newInstance(position: Int, filtered: Boolean) = CalendarDayFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_POSITION, position)
                putBoolean(ARG_FILTERED, filtered)
            }
        }
    }
}
