package ani.dantotsu.media

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ItemGenreTagSectionBinding
import ani.dantotsu.databinding.SheetGenreTagBinding
import ani.dantotsu.media.GenreTagGroups.Entry
import ani.dantotsu.media.GenreTagGroups.Group
import ani.dantotsu.media.GenreTagGroups.Kind
import ani.dantotsu.media.GenreTagGroups.Mark
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors

/**
 * Unified genres & tags bottom sheet: search field on top, pinned row of selected chips,
 * Genres as wrapped M3 filter chips, and Tags grouped by collapsible categories (closed by default)
 * as wrapped M3 filter chips. Each chip cycles unselected -> ✓ include -> ✕ exclude -> unselected.
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
        val tagsLabel = context.getString(R.string.tags)
        val allGroups = GenreTagGroups.groups(
            genres = genres,
            tags = tags,
            categories = categories,
            genresTitle = context.getString(R.string.genres),
            otherTitle = tagsLabel,
        )
        if (allGroups.isEmpty()) return null
        val fullGroupsByTitle = allGroups.associateBy { it.title }

        val incGenres = (filters.genres ?: mutableListOf()).toMutableList()
        val excGenres = (filters.excludedGenres ?: mutableListOf()).toMutableList()
        val incTags = (filters.tags ?: mutableListOf()).toMutableList()
        val excTags = (filters.excludedTags ?: mutableListOf()).toMutableList()
        val expandedCategories = HashSet<String>()
        var currentQuery = ""

        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val b = SheetGenreTagBinding.inflate(inflater)
        if (rescue) {
            val genresWord = context.getString(R.string.genres)
            b.genreTagTitle.text = genresWord
            b.genreTagSearchText.hint = context.getString(R.string.search_hint, genresWord.lowercase())
        }

        fun selectedTotal() = incGenres.size + excGenres.size + incTags.size + excTags.size
        fun hasAny() = selectedTotal() > 0

        fun markOf(entry: Entry): Mark = when (entry.kind) {
            Kind.GENRE -> GenreTagGroups.markOf(entry.name, incGenres, excGenres)
            Kind.TAG -> GenreTagGroups.markOf(entry.name, incTags, excTags)
        }

        fun setMark(entry: Entry, mark: Mark) {
            when (entry.kind) {
                Kind.GENRE -> GenreTagGroups.set(entry.name, mark, incGenres, excGenres)
                Kind.TAG -> GenreTagGroups.set(entry.name, mark, incTags, excTags)
            }
        }

        lateinit var adapter: SectionAdapter
        lateinit var syncToFilters: (Boolean) -> Unit

        fun renderSelectedRow() {
            val selected = GenreTagGroups.selected(incGenres, excGenres, incTags, excTags)
            val hasSelected = selected.isNotEmpty()
            b.genreTagSelectedScroll.isVisible = hasSelected
            b.genreTagSelectedDivider.isVisible = hasSelected
            b.genreTagSelectedRow.removeAllViews()
            for (item in selected) {
                val chip = inflater.inflate(
                    R.layout.item_search_input_chip,
                    b.genreTagSelectedRow,
                    false,
                ) as Chip
                val isExclude = item.mark == Mark.EXCLUDE
                chip.text = if (isExclude) "\u2212${item.entry.name}" else item.entry.name
                if (isExclude) {
                    val bg = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorErrorContainer)
                    val fg = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnErrorContainer)
                    chip.chipBackgroundColor = ColorStateList.valueOf(bg)
                    chip.setTextColor(fg)
                    chip.closeIconTint = ColorStateList.valueOf(fg)
                }
                chip.contentDescription = context.getString(
                    if (isExclude) R.string.genre_tag_excluded else R.string.genre_tag_included,
                    item.entry.name,
                )
                chip.closeIconContentDescription = context.getString(R.string.remove_filter, item.entry.name)
                chip.setOnClickListener {
                    setMark(item.entry, markOf(item.entry).next())
                    syncToFilters(true)
                }
                chip.setOnCloseIconClickListener {
                    setMark(item.entry, Mark.NONE)
                    syncToFilters(true)
                }
                b.genreTagSelectedRow.addView(chip)
            }
        }

        fun renderHeaderCount() {
            val total = selectedTotal()
            b.genreTagReset.isVisible = total > 0
            b.genreTagCount.isVisible = total > 0
            if (total > 0) {
                b.genreTagCount.text = total.toString()
                b.genreTagCount.contentDescription = context.resources.getQuantityString(
                    R.plurals.search_filters_active_count_cd,
                    total,
                    total,
                )
            }
        }

        syncToFilters = { refreshList ->
            filters.genres = incGenres.toMutableList()
            filters.excludedGenres = excGenres.toMutableList()
            filters.tags = incTags.toMutableList()
            filters.excludedTags = excTags.toMutableList()
            renderHeaderCount()
            renderSelectedRow()
            if (refreshList) adapter.notifyDataSetChanged()
            onChanged()
        }

        fun cycleFromGroup(entry: Entry): Mark {
            val next = markOf(entry).next()
            setMark(entry, next)
            syncToFilters(false)
            return next
        }

        adapter = SectionAdapter(
            groups = allGroups,
            tagsLabel = tagsLabel,
            expandedCategories = expandedCategories,
            queryProvider = { currentQuery },
            markOf = ::markOf,
            selectedCountOf = { group ->
                val fullGroup = fullGroupsByTitle[group.title] ?: group
                GenreTagGroups.selectedCountInGroup(fullGroup, incGenres, excGenres, incTags, excTags)
            },
            onCycle = ::cycleFromGroup,
        )
        b.genreTagList.layoutManager = LinearLayoutManager(context)
        b.genreTagList.adapter = adapter
        b.genreTagList.itemAnimator = null

        renderHeaderCount()
        renderSelectedRow()
        b.genreTagReset.setOnClickListener {
            incGenres.clear()
            excGenres.clear()
            incTags.clear()
            excTags.clear()
            syncToFilters(true)
        }

        b.genreTagSearchText.doAfterTextChanged { text ->
            currentQuery = text?.toString().orEmpty()
            b.genreTagSearchClear.isVisible = currentQuery.isNotEmpty()
            val filtered = GenreTagGroups.search(allGroups, currentQuery)
            b.genreTagEmpty.isVisible = filtered.isEmpty()
            b.genreTagList.isVisible = filtered.isNotEmpty()
            adapter.update(filtered)
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
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface))
        ViewCompat.requestApplyInsets(b.root)
        return dialog
    }

    private fun applyFilterChip(chip: Chip, entry: Entry, mark: Mark) {
        val ctx = chip.context
        val strokePx = 1f * ctx.resources.displayMetrics.density
        chip.text = entry.name
        when (mark) {
            Mark.NONE -> {
                chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                chip.chipStrokeWidth = strokePx
                chip.chipStrokeColor = ColorStateList.valueOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOutline),
                )
                chip.setTextColor(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                chip.chipIcon = null
                chip.isChipIconVisible = false
                chip.contentDescription = entry.name
            }
            Mark.INCLUDE -> {
                val fg = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSecondaryContainer)
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorSecondaryContainer),
                )
                chip.chipStrokeWidth = 0f
                chip.setTextColor(fg)
                chip.chipIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_chip_check)?.mutate()
                chip.isChipIconVisible = true
                chip.chipIconTint = ColorStateList.valueOf(fg)
                chip.contentDescription = ctx.getString(R.string.genre_tag_included, entry.name)
            }
            Mark.EXCLUDE -> {
                val fg = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnErrorContainer)
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    MaterialColors.getColor(chip, com.google.android.material.R.attr.colorErrorContainer),
                )
                chip.chipStrokeWidth = 0f
                chip.setTextColor(fg)
                chip.chipIcon = AppCompatResources.getDrawable(ctx, R.drawable.ic_round_close_24)?.mutate()
                chip.isChipIconVisible = true
                chip.chipIconTint = ColorStateList.valueOf(fg)
                chip.contentDescription = ctx.getString(R.string.genre_tag_excluded, entry.name)
            }
        }
        ViewCompat.setAccessibilityDelegate(chip, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = mark != Mark.NONE
            }
        })
    }

    private class SectionHolder(val binding: ItemGenreTagSectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class SectionAdapter(
        private var groups: List<Group>,
        private val tagsLabel: String,
        private val expandedCategories: MutableSet<String>,
        private val queryProvider: () -> String,
        private val markOf: (Entry) -> Mark,
        private val selectedCountOf: (Group) -> Int,
        private val onCycle: (Entry) -> Mark,
    ) : RecyclerView.Adapter<SectionHolder>() {

        fun update(next: List<Group>) {
            groups = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder =
            SectionHolder(
                ItemGenreTagSectionBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )

        override fun onBindViewHolder(holder: SectionHolder, position: Int) {
            val group = groups[position]
            val b = holder.binding
            val ctx = b.root.context
            val query = queryProvider()
            val collapsible = group.isCollapsible
            val firstTagSection = collapsible && groups.getOrNull(position - 1)?.isCollapsible != true
            val expanded = GenreTagGroups.isGroupExpanded(group, expandedCategories, query)
            val canToggle = collapsible && query.isBlank()

            // Show a "Tags" section overline before the first categorized tag group (not when the
            // single fallback group is already titled "Tags").
            b.genreTagTagsSubheader.isVisible = firstTagSection && group.title != tagsLabel
            b.genreTagSectionDivider.isVisible = collapsible && !firstTagSection

            val density = ctx.resources.displayMetrics.density
            if (canToggle) {
                val outValue = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                b.genreTagSectionHeader.setBackgroundResource(outValue.resourceId)
                b.genreTagSectionHeader.isClickable = true
                b.genreTagSectionHeader.isFocusable = true
                ViewCompat.setStateDescription(
                    b.genreTagSectionHeader,
                    ctx.getString(if (expanded) R.string.genre_tag_expanded else R.string.genre_tag_collapsed),
                )
                b.genreTagSectionHeader.setOnClickListener {
                    if (!expandedCategories.add(group.title)) expandedCategories.remove(group.title)
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
            } else {
                b.genreTagSectionHeader.background = null
                b.genreTagSectionHeader.isClickable = false
                b.genreTagSectionHeader.isFocusable = false
                b.genreTagSectionHeader.setOnClickListener(null)
                ViewCompat.setStateDescription(b.genreTagSectionHeader, null)
            }
            b.genreTagSectionHeader.minimumHeight = if (collapsible) (48 * density).toInt() else 0
            b.genreTagSectionHeader.updatePaddingRelative(
                start = (16 * density).toInt(),
                top = if (collapsible) 0 else (12 * density).toInt(),
                end = (12 * density).toInt(),
                bottom = if (collapsible) 0 else (4 * density).toInt(),
            )

            b.genreTagSectionTitle.text = group.title
            b.genreTagSectionTitle.setTextColor(
                MaterialColors.getColor(
                    b.genreTagSectionTitle,
                    if (collapsible) {
                        com.google.android.material.R.attr.colorOnSurface
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                ),
            )

            fun updateCount() {
                val count = if (collapsible) selectedCountOf(group) else 0
                b.genreTagSectionCount.isVisible = count > 0
                if (count > 0) b.genreTagSectionCount.text = count.toString()
                b.genreTagSectionHeader.contentDescription = if (collapsible && count > 0) {
                    ctx.getString(R.string.genre_tag_category_selected, group.title, count)
                } else {
                    group.title
                }
            }
            updateCount()

            b.genreTagSectionChevron.isVisible = canToggle
            b.genreTagSectionChevron.rotation = if (expanded) 180f else 0f

            b.genreTagChipGroup.isVisible = expanded
            b.genreTagChipGroup.removeAllViews()
            if (expanded) {
                val inflater = LayoutInflater.from(ctx)
                for (entry in group.entries) {
                    val chip = inflater.inflate(
                        R.layout.item_genre_tag_chip,
                        b.genreTagChipGroup,
                        false,
                    ) as Chip
                    applyFilterChip(chip, entry, markOf(entry))
                    chip.setOnClickListener {
                        val next = onCycle(entry)
                        applyFilterChip(chip, entry, next)
                        updateCount()
                        chip.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    }
                    b.genreTagChipGroup.addView(chip)
                }
            }
        }
    }
}
