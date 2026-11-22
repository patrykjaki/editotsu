package ani.dantotsu.media

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.SheetSearchFiltersBinding
import ani.dantotsu.media.SearchFilterPolicy.Facet
import ani.dantotsu.others.setDropdownText
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors

/**
 * Compact single-screen Search Filters bottom sheet:
 * - Primary row: Sort, Genres & tags (dropdown chips) and Adult (binary filter chip, when enabled).
 * - Common facets: Format, Status, Year, Season (dropdown chips, hidden when not applicable to type/mode).
 * - Collapsible "More": Source, Country, List (hidden in Rescue mode; auto-expanded if any is set).
 * - Single primary "Show results" CTA and conditional header Reset icon button.
 *
 * Choices stay live on [filters] as you pick facets and commit once when the sheet closes (via
 * "Show results", swipe-down, scrim tap, or Back), so swiping down never loses choices and picking
 * multiple facets fires at most one search. Back from any facet picker returns to this sheet.
 */
object SearchFiltersSheet {

    const val DEFAULT_SORT = SearchFilterPolicy.DEFAULT_SORT

    private val COUNTRY_CODES = listOf("JP", "KR", "CN", "TW")
    private val COUNTRY_LABEL_RES = listOf(
        R.string.country_japan,
        R.string.country_korea,
        R.string.country_china,
        R.string.country_taiwan,
    )

