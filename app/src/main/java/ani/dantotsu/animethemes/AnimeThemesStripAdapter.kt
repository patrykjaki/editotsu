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

/** Horizontal openings & endings cards on the details screen. */
class AnimeThemesStripAdapter(
    private val tracks: List<ThemeTrack>,
    private val isShielded: (ThemeTrack) -> Boolean,
    private val onPlay: (ThemeTrack) -> Unit,
) : RecyclerView.Adapter<AnimeThemesStripAdapter.Holder>() {

    class Holder(val binding: ItemAnimeThemeCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAnimeThemeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = tracks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        val b = holder.binding
        val context = b.root.context
        b.themeTag.text = track.tag
        b.themeEpisodes.text = ThemeQueue.episodesLabel(track.episodes).orEmpty()
        val flag = when (track.warning) {
            ThemeWarningType.NONE -> null
            ThemeWarningType.SPOILER -> context.getString(R.string.theme_flag_spoiler)
            ThemeWarningType.NSFW -> context.getString(R.string.theme_flag_nsfw)
            ThemeWarningType.SPOILER_AND_NSFW ->
                context.getString(R.string.theme_flag_spoiler) + " · " + context.getString(R.string.theme_flag_nsfw)
        }
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
        b.root.contentDescription = listOf(
            track.tag,
            ThemeQueue.episodesLabel(track.episodes),
            b.themeSong.text.toString(),
            b.themeArtist.text.toString().takeIf { it.isNotBlank() },
            flag,
        ).filterNotNull().joinToString(", ")
        b.root.setOnClickListener { onPlay(track) }
    }

    private fun onSurface(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)

    private fun onSurfaceVariant(context: Context) =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
}
