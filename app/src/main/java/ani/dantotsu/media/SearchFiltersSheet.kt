package ani.dantotsu.media

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.SheetSearchFiltersBinding
import ani.dantotsu.others.setDropdownText
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.util.Calendar

/**
 * Unified Search Filters bottom sheet, all chips: Sort and Genres & tags as dropdown chips that open
 * their own sheet, Adult as an on/off chip, then the short-choice filters (format, status, season,
 * year, source, country, list) as single-select chip sections. Changes update the live results at once.
 */
object SearchFiltersSheet {

    const val DEFAULT_SORT = "POPULARITY_DESC"

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

        val dialog = BottomSheetDialog(context)
        val b = SheetSearchFiltersBinding.inflate(LayoutInflater.from(context))

        fun sync() {
            b.searchFiltersReset.isVisible = SearchStatePolicy.hasActiveFilters(filters)
            val mutated = {
                sync()
                onChanged()
            }
            buildTop(context, b.searchFiltersTop, filters, rescue, mutated)
            buildSections(context, b.searchFiltersSections, anime, filters, rescue, mutated)
        }

        b.searchFiltersReset.setOnClickListener {
            SearchStatePolicy.clearFilters(filters)
            sync()
            onChanged()
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

    /** A chip from the shared filter-chip layout; [dropdown] chips draw ▾ after the label and open a sheet. */
    private fun filterChip(
        inflater: LayoutInflater,
        group: ChipGroup,
        label: CharSequence,
        checked: Boolean,
        dropdown: Boolean = false,
        onClick: () -> Unit,
    ): Chip {
        val chip = inflater.inflate(R.layout.item_picker_filter_chip, group, false) as Chip
        chip.id = View.generateViewId()
        if (dropdown) chip.setDropdownText(label) else chip.text = label
        chip.isChecked = checked
        chip.setOnClickListener {
            // the state comes from the filters, not from the tap: a cancelled sheet leaves it as it was
            chip.isChecked = checked
            onClick()
        }
        group.addView(chip)
        return chip
    }

    /**
     * The top row: Sort and Genres & tags as dropdown chips (they open their own sheet), Adult as an
     * on/off chip. Same chip family as the sections below, so the sheet reads as one set of controls.
     */
    private fun buildTop(
        context: Context,
        group: ChipGroup,
        f: AniMangaSearchResults,
        rescue: Boolean,
        onMutated: () -> Unit,
    ) {
        group.removeAllViews()
        val inflater = LayoutInflater.from(context)

        // Sort: when unset (null), AniList defaults to POPULARITY_DESC (and Rescue mode to members desc).
        // It always has a value, so the sort icon names the chip while it shows the default.
        val sortActive = f.sort != null && f.sort != DEFAULT_SORT
        filterChip(inflater, group, sortLabel(context, f.sort ?: DEFAULT_SORT), sortActive, dropdown = true) {
            openSort(context, f, onMutated)
        }.apply {
            if (!sortActive) {
                setChipIconResource(R.drawable.ic_round_sort_24)
                isChipIconVisible = true
                chipIconTint = textColors
            }
        }

        val hasGenreData = !Anilist.genres.isNullOrEmpty() ||
            (!rescue && !Anilist.tags?.get(f.isAdult).isNullOrEmpty()) ||
            !f.genres.isNullOrEmpty() || !f.tags.isNullOrEmpty() ||
            !f.excludedGenres.isNullOrEmpty() || !f.excludedTags.isNullOrEmpty()
        if (hasGenreData) {
            val inc = f.genres.orEmpty() + f.tags.orEmpty()
            val exc = f.excludedGenres.orEmpty() + f.excludedTags.orEmpty()
            val summary = GenreTagGroups.summary(inc, exc)
            filterChip(inflater, group, summary ?: context.getString(R.string.genres_and_tags), summary != null, dropdown = true) {
                GenreTagSheet.show(context, f, rescue, onMutated)
            }.apply {
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * context.resources.displayMetrics.density).toInt()
            }
        }

        if (Anilist.adult) {
            filterChip(inflater, group, context.getString(R.string.adult), f.isAdult) {
                f.isAdult = !f.isAdult
                onMutated()
            }
        }
    }

