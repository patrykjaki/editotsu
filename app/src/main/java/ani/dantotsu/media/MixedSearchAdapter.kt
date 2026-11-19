package ani.dantotsu.media

import android.annotation.SuppressLint
import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.MixedSearchResults
import ani.dantotsu.databinding.ItemMixedCompactRowBinding
import ani.dantotsu.databinding.ItemMixedMediaShelfBinding
import ani.dantotsu.databinding.ItemMixedSectionHeaderBinding
import ani.dantotsu.loadImage
import ani.dantotsu.openLinkInCustomTab
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.User
import ani.dantotsu.px
import ani.dantotsu.settings.rows.SettingsCards
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.Serializable

/**
 * Multi-section adapter for mixed "All" search (opened from Home):
 * Anime and Manga as horizontal cover-card shelves, Characters, Staff, Studios and Users as
 * grouped compact rows, each headed by a title + "see all" arrow that switches to that type.
 */
class MixedSearchAdapter(
    private val activity: FragmentActivity,
    private val onSeeAll: (SearchType) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val type: SearchType, val titleRes: Int, val first: Boolean) : Row
        data class MediaShelf(val items: MutableList<Media>) : Row
        data class CharacterRow(val item: Character, val index: Int, val count: Int) : Row
        data class StaffRow(val item: Author, val index: Int, val count: Int) : Row
        data class StudioRow(val item: Studio, val index: Int, val count: Int) : Row
        data class UserRow(val item: User, val index: Int, val count: Int) : Row
    }

    private var rows: List<Row> = emptyList()
    private val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(results: MixedSearchResults?) {
        rows = if (results == null) emptyList() else buildRows(results)
        notifyDataSetChanged()
    }

    private fun buildRows(r: MixedSearchResults): List<Row> {
        val out = ArrayList<Row>()
        fun addSectionHeader(type: SearchType, titleRes: Int) {
            out += Row.Header(type, titleRes, first = out.isEmpty())
        }
        if (r.anime.isNotEmpty()) {
            addSectionHeader(SearchType.ANIME, R.string.anime)
            out += Row.MediaShelf(r.anime)
        }
        if (r.manga.isNotEmpty()) {
            addSectionHeader(SearchType.MANGA, R.string.manga)
            out += Row.MediaShelf(r.manga)
        }
        if (r.characters.isNotEmpty()) {
            addSectionHeader(SearchType.CHARACTER, R.string.characters)
            r.characters.forEachIndexed { idx, item ->
                out += Row.CharacterRow(item, idx, r.characters.size)
            }
        }
        if (r.staff.isNotEmpty()) {
            addSectionHeader(SearchType.STAFF, R.string.staff)
            r.staff.forEachIndexed { idx, item ->
                out += Row.StaffRow(item, idx, r.staff.size)
            }
        }
        if (r.studios.isNotEmpty()) {
            addSectionHeader(SearchType.STUDIO, R.string.studios)
            r.studios.forEachIndexed { idx, item ->
                out += Row.StudioRow(item, idx, r.studios.size)
            }
        }
        if (r.users.isNotEmpty()) {
            addSectionHeader(SearchType.USER, R.string.users)
            r.users.forEachIndexed { idx, item ->
                out += Row.UserRow(item, idx, r.users.size)
            }
        }
        return out
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.MediaShelf -> TYPE_SHELF
        else -> TYPE_COMPACT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemMixedSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_SHELF -> ShelfVH(ItemMixedMediaShelfBinding.inflate(inflater, parent, false))
            else -> CompactVH(ItemMixedCompactRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> bindHeader(holder as HeaderVH, row)
            is Row.MediaShelf -> bindShelf(holder as ShelfVH, row)
            is Row.CharacterRow -> bindCompact(
                holder as CompactVH,
                title = row.item.name.orEmpty(),
                subtitle = null,
                imageUrl = row.item.image,
                circleAvatar = false,
                index = row.index,
                count = row.count,
            ) { b ->
                ContextCompat.startActivity(
                    activity,
                    Intent(activity, CharacterDetailsActivity::class.java)
                        .putExtra("character", row.item as Serializable),
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        Pair.create(
                            b.mixedRowImage,
                            ViewCompat.getTransitionName(b.mixedRowImage) ?: "mediaCover",
                        ),
                    ).toBundle(),
                )
            }

            is Row.StaffRow -> bindCompact(
                holder as CompactVH,
                title = row.item.name,
                subtitle = row.item.role?.takeIf { it.isNotBlank() && !it.equals("STAFF", true) },
                imageUrl = row.item.image,
                circleAvatar = false,
                index = row.index,
                count = row.count,
            ) { b ->
                ContextCompat.startActivity(
                    activity,
                    Intent(activity, AuthorActivity::class.java)
                        .putExtra("author", row.item as Serializable),
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        Pair.create(
                            b.mixedRowImage,
                            ViewCompat.getTransitionName(b.mixedRowImage) ?: "mediaCover",
                        ),
                    ).toBundle(),
                )
            }

            is Row.StudioRow -> bindCompact(
                holder as CompactVH,
                title = row.item.name,
                subtitle = null,
                imageUrl = row.item.imageUrl,
                circleAvatar = false,
                index = row.index,
                count = row.count,
            ) { b ->
                ContextCompat.startActivity(
                    activity,
                    Intent(activity, StudioActivity::class.java)
                        .putExtra("studio", row.item as Serializable),
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        Pair.create(
                            b.mixedRowImage,
                            ViewCompat.getTransitionName(b.mixedRowImage) ?: "mediaCover",
                        ),
                    ).toBundle(),
                )
            }

            is Row.UserRow -> bindCompact(
                holder as CompactVH,
                title = row.item.name,
                subtitle = null,
                imageUrl = row.item.pfp,
                circleAvatar = true,
                index = row.index,
                count = row.count,
            ) {
                if (rescueMode) {
                    val malUrl = row.item.banner ?: "https://myanimelist.net/profile/${row.item.name}"
                    openLinkInCustomTab(malUrl)
                } else {
                    ContextCompat.startActivity(
                        activity,
                        Intent(activity, ProfileActivity::class.java).putExtra("userId", row.item.id),
                        null,
                    )
                }
            }
        }
    }

    private fun bindHeader(holder: HeaderVH, row: Row.Header) {
        val title = activity.getString(row.titleRes)
        holder.binding.mixedSectionTitle.text = title
        holder.binding.mixedSectionHeader.contentDescription =
            activity.getString(R.string.search_see_all, title)
        holder.binding.mixedSectionHeader.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = if (row.first) 4f.px else 16f.px
        }
        holder.binding.mixedSectionHeader.setOnClickListener { onSeeAll(row.type) }
    }

    private fun bindShelf(holder: ShelfVH, row: Row.MediaShelf) {
        val rv = holder.binding.mixedMediaShelf
        if (rv.layoutManager == null) {
            rv.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        }
        rv.adapter = MediaAdaptor(0, row.items, activity)
    }

    private fun bindCompact(
        holder: CompactVH,
        title: String,
        subtitle: String?,
        imageUrl: String?,
        circleAvatar: Boolean,
        index: Int,
        count: Int,
        onClick: (ItemMixedCompactRowBinding) -> Unit,
    ) {
        val b = holder.binding
        SettingsCards.apply(b.mixedRowRoot, first = index == 0, last = index == count - 1)
        b.mixedRowTitle.text = title
        b.mixedRowSubtitle.isVisible = !subtitle.isNullOrBlank()
        b.mixedRowSubtitle.text = subtitle.orEmpty()
        val shapeRes = if (circleAvatar) R.style.roundedImageView else R.style.roundedImageViewSmall
        b.mixedRowImage.shapeAppearanceModel =
            ShapeAppearanceModel.builder(b.root.context, 0, shapeRes).build()
        b.mixedRowImage.loadImage(imageUrl)
        b.mixedRowRoot.setOnClickListener { onClick(b) }
    }

    private class HeaderVH(val binding: ItemMixedSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class ShelfVH(val binding: ItemMixedMediaShelfBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class CompactVH(val binding: ItemMixedCompactRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_HEADER = 1
        private const val TYPE_SHELF = 2
        private const val TYPE_COMPACT = 3
    }
}
