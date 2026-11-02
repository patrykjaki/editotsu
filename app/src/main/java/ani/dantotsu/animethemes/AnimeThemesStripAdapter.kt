package ani.dantotsu.animethemes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
        if (isShielded(track)) {
            b.themeSong.text = context.getString(
                R.string.theme_hidden_until, ThemeQueue.firstEpisode(track.episodes) ?: 0
            )
            b.themeArtist.visibility = View.GONE
        } else {
            b.themeSong.text = track.songTitle
            b.themeArtist.text = track.artist
            b.themeArtist.visibility = if (track.artist.isBlank()) View.GONE else View.VISIBLE
        }
        b.root.setOnClickListener { onPlay(track) }
    }
}
