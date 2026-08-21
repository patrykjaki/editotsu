package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetTrackGroupsBinding
import ani.dantotsu.databinding.ItemSubtitleTextBinding
import ani.dantotsu.media.anime.player.PlayerTrack
import ani.dantotsu.media.anime.player.TrackType
import java.util.Locale

class TrackGroupDialogFragment(
    private var instance: ExoplayerView,
    private var tracks: List<PlayerTrack>,
    private var type: TrackType
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTrackGroupsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTrackGroupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (type == TrackType.AUDIO) {
            binding.selectionTitle.text = getString(R.string.audio_tracks)
        } else {
            binding.selectionTitle.text = getString(R.string.subtitles)
        }

        binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.subtitlesRecycler.adapter = TrackGroupAdapter()
    }

    inner class TrackGroupAdapter : RecyclerView.Adapter<TrackGroupAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemSubtitleTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        // Item 0 is "Off / Disabled", subsequent items are tracks
        override fun getItemCount(): Int = tracks.size + 1

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val itemBinding = holder.binding

            if (position == 0) {
                val isAnySelected = tracks.any { it.selected }
                val title = getString(R.string.disabled_track)
                itemBinding.subtitleTitle.text = if (!isAnySelected) "✔ $title" else title
                itemBinding.root.setOnClickListener {
                    dismiss()
                    instance.onSelectTrack(null, type)
                }
                return
            }

            val track = tracks[position - 1]
            val lang = track.language?.lowercase()
            val locale = if (!lang.isNullOrBlank()) {
                if (lang.contains("-")) {
                    val parts = lang.split("-")
                    try { Locale(parts[0], parts[1]) } catch (_: Exception) { null }
                } else {
                    try { Locale(lang) } catch (_: Exception) { null }
                }
            } else {
                null
            }

            val displayLabel = track.name ?: locale?.displayName ?: getString(R.string.unknown_track, "Track #${track.id}")
            val formattedTitle = if (locale != null) "[${locale.language}] $displayLabel" else displayLabel

            itemBinding.subtitleTitle.text = if (track.selected) "✔ $formattedTitle" else formattedTitle
            itemBinding.root.setOnClickListener {
                dismiss()
                instance.onSelectTrack(track, type)
            }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
