package ani.dantotsu.others.imagesearch

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemSearchByImageBinding
import ani.dantotsu.databinding.ItemSearchByImageWeakBinding
import ani.dantotsu.loadImage
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * Matches, best first. Matches under [STRONG] are usually wrong, so they sit under their own small
 * heading and their badge drops the accent colour.
 */
class ImageSearchResultAdapter(
    results: List<ImageSearchViewModel.ImageResult>,
    private val onPick: (ImageSearchViewModel.ImageResult) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Big cards for likely matches (or the best guess when none is likely), then a heading and small rows. */
    private val big: List<ImageSearchViewModel.ImageResult>
    private val rows: List<Any>

    init {
        val sorted = results.sortedByDescending { it.similarity ?: 0.0 }
        big = sorted.filter { (it.similarity ?: 0.0) >= STRONG }.ifEmpty { sorted.take(1) }
        val small = sorted - big.toSet()
        rows = big + (if (small.isNotEmpty()) listOf(WEAK_HEADER) else emptyList()) + small
    }

    private class Header(val view: TextView) : RecyclerView.ViewHolder(view)
    private class Match(val binding: ItemSearchByImageBinding) : RecyclerView.ViewHolder(binding.root)
    private class Weak(val binding: ItemSearchByImageWeakBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = when {
        rows[position] === WEAK_HEADER -> 1
        rows[position] in big -> 0
        else -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1) {
            val c = parent.context
            val tv = TextView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setText(R.string.image_search_weaker)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.poppins_bold)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                val d = c.resources.displayMetrics.density
                setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (12 * d).toInt())
            }
            return Header(tv)
        }
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == 2) return Weak(ItemSearchByImageWeakBinding.inflate(inflater, parent, false))
        return Match(ItemSearchByImageBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val r = rows[position] as? ImageSearchViewModel.ImageResult ?: return
        val title = r.anilist?.title?.run { romaji ?: english ?: native }.orEmpty()
        val percent = ((r.similarity ?: 0.0) * 100).roundToInt()
        val strong = (r.similarity ?: 0.0) >= STRONG
        val time = toTimestamp(r.from)
        val episode = r.episode?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
        val c = holder.itemView.context
        holder.itemView.contentDescription = if (episode != null)
            c.getString(R.string.image_search_match_cd, title, episode, time, percent)
        else c.getString(R.string.image_search_match_movie_cd, title, time, percent)
        holder.itemView.setOnClickListener { onPick(r) }

        if (holder is Weak) {
            val w = holder.binding
            w.weakImage.loadImage(r.image)
            w.weakTitle.text = title
            w.weakMeta.text = if (episode != null) c.getString(R.string.image_search_weak_meta, episode, time) else time
            w.weakPercent.text = c.getString(R.string.image_search_percent, percent)
            return
        }
        val b = (holder as Match).binding

        b.itemCompactTitle.text = title
        b.itemImage.loadImage(r.image)
        b.timeStamp.text = time
        b.episodeNumber.isVisible = episode != null
        b.episodeNumber.text = episode
        b.itemTotal.text = c.getString(R.string.image_search_percent, percent)

        // strong matches wear the accent like a score badge; weak ones go neutral
        val badgeBg = if (strong) androidx.appcompat.R.attr.colorPrimary
        else com.google.android.material.R.attr.colorSurfaceContainerHighest
        val badgeFg = if (strong) com.google.android.material.R.attr.colorOnPrimary
        else com.google.android.material.R.attr.colorOnSurfaceVariant
        b.itemTotal.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(b.itemTotal, badgeBg))
        b.itemTotal.setTextColor(MaterialColors.getColor(b.itemTotal, badgeFg))
    }

    override fun getItemCount() = rows.size

    private fun toTimestamp(seconds: Double?): String {
        val s = (seconds ?: 0.0).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        /** The matching service says results below 90% are most likely wrong. */
        const val STRONG = 0.9
        private val WEAK_HEADER = Any()
    }
}
