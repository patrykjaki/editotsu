package ani.dantotsu.animethemes

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetThemeVersionsBinding
import ani.dantotsu.databinding.ItemThemeVersionRowBinding
import com.google.android.material.color.MaterialColors

/** A theme's AnimeThemes versions; a tap plays that version. */
class ThemeVersionsSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetThemeVersionsBinding? = null
    private val binding get() = _binding!!

    private var versions: List<ThemeTrack> = emptyList()
    private var currentEntryId: Int = 0
    private var isShielded: (ThemeTrack) -> Boolean = { false }
    private var onPick: ((ThemeTrack) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetThemeVersionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // The callback does not survive recreation; the strip opens the sheet again.
        if (onPick == null || versions.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        val context = requireContext()
        val current = versions.firstOrNull { it.entryId == currentEntryId } ?: versions.first()
        val hidden = isShielded(current)
        binding.themeVersionsTitle.text =
            if (hidden || current.songTitle.isBlank()) current.tag else "${current.tag} · ${current.songTitle}"
        binding.themeVersionsArtist.text = if (hidden) "" else current.artist
        binding.themeVersionsArtist.visibility = if (hidden || current.artist.isBlank()) View.GONE else View.VISIBLE
        binding.themeVersionsCount.text =
            context.resources.getQuantityString(R.plurals.theme_versions, versions.size, versions.size)

        versions.forEach { v ->
            val row = ItemThemeVersionRowBinding.inflate(layoutInflater, binding.themeVersionsList, false)
            val shielded = isShielded(v)
            val first = ThemeQueue.firstEpisode(v.episodes)
            row.versionNumber.text = "v${v.version}"
            if (shielded && first != null) {
                row.versionEpisodes.text = context.getString(R.string.theme_hidden_until, first)
                row.versionEpisodes.typeface = ResourcesCompat.getFont(context, R.font.poppins)
                row.versionEpisodes.setTextColor(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
            } else {
                row.versionEpisodes.text = ThemeQueue.episodesLabel(v.episodes) ?: v.tag
            }
            val flag = themeFlagText(context, v.warning)
            row.versionFlag.text = flag
            row.versionFlag.visibility = if (flag == null) View.GONE else View.VISIBLE
            val selected = v.entryId == current.entryId
            row.root.strokeWidth = if (selected) (2 * resources.displayMetrics.density).toInt() else 0
            row.root.isSelected = selected
            row.root.contentDescription = listOfNotNull(
                "v${v.version}", row.versionEpisodes.text.toString(), flag,
            ).joinToString(", ")
            row.root.setOnClickListener {
                onPick?.invoke(v)
                dismiss()
            }
            binding.themeVersionsList.addView(row.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun color(context: Context, attr: Int) = MaterialColors.getColor(context, attr, 0)

    companion object {
        fun newInstance(
            versions: List<ThemeTrack>,
            currentEntryId: Int,
            isShielded: (ThemeTrack) -> Boolean,
            onPick: (ThemeTrack) -> Unit,
        ) = ThemeVersionsSheet().apply {
            this.versions = versions
            this.currentEntryId = currentEntryId
            this.isShielded = isShielded
            this.onPick = onPick
        }
    }
}

/** "Spoiler", "NSFW", "Spoiler · NSFW"; null when AnimeThemes flags nothing. */
internal fun themeFlagText(context: Context, warning: ThemeWarningType): String? = when (warning) {
    ThemeWarningType.NONE -> null
    ThemeWarningType.SPOILER -> context.getString(R.string.theme_flag_spoiler)
    ThemeWarningType.NSFW -> context.getString(R.string.theme_flag_nsfw)
    ThemeWarningType.SPOILER_AND_NSFW ->
        context.getString(R.string.theme_flag_spoiler) + " · " + context.getString(R.string.theme_flag_nsfw)
}
