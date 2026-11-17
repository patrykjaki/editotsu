package ani.dantotsu.media

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ItemChoiceGroupBinding
import ani.dantotsu.databinding.ItemGenreTagOptionBinding
import ani.dantotsu.databinding.SheetGenreTagBinding
import ani.dantotsu.media.GenreTagGroups.Entry
import ani.dantotsu.media.GenreTagGroups.Kind
import ani.dantotsu.media.GenreTagGroups.Mark
import ani.dantotsu.settings.rows.SettingsCards
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/**
 * Unified genres & tags sheet: search field on top, genres group first, then tag groups by category.
 * Every row has a tri-state box that cycles blank -> ✓ include -> ✕ exclude (in the theme's error colour).
 */
object GenreTagSheet {

    fun show(
        context: Context,
        filters: AniMangaSearchResults,
        rescue: Boolean = PrefManager.getVal(PrefName.RescueMode),
        onChanged: () -> Unit,
    ): BottomSheetDialog? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
        val genres = Anilist.genres.orEmpty().filter { Anilist.adult || !it.equals("hentai", true) }
        val tags = if (rescue) emptyList() else Anilist.tags?.get(filters.isAdult).orEmpty()
        val categories = Anilist.tagCategories.ifEmpty {
            GenreTagGroups.decodeCategories(PrefManager.getVal(PrefName.TagCategories))
        }
        val allGroups = GenreTagGroups.groups(
            genres = genres,
            tags = tags,
            categories = categories,
            genresTitle = context.getString(R.string.genres),
            otherTitle = context.getString(R.string.tags),
        )
        if (allGroups.isEmpty()) return null

        val incGenres = (filters.genres ?: mutableListOf()).toMutableList()
        val excGenres = (filters.excludedGenres ?: mutableListOf()).toMutableList()
        val incTags = (filters.tags ?: mutableListOf()).toMutableList()
        val excTags = (filters.excludedTags ?: mutableListOf()).toMutableList()

        val dialog = BottomSheetDialog(context)
        val b = SheetGenreTagBinding.inflate(LayoutInflater.from(context))

        fun hasAny() = incGenres.isNotEmpty() || excGenres.isNotEmpty() || incTags.isNotEmpty() || excTags.isNotEmpty()

        fun syncToFilters() {
            filters.genres = incGenres.toMutableList()
            filters.excludedGenres = excGenres.toMutableList()
            filters.tags = incTags.toMutableList()
            filters.excludedTags = excTags.toMutableList()
            b.genreTagReset.isVisible = hasAny()
            onChanged()
        }

        fun markOf(entry: Entry): Mark = when (entry.kind) {
            Kind.GENRE -> GenreTagGroups.markOf(entry.name, incGenres, excGenres)
            Kind.TAG -> GenreTagGroups.markOf(entry.name, incTags, excTags)
        }

        fun cycle(entry: Entry): Mark {
            val next = markOf(entry).next()
            when (entry.kind) {
                Kind.GENRE -> GenreTagGroups.set(entry.name, next, incGenres, excGenres)
                Kind.TAG -> GenreTagGroups.set(entry.name, next, incTags, excTags)
            }
            syncToFilters()
            return next
        }

        val adapter = Adapter(
            items = buildItems(allGroups),
            markOf = ::markOf,
            onCycle = ::cycle,
        )
        b.genreTagList.layoutManager = LinearLayoutManager(context)
        b.genreTagList.adapter = adapter
        b.genreTagList.itemAnimator = null

        b.genreTagReset.isVisible = hasAny()
        b.genreTagReset.setOnClickListener {
            incGenres.clear()
            excGenres.clear()
            incTags.clear()
            excTags.clear()
            syncToFilters()
            adapter.notifyDataSetChanged()
        }

        b.genreTagSearchText.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            b.genreTagSearchClear.isVisible = q.isNotEmpty()
            adapter.update(buildItems(GenreTagGroups.search(allGroups, q)))
        }
        b.genreTagSearchClear.setOnClickListener {
            b.genreTagSearchText.setText("")
            b.genreTagSearchText.requestFocus()
        }

        dialog.setContentView(b.root)
        val basePadding = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = basePadding + nav)
            insets
        }
        dialog.setTitle(b.genreTagTitle.text)

        val metrics = context.resources.displayMetrics
        dialog.behavior.apply {
            peekHeight = (metrics.heightPixels * 0.7f).toInt()
            skipCollapsed = false
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        val firstMarked = adapter.items.indexOfFirst { it is Item.Option && markOf(it.entry) != Mark.NONE }
        if (firstMarked > 0) {
            (b.genreTagList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(firstMarked, (metrics.heightPixels * 0.15f).toInt())
        }
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface))
        ViewCompat.requestApplyInsets(b.root)
        return dialog
    }

    private sealed interface Item {
        class Group(val title: String) : Item
        class Option(val entry: Entry) : Item
    }

    private fun buildItems(groups: List<GenreTagGroups.Group>): List<Item> {
        val out = ArrayList<Item>()
        for (group in groups) {
            out += Item.Group(group.title)
            group.entries.forEach { out += Item.Option(it) }
        }
        return out
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private class Adapter(
        var items: List<Item>,
        val markOf: (Entry) -> Mark,
        val onCycle: (Entry) -> Mark,
    ) : RecyclerView.Adapter<Holder>() {

        fun update(newItems: List<Item>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Group) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            return Holder(
                if (viewType == 0) ItemChoiceGroupBinding.inflate(inflater, parent, false).root
                else ItemGenreTagOptionBinding.inflate(inflater, parent, false).root
            )
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = items[position]) {
                is Item.Group -> {
                    val g = ItemChoiceGroupBinding.bind(holder.itemView)
                    g.groupTitle.text = item.title
                    g.groupDetail.isVisible = false
                }
                is Item.Option -> bindOption(ItemGenreTagOptionBinding.bind(holder.itemView), item.entry, position)
            }
        }

        private fun bindOption(o: ItemGenreTagOptionBinding, entry: Entry, position: Int) {
            val view = o.root
            val ctx = view.context
            val mark = markOf(entry)
            o.genreTagLabel.text = entry.name

            val (iconRes, tintAttr) = when (mark) {
                Mark.NONE -> R.drawable.ic_tri_box_blank to com.google.android.material.R.attr.colorOnSurfaceVariant
                Mark.INCLUDE -> R.drawable.ic_tri_box_include to androidx.appcompat.R.attr.colorPrimary
                Mark.EXCLUDE -> R.drawable.ic_tri_box_exclude to androidx.appcompat.R.attr.colorError
            }
            o.genreTagBox.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                o.genreTagBox,
                ColorStateList.valueOf(MaterialColors.getColor(view, tintAttr)),
            )

            SettingsCards.apply(
                view,
                first = items.getOrNull(position - 1) !is Item.Option,
                last = items.getOrNull(position + 1) !is Item.Option,
            )

            view.contentDescription = when (mark) {
                Mark.NONE -> entry.name
                Mark.INCLUDE -> ctx.getString(R.string.genre_tag_included, entry.name)
                Mark.EXCLUDE -> ctx.getString(R.string.genre_tag_excluded, entry.name)
            }
            view.setOnClickListener {
                onCycle(entry)
                notifyItemChanged(position)
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.CheckBox::class.java.name
                    info.isCheckable = true
                    info.isChecked = markOf(entry) != Mark.NONE
                }
            })
        }
    }
}
