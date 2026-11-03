package ani.dantotsu.animethemes

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemAnimeThemeCardBinding

/**
 * Horizontal openings & endings cards on the details screen. A card plays one version
 * of its theme; cards with several versions get a "N versions" button that opens the list.
 */
class AnimeThemesStripAdapter(
    private val choices: List<ThemeChoice>,
    private val isShielded: (ThemeTrack) -> Boolean,
    private val fragmentManager: androidx.fragment.app.FragmentManager,
    private val onPlay: (tapped: ThemeTrack, shown: List<ThemeTrack>) -> Unit,
) : RecyclerView.Adapter<AnimeThemesStripAdapter.Holder>() {

    class Holder(val binding: ItemAnimeThemeCardBinding) : RecyclerView.ViewHolder(binding.root)

    /** Version each card shows; starts at the shield-aware default, changes when one is chosen. */
    private val shown: MutableList<ThemeTrack> =
        choices.map { ThemeQueue.preferredVersion(it, isShielded) }.toMutableList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAnimeThemeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = choices.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = shown[position]
        val choice = choices[position]
        val b = holder.binding
        val context = b.root.context
        b.themeTag.text = track.tag
        b.themeEpisodes.text = ThemeQueue.episodesLabel(track.episodes).orEmpty()
        val flag = themeFlagText(context, track.warning)
        b.themeFlag.text = flag
        b.themeFlag.visibility = if (flag == null) View.GONE else View.VISIBLE
        val shielded = isShielded(track)
        if (shielded) {
            b.themeSong.text = context.getString(
                R.string.theme_hidden_until, ThemeQueue.firstEpisode(track.episodes) ?: 0
            )
            b.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins)
            b.themeSong.setTextColor(onSurfaceVariant(context))
            b.themeArtist.text = ""
        } else {
            b.themeSong.text = track.songTitle
            b.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
            b.themeSong.setTextColor(onSurface(context))
            b.themeArtist.text = track.artist
        }
        val count = choice.versions.size
        val versionsText = if (count > 1) {
            context.resources.getQuantityString(R.plurals.theme_versions, count, count)
        } else null
        b.themeVersions.visibility = if (versionsText == null) View.GONE else View.VISIBLE
        b.themeVersions.text = versionsText
        b.themeVersions.contentDescription = versionsText?.let {
            context.getString(R.string.theme_versions_action, track.tag, it)
        }
        b.themeVersions.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { showVersions(it) }
        }
        b.root.contentDescription = listOf(
            if (count > 1) "${track.tag} v${track.version}" else track.tag,
            ThemeQueue.episodesLabel(track.episodes),
            b.themeSong.text.toString(),
            b.themeArtist.text.toString().takeIf { it.isNotBlank() },
            flag,
        ).filterNotNull().joinToString(", ")
        b.root.setOnClickListener { onPlay(track, shown.toList()) }
    }

    private fun showVersions(position: Int) {
        val choice = choices[position]
        ThemeVersionsSheet.newInstance(choice.versions, shown[position].entryId, isShielded) { picked ->
            shown[position] = picked
            notifyItemChanged(position)
            onPlay(picked, shown.toList())
        }.show(fragmentManager, "themeVersions")
    }

    private fun onSurface(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)

    private fun onSurfaceVariant(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
}