    /** Recent years shown as chips; with the "Older" chip they make two rows of four on a phone. */
    private const val YEARS_FIRST = 7

    /**
     * The short-choice filters as chip sections inside the sheet, instead of a sheet per filter
     * with one long list. One chip per option; tapping the chosen chip again goes back to Any.
     */
    private fun buildSections(
        context: Context,
        container: LinearLayout,
        anime: Boolean,
        f: AniMangaSearchResults,
        rescue: Boolean,
        onMutated: () -> Unit,
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun section(titleRes: Int, labels: List<String>, selected: Int, pick: (Int?) -> Unit): ChipGroup {
            container.addView(TextView(context).apply {
                text = context.getString(titleRes)
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
                setTextColor(MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
                // same 16dp keyline as the title and the chips
                setPaddingRelative(px(16), px(if (container.childCount == 0) 8 else 12), px(16), px(2))
            })
            val group = ChipGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = false
                chipSpacingHorizontal = px(8)
                // the chips keep a 48dp touch target, which already leaves 16dp between rows
                chipSpacingVertical = 0
                setPaddingRelative(px(16), 0, px(16), 0)
            }
            labels.forEachIndexed { i, label ->
                filterChip(inflater, group, label, i == selected) {
                    pick(if (i == selected) null else i)
                    onMutated()
                }
            }
            container.addView(group)
            return group
        }

        val formats = if (anime) Anilist.animeFormats else Anilist.mangaFormats
        section(R.string.format, formats.map(::pretty), formats.indexOf(f.format)) {
            f.format = it?.let(formats::getOrNull)
        }

        val statuses = if (anime) Anilist.animeStatus else Anilist.mangaStatus
        section(R.string.chip_status, statuses.map(::pretty), statuses.indexOf(f.status?.replace("_", " "))) {
            f.status = it?.let(statuses::getOrNull)?.replace(" ", "_")
        }

        if (anime && !rescue) {
            section(R.string.season, Anilist.seasons.map(::pretty), Anilist.seasons.indexOf(f.season)) {
                f.season = it?.let(Anilist.seasons::getOrNull)
            }
        }

        val years = (1970..Calendar.getInstance().get(Calendar.YEAR) + 1).reversed().toList()
        val year = if (anime) f.seasonYear else f.startYear
        val recent = years.take(YEARS_FIRST)
        val group = section(R.string.year, recent.map(Int::toString), recent.indexOf(year)) {
            val y = it?.let(recent::getOrNull)
            if (anime) f.seasonYear = y else f.startYear = y
        }
        // Older years: one dropdown chip opening the full list; shows the year once one is picked from it.
        val older = year != null && year !in recent
        filterChip(inflater, group, if (older) year.toString() else context.getString(R.string.filter_older_years), older, dropdown = true) {
            openYear(context, anime, f, onMutated)
        }

        if (!rescue) {
            section(R.string.source, Anilist.source.map(::pretty), Anilist.source.indexOf(f.source?.replace("_", " "))) {
                f.source = it?.let(Anilist.source::getOrNull)?.replace(" ", "_")
            }
            section(R.string.chip_country, COUNTRY_LABEL_RES.map(context::getString), COUNTRY_CODES.indexOf(f.countryOfOrigin)) {
                f.countryOfOrigin = it?.let(COUNTRY_CODES::getOrNull)
            }
        }

        if (Anilist.userid != null && !rescue) {
            val lists = listOf(R.string.filter_on_list_in, R.string.filter_on_list_not_in).map(context::getString)
            val sel = when (f.onList) {
                true -> 0
                false -> 1
                null -> -1
            }
            section(R.string.list, lists, sel) {
                f.onList = when (it) {
                    0 -> true
                    1 -> false
                    else -> null
                }
            }
        }
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

    /** "NOT_YET_RELEASED" -> "Not yet released"; short codes (TV, OVA, ONA) stay as they are. */
    fun pretty(raw: String): String {
        val s = raw.replace('_', ' ')
        if (s.length <= 3) return s
        // "TV SHORT" reads "TV short", not "Tv short"
        return s.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("^Tv\\b"), "TV")
    }