    fun show(
        context: Context,
        type: SearchType,
        filters: AniMangaSearchResults,
        rescue: Boolean = PrefManager.getVal(PrefName.RescueMode),
        onChanged: () -> Unit,
    ): BottomSheetDialog? {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return null
        val anime = type == SearchType.ANIME
        val signedIn = Anilist.userid != null
        val adultEnabled = Anilist.adult
        val initialSnapshot = SearchFilterPolicy.FilterSnapshot.from(filters)
        var moreExpanded = SearchFilterPolicy.shouldExpandMore(
            filters = filters,
            userExpanded = SearchFilterPolicy.moreExpanded,
            rescue = rescue,
            signedIn = signedIn,
        )
        var committed = false

        val dialog = BottomSheetDialog(context)
        val b = SheetSearchFiltersBinding.inflate(LayoutInflater.from(context))

        fun hasGenreData(): Boolean = !Anilist.genres.isNullOrEmpty() ||
            (!rescue && !Anilist.tags?.get(filters.isAdult).isNullOrEmpty()) ||
            !filters.genres.isNullOrEmpty() || !filters.tags.isNullOrEmpty() ||
            !filters.excludedGenres.isNullOrEmpty() || !filters.excludedTags.isNullOrEmpty()

        lateinit var sync: () -> Unit

        fun openFacet(facet: Facet) {
            when (facet) {
                Facet.SORT -> openSort(context, filters, sync)
                Facet.GENRES_TAGS -> GenreTagSheet.show(context, filters, rescue, sync)
                Facet.ADULT -> {
                    filters.isAdult = !filters.isAdult
                    sync()
                }
                Facet.FORMAT -> openFormat(context, anime, filters, sync)
                Facet.STATUS -> openStatus(context, anime, filters, sync)
                Facet.YEAR -> openYear(context, anime, filters, sync)
                Facet.SEASON -> openSeason(context, filters, sync)
                Facet.SOURCE -> openSource(context, filters, sync, anime)
                Facet.COUNTRY -> openCountry(context, filters, sync)
                Facet.LIST -> openList(context, filters, sync)
            }
        }

        fun clearFacet(facet: Facet) {
            when (facet) {
                Facet.SORT -> filters.sort = null
                Facet.GENRES_TAGS -> {
                    filters.genres = null
                    filters.excludedGenres = null
                    filters.tags = null
                    filters.excludedTags = null
                }
                Facet.ADULT -> filters.isAdult = false
                Facet.FORMAT -> filters.format = null
                Facet.STATUS -> filters.status = null
                Facet.YEAR -> if (anime) filters.seasonYear = null else filters.startYear = null
                Facet.SEASON -> filters.season = null
                Facet.SOURCE -> filters.source = null
                Facet.COUNTRY -> filters.countryOfOrigin = null
                Facet.LIST -> filters.onList = null
            }
            sync()
        }

        fun addFacetChip(inflater: LayoutInflater, group: ChipGroup, facet: Facet) {
            when (facet) {
                Facet.ADULT -> {
                    val label = context.getString(R.string.adult)
                    binaryChip(inflater, group, label, filters.isAdult) {
                        openFacet(Facet.ADULT)
                    }
                }
                Facet.SORT -> {
                    val active = SearchFilterPolicy.normalizeSort(filters.sort) != null
                    val label = sortLabel(context, filters.sort ?: DEFAULT_SORT)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = context.getString(R.string.chip_sort),
                        label = label,
                        selected = active,
                        unselectedIcon = R.drawable.ic_round_sort_24,
                        onTap = { openFacet(Facet.SORT) },
                        onClear = if (active) ({ clearFacet(Facet.SORT) }) else null,
                    )
                }
                Facet.GENRES_TAGS -> {
                    val inc = filters.genres.orEmpty() + if (rescue) emptyList() else filters.tags.orEmpty()
                    val exc = filters.excludedGenres.orEmpty() + if (rescue) emptyList() else filters.excludedTags.orEmpty()
                    val summary = GenreTagGroups.summary(inc, exc)
                    val facetName = context.getString(if (rescue) R.string.genres else R.string.genres_and_tags)
                    val selected = summary != null
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = summary ?: facetName,
                        selected = selected,
                        unselectedIcon = R.drawable.ic_label_24,
                        onTap = { openFacet(Facet.GENRES_TAGS) },
                        onClear = if (selected) ({ clearFacet(Facet.GENRES_TAGS) }) else null,
                    ).apply {
                        ellipsize = TextUtils.TruncateAt.END
                        maxWidth = (260 * context.resources.displayMetrics.density).toInt()
                    }
                }
                Facet.FORMAT -> {
                    val facetName = context.getString(R.string.format)
                    val value = filters.format?.let(::pretty)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.FORMAT) },
                        onClear = if (value != null) ({ clearFacet(Facet.FORMAT) }) else null,
                    )
                }
                Facet.STATUS -> {
                    val facetName = context.getString(R.string.chip_status)
                    val value = filters.status?.let(::pretty)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.STATUS) },
                        onClear = if (value != null) ({ clearFacet(Facet.STATUS) }) else null,
                    )
                }
                Facet.YEAR -> {
                    val facetName = context.getString(R.string.year)
                    val year = if (anime) filters.seasonYear else filters.startYear
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = year?.toString() ?: facetName,
                        selected = year != null,
                        onTap = { openFacet(Facet.YEAR) },
                        onClear = if (year != null) ({ clearFacet(Facet.YEAR) }) else null,
                    )
                }
                Facet.SEASON -> {
                    val facetName = context.getString(R.string.season)
                    val value = filters.season?.let(::pretty)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.SEASON) },
                        onClear = if (value != null) ({ clearFacet(Facet.SEASON) }) else null,
                    )
                }
                Facet.SOURCE -> {
                    val facetName = context.getString(R.string.source)
                    val value = filters.source?.let(::pretty)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.SOURCE) },
                        onClear = if (value != null) ({ clearFacet(Facet.SOURCE) }) else null,
                    )
                }
                Facet.COUNTRY -> {
                    val facetName = context.getString(R.string.chip_country)
                    val value = countryLabel(context, filters.countryOfOrigin)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.COUNTRY) },
                        onClear = if (value != null) ({ clearFacet(Facet.COUNTRY) }) else null,
                    )
                }
                Facet.LIST -> {
                    val facetName = context.getString(R.string.list)
                    val value = listLabel(context, filters.onList)
                    dropdownChip(
                        inflater = inflater,
                        group = group,
                        facetName = facetName,
                        label = value ?: facetName,
                        selected = value != null,
                        onTap = { openFacet(Facet.LIST) },
                        onClear = if (value != null) ({ clearFacet(Facet.LIST) }) else null,
                    )
                }
            }
        }

        sync = sync@{
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return@sync
            val inflater = LayoutInflater.from(context)
            val activeCount = SearchFilterPolicy.applicableActiveCount(
                filters = filters,
                anime = anime,
                rescue = rescue,
                signedIn = signedIn,
                adultEnabled = adultEnabled,
            )
            b.searchFiltersCount.isVisible = activeCount > 0
            if (activeCount > 0) {
                b.searchFiltersCount.text = activeCount.toString()
                b.searchFiltersCount.contentDescription = context.resources.getQuantityString(
                    R.plurals.search_filters_active_count_cd,
                    activeCount,
                    activeCount,
                )
            }
            b.searchFiltersReset.isVisible = activeCount > 0 || SearchStatePolicy.hasActiveFilters(filters)

            b.searchFiltersPrimary.removeAllViews()
            SearchFilterPolicy.visiblePrimaryFacets(hasGenreData(), adultEnabled).forEach { facet ->
                addFacetChip(inflater, b.searchFiltersPrimary, facet)
            }

            b.searchFiltersCommon.removeAllViews()
            SearchFilterPolicy.visibleCommonFacets(anime, rescue).forEach { facet ->
                addFacetChip(inflater, b.searchFiltersCommon, facet)
            }

            val moreFacets = SearchFilterPolicy.visibleMoreFacets(rescue, signedIn)
            b.searchFiltersMoreContainer.isVisible = moreFacets.isNotEmpty()
            if (moreFacets.isNotEmpty()) {
                val moreActive = SearchFilterPolicy.activeMoreCount(filters, rescue, signedIn)
                b.searchFiltersMoreCount.isVisible = !moreExpanded && moreActive > 0
                if (moreActive > 0) b.searchFiltersMoreCount.text = moreActive.toString()
                b.searchFiltersMoreChevron.rotation = if (moreExpanded) 180f else 0f
                ViewCompat.setStateDescription(
                    b.searchFiltersMoreHeader,
                    context.getString(if (moreExpanded) R.string.genre_tag_expanded else R.string.genre_tag_collapsed),
                )
                b.searchFiltersMoreGroup.isVisible = moreExpanded
                b.searchFiltersMoreGroup.removeAllViews()
                if (moreExpanded) {
                    moreFacets.forEach { facet ->
                        addFacetChip(inflater, b.searchFiltersMoreGroup, facet)
                    }
                }
            }
        }

        b.searchFiltersMoreHeader.setOnClickListener {
            moreExpanded = !moreExpanded
            SearchFilterPolicy.moreExpanded = moreExpanded
            sync()
        }

        b.searchFiltersReset.setOnClickListener {
            SearchStatePolicy.clearFilters(filters)
            sync()
        }

        b.searchFiltersApply.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!committed) {
                committed = true
                if (initialSnapshot.differsFrom(filters)) {
                    onChanged()
                }
            }
        }

        sync()

        dialog.setContentView(b.root)
        val basePadding = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = basePadding + nav)
            insets
        }
        dialog.setTitle(b.searchFiltersTitle.text)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorSurface))
        ViewCompat.requestApplyInsets(b.root)
        return dialog
    }

    /** Binary on/off M3 filter chip (e.g. Adult). */
    private fun binaryChip(
        inflater: LayoutInflater,
        group: ChipGroup,
        label: CharSequence,
        checked: Boolean,
        onClick: () -> Unit,
    ): Chip {
        val chip = inflater.inflate(R.layout.item_picker_filter_chip, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.contentDescription = label
        chip.isChecked = checked
        chip.setOnClickListener {
            chip.isChecked = checked
            onClick()
        }
        group.addView(chip)
        return chip
    }

    /** M3 dropdown chip with one touch target: unselected = [unselectedIcon] + label + ▾; selected = ✓ + value + ▾. */
    private fun dropdownChip(
        inflater: LayoutInflater,
        group: ChipGroup,
        facetName: String,
        label: CharSequence,
        selected: Boolean,
        @DrawableRes unselectedIcon: Int? = null,
        onTap: () -> Unit,
        onClear: (() -> Unit)? = null,
    ): Chip {
        val chip = inflater.inflate(R.layout.item_filter_dropdown_chip, group, false) as Chip
        chip.id = View.generateViewId()
        chip.setDropdownText(label)
        chip.isChecked = selected
        chip.isCheckedIconVisible = selected
        if (!selected && unselectedIcon != null) {
            chip.setChipIconResource(unselectedIcon)
            chip.isChipIconVisible = true
            chip.chipIconTint = ColorStateList.valueOf(
                MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
        } else {
            chip.isChipIconVisible = false
        }
        chip.contentDescription = if (selected) {
            chip.context.getString(R.string.search_filter_selected_cd, facetName, label)
        } else {
            facetName
        }
        chip.setOnClickListener {
            // Selection comes from filters, not from the tap: cancelling a picker keeps current state.
            chip.isChecked = selected
            onTap()
        }
        if (onClear != null) {
            chip.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onClear()
                true
            }
        } else {
            chip.isLongClickable = false
        }
        group.addView(chip)
        return chip
    }

    // ---- Shared pickers & formatters (used by both SearchFiltersSheet and SearchFilterChips) ----

    fun sortLabel(context: Context, key: String): String {
        val labels = context.resources.getStringArray(R.array.sort_by).toList()
        val idx = Anilist.sortBy.indexOf(key)
        return if (idx >= 0) labels.getOrElse(idx) { key } else key
    }

    fun listLabel(context: Context, onList: Boolean?): String? = when (onList) {
        true -> context.getString(R.string.filter_on_list_in)
        false -> context.getString(R.string.filter_on_list_not_in)
        null -> null
    }

    fun countryLabel(context: Context, code: String?): String? {
        if (code == null) return null
        val idx = COUNTRY_CODES.indexOf(code)
        return if (idx >= 0) context.getString(COUNTRY_LABEL_RES[idx]) else code
    }

    fun pretty(raw: String): String = SearchFilterPolicy.pretty(raw)

    fun openSort(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val labels = context.resources.getStringArray(R.array.sort_by).toList()
        val options = Anilist.sortBy.indices.map { i ->
            SearchFilterPickers.Option(
                key = Anilist.sortBy[i],
                label = labels.getOrElse(i) { Anilist.sortBy[i] },
            )
        }
        val current = f.sort ?: DEFAULT_SORT
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.chip_sort),
            primaryOptions = options,
            selectedKey = current,
            allowClear = false,
        ) { picked ->
            f.sort = SearchFilterPolicy.normalizeSort(picked)
            onChanged()
        }
    }

    fun openFormat(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val formats = if (anime) Anilist.animeFormats else Anilist.mangaFormats
        val options = formats.map { SearchFilterPickers.Option(it, pretty(it)) }
        val selected = SearchFilterPolicy.findMatchingKey(formats, f.format)
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.format),
            primaryOptions = options,
            selectedKey = selected,
        ) { picked ->
            f.format = picked
            onChanged()
        }
    }

    fun openStatus(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val statuses = if (anime) Anilist.animeStatus else Anilist.mangaStatus
        val options = statuses.map { SearchFilterPickers.Option(it.replace(" ", "_"), pretty(it)) }
        val selected = f.status?.replace(" ", "_")
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.chip_status),
            primaryOptions = options,
            selectedKey = selected,
        ) { picked ->
            f.status = picked
            onChanged()
        }
    }

    fun openYear(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val current = if (anime) f.seasonYear else f.startYear
        return SearchFilterPickers.showYearGrid(
            context = context,
            selectedYear = current,
        ) { picked ->
            if (anime) f.seasonYear = picked else f.startYear = picked
            onChanged()
        }
    }

    fun openSeason(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val options = Anilist.seasons.map { SearchFilterPickers.Option(it, pretty(it)) }
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.season),
            primaryOptions = options,
            selectedKey = f.season,
        ) { picked ->
            f.season = picked
            onChanged()
        }
    }

    fun openList(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val options = listOf(
            SearchFilterPickers.Option("IN", context.getString(R.string.filter_on_list_in)),
            SearchFilterPickers.Option("NOT_IN", context.getString(R.string.filter_on_list_not_in)),
        )
        val selected = when (f.onList) {
            true -> "IN"
            false -> "NOT_IN"
            null -> null
        }
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.list),
            primaryOptions = options,
            selectedKey = selected,
        ) { picked ->
            f.onList = when (picked) {
                "IN" -> true
                "NOT_IN" -> false
                else -> null
            }
            onChanged()
        }
    }

    fun openSource(
        context: Context,
        f: AniMangaSearchResults,
        onChanged: () -> Unit,
        anime: Boolean = true,
    ): BottomSheetDialog? {
        val partition = SearchFilterPolicy.sourceGroups(Anilist.source, anime)
        val primary = partition.common.map { SearchFilterPickers.Option(it.replace(" ", "_"), pretty(it)) }
        val more = partition.more.map { SearchFilterPickers.Option(it.replace(" ", "_"), pretty(it)) }
        val selected = f.source?.replace(" ", "_")
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.source),
            primaryOptions = primary,
            selectedKey = selected,
            moreOptions = more,
            moreTitle = context.getString(R.string.search_filters_more_sources),
        ) { picked ->
            f.source = picked
            onChanged()
        }
    }

    fun openCountry(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit): BottomSheetDialog? {
        val options = COUNTRY_CODES.indices.map { i ->
            SearchFilterPickers.Option(COUNTRY_CODES[i], context.getString(COUNTRY_LABEL_RES[i]))
        }
        return SearchFilterPickers.showOptions(
            context = context,
            title = context.getString(R.string.chip_country),
            primaryOptions = options,
            selectedKey = f.countryOfOrigin,
        ) { picked ->
            f.countryOfOrigin = picked
            onChanged()
        }
    }
}