    private fun openSingle(
        context: Context,
        titleRes: Int,
        options: List<String>,
        selected: Int,
        set: (Int) -> Unit,
        onChanged: () -> Unit,
    ) {
        val all = listOf(ChoiceOption(context.getString(R.string.chip_any))) + options.map { ChoiceOption(it) }
        ChoiceSheet.single(context, context.getString(titleRes), all, selected + 1) { picked ->
            set(picked - 1)
            onChanged()
        }
    }

    fun openSort(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val labels = context.resources.getStringArray(R.array.sort_by).toList()
        val options = Anilist.sortBy.indices.map { ChoiceOption(labels.getOrElse(it) { Anilist.sortBy[it] }) }
        val current = Anilist.sortBy.indexOf(f.sort ?: DEFAULT_SORT).coerceAtLeast(0)
        ChoiceSheet.single(context, context.getString(R.string.chip_sort), options, current) { picked ->
            val key = Anilist.sortBy.getOrNull(picked)
            f.sort = key?.takeUnless { it == DEFAULT_SORT }
            onChanged()
        }
    }

    fun openFormat(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val formats = if (anime) Anilist.animeFormats else Anilist.mangaFormats
        openSingle(context, R.string.format, formats.map(::pretty), formats.indexOf(f.format),
            { f.format = formats.getOrNull(it) }, onChanged)
    }

    fun openStatus(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val statuses = if (anime) Anilist.animeStatus else Anilist.mangaStatus
        openSingle(context, R.string.chip_status, statuses.map(::pretty), statuses.indexOf(f.status?.replace("_", " ")),
            { f.status = statuses.getOrNull(it)?.replace(" ", "_") }, onChanged)
    }

    fun openYear(context: Context, anime: Boolean, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val years = (1970..Calendar.getInstance().get(Calendar.YEAR) + 1).reversed().toList()
        val year = if (anime) f.seasonYear else f.startYear
        openSingle(context, R.string.year, years.map { it.toString() }, years.indexOf(year), {
            val y = years.getOrNull(it)
            if (anime) f.seasonYear = y else f.startYear = y
        }, onChanged)
    }

    fun openSeason(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit) {
        openSingle(context, R.string.season, Anilist.seasons.map(::pretty), Anilist.seasons.indexOf(f.season),
            { f.season = Anilist.seasons.getOrNull(it) }, onChanged)
    }

    fun openList(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val lists = listOf(R.string.filter_on_list_in, R.string.filter_on_list_not_in).map(context::getString)
        val sel = when (f.onList) {
            true -> 0
            false -> 1
            null -> -1
        }
        openSingle(context, R.string.list, lists, sel, {
            f.onList = when (it) {
                0 -> true
                1 -> false
                else -> null
            }
        }, onChanged)
    }

    fun openSource(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit) {
        openSingle(context, R.string.source, Anilist.source.map(::pretty),
            Anilist.source.indexOf(f.source?.replace("_", " ")),
            { f.source = Anilist.source.getOrNull(it)?.replace(" ", "_") }, onChanged)
    }

    fun openCountry(context: Context, f: AniMangaSearchResults, onChanged: () -> Unit) {
        val names = COUNTRY_LABEL_RES.map(context::getString)
        openSingle(context, R.string.chip_country, names, COUNTRY_CODES.indexOf(f.countryOfOrigin),
            { f.countryOfOrigin = COUNTRY_CODES.getOrNull(it) }, onChanged)
    }
}
