package ani.dantotsu.media

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.others.setDropdownText
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * The row under the search bar:
 * 1. Type chip (`All ▾`, `Anime ▾`, `Manga ▾`, `Characters ▾`, `Staff ▾`, `Studios ▾`, `Users ▾`).
 * 2. For anime/manga only: a subtle divider, then ONE `"Filters"` button-chip (`"Filters · N"` when N set)
 *    that opens [SearchFiltersSheet].
 * 3. After `"Filters"`: ONLY the filters that are currently SET, as compact removable input chips
 *    (`"Action"`, `"TV"`, `"2024"`, `"−Isekai"`, `"Sort: Popularity"`). Tapping opens that filter's
 *    picker; tapping × clears just that filter. Nothing set = no extra chips.
 */
class SearchFilterChips(
    private val activity: SearchActivity,
    private val row: LinearLayout,
    private val type: SearchType,
    private val currentQuery: () -> String,
    /** Anime/manga filters; null for All, characters, staff, studios and users (type chip only). */
    private val filters: AniMangaSearchResults?,
    private val onChanged: () -> Unit,
) {
    private val anime = type == SearchType.ANIME
    private val rescue: Boolean = PrefManager.getVal(PrefName.RescueMode)

    fun build() {
        row.removeAllViews()
        typeChip()
        val f = filters ?: return
        addDivider()
        filtersButtonChip(f)
        addActiveFilterChips(f)
    }

    /** Subtle 1dp vertical rule between the search-type scope chip and the Filters control. */
    private fun addDivider() {
        val d = activity.resources.displayMetrics.density
        val divider = View(activity).apply {
            setBackgroundColor(
                MaterialColors.getColor(row, com.google.android.material.R.attr.colorOutlineVariant)
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = LinearLayout.LayoutParams(
            (1 * d).roundToInt().coerceAtLeast(1),
            (20 * d).roundToInt(),
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = (8 * d).roundToInt()
        }
        row.addView(divider, lp)
    }

    private fun typeChip() {
        val types = listOf(
            SearchType.ALL,
            SearchType.ANIME,
            SearchType.MANGA,
            SearchType.CHARACTER,
            SearchType.STAFF,
            SearchType.STUDIO,
            SearchType.USER,
        )
        val names = listOf(
            R.string.all,
            R.string.anime,
            R.string.manga,
            R.string.characters,
            R.string.staff,
            R.string.studios,
            R.string.users,
        ).map(activity::getString)
        val currentIdx = types.indexOf(type).coerceAtLeast(0)
        val chip = LayoutInflater.from(activity).inflate(R.layout.item_search_chip, row, false) as Chip
        chip.setDropdownText(names[currentIdx])
        chip.textEndPadding = 0f
        chip.chipEndPadding = 5 * activity.resources.displayMetrics.density
        chip.isCheckedIconVisible = true
        chip.isChecked = true
        chip.contentDescription = activity.getString(R.string.search_type_of, names[currentIdx])
        row.addView(chip)

        chip.setOnClickListener {
            chip.isChecked = true
            ChoiceSheet.single(
                activity,
                activity.getString(R.string.search_type),
                names.map { ChoiceOption(it) },
                currentIdx,
            ) { picked ->
                if (types[picked] == type) return@single
                val query = currentQuery()
                activity.switchToType(
                    nextType = types[picked],
                    query = query.takeIf { it.isNotBlank() },
                    autoSearch = query.isNotBlank(),
                )
            }
        }
    }

    /** Single entry point to the unified Filters sheet; displays a live count when any filter is set. */
    private fun filtersButtonChip(f: AniMangaSearchResults) {
        val count = SearchStatePolicy.activeFilterCount(f)
        val label = if (count > 0) {
            activity.getString(R.string.search_filters_count, count)
        } else {
            activity.getString(R.string.search_filters)
        }
        val chip = LayoutInflater.from(activity).inflate(R.layout.item_search_chip, row, false) as Chip
        chip.text = label
        chip.contentDescription = label
        chip.isCheckedIconVisible = false
        chip.chipIcon = AppCompatResources.getDrawable(activity, R.drawable.ic_round_tune_24)
        chip.isChipIconVisible = true
        chip.isChecked = count > 0
        val iconAttr = if (count > 0) {
            com.google.android.material.R.attr.colorOnSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        chip.chipIconTint = ColorStateList.valueOf(MaterialColors.getColor(chip, iconAttr))
        row.addView(chip)

        chip.setOnClickListener {
            chip.isChecked = SearchStatePolicy.activeFilterCount(f) > 0
            SearchFiltersSheet.show(activity, type, f, rescue, onChanged)
        }
    }

    /** Emits compact removable input chips ONLY for filters currently set on [f]. */
    private fun addActiveFilterChips(f: AniMangaSearchResults) {
        f.sort?.let { sortKey ->
            val label = activity.getString(
                R.string.search_filter_sort_chip,
                SearchFiltersSheet.sortLabel(activity, sortKey),
            )
            activeInputChip(
                text = label,
                onTap = { SearchFiltersSheet.openSort(activity, f, onChanged) },
                onClear = {
                    f.sort = null
                    onChanged()
                },
            )
        }

        f.genres?.toList()?.forEach { genre ->
            activeInputChip(
                text = genre,
                onTap = { GenreTagSheet.show(activity, f, rescue, onChanged) },
                onClear = {
                    f.genres?.remove(genre)
                    if (f.genres.isNullOrEmpty()) f.genres = null
                    onChanged()
                },
            )
        }

        f.tags?.toList()?.forEach { tag ->
            activeInputChip(
                text = tag,
                onTap = { GenreTagSheet.show(activity, f, rescue, onChanged) },
                onClear = {
                    f.tags?.remove(tag)
                    if (f.tags.isNullOrEmpty()) f.tags = null
                    onChanged()
                },
            )
        }

        f.excludedGenres?.toList()?.forEach { genre ->
            activeInputChip(
                text = "\u2212$genre",
                onTap = { GenreTagSheet.show(activity, f, rescue, onChanged) },
                onClear = {
                    f.excludedGenres?.remove(genre)
                    if (f.excludedGenres.isNullOrEmpty()) f.excludedGenres = null
                    onChanged()
                },
            )
        }

        f.excludedTags?.toList()?.forEach { tag ->
            activeInputChip(
                text = "\u2212$tag",
                onTap = { GenreTagSheet.show(activity, f, rescue, onChanged) },
                onClear = {
                    f.excludedTags?.remove(tag)
                    if (f.excludedTags.isNullOrEmpty()) f.excludedTags = null
                    onChanged()
                },
            )
        }

        f.format?.let { fmt ->
            activeInputChip(
                text = SearchFiltersSheet.pretty(fmt),
                onTap = { SearchFiltersSheet.openFormat(activity, anime, f, onChanged) },
                onClear = {
                    f.format = null
                    onChanged()
                },
            )
        }

        f.status?.let { st ->
            activeInputChip(
                text = SearchFiltersSheet.pretty(st),
                onTap = { SearchFiltersSheet.openStatus(activity, anime, f, onChanged) },
                onClear = {
                    f.status = null
                    onChanged()
                },
            )
        }

        val year = if (anime) f.seasonYear else f.startYear
        year?.let { y ->
            activeInputChip(
                text = y.toString(),
                onTap = { SearchFiltersSheet.openYear(activity, anime, f, onChanged) },
                onClear = {
                    if (anime) f.seasonYear = null else f.startYear = null
                    onChanged()
                },
            )
        }

        f.season?.let { season ->
            activeInputChip(
                text = SearchFiltersSheet.pretty(season),
                onTap = { SearchFiltersSheet.openSeason(activity, f, onChanged) },
                onClear = {
                    f.season = null
                    onChanged()
                },
            )
        }

        f.onList?.let { onList ->
            val label = SearchFiltersSheet.listLabel(activity, onList) ?: return@let
            activeInputChip(
                text = label,
                onTap = { SearchFiltersSheet.openList(activity, f, onChanged) },
                onClear = {
                    f.onList = null
                    onChanged()
                },
            )
        }

        f.source?.let { src ->
            activeInputChip(
                text = SearchFiltersSheet.pretty(src),
                onTap = { SearchFiltersSheet.openSource(activity, f, onChanged, anime) },
                onClear = {
                    f.source = null
                    onChanged()
                },
            )
        }

        f.countryOfOrigin?.let { code ->
            val label = SearchFiltersSheet.countryLabel(activity, code) ?: code
            activeInputChip(
                text = label,
                onTap = { SearchFiltersSheet.openCountry(activity, f, onChanged) },
                onClear = {
                    f.countryOfOrigin = null
                    onChanged()
                },
            )
        }

        if (f.isAdult && Anilist.adult) {
            activeInputChip(
                text = activity.getString(R.string.adult),
                onTap = {
                    f.isAdult = false
                    onChanged()
                },
                onClear = {
                    f.isAdult = false
                    onChanged()
                },
            )
        }
    }

    private fun activeInputChip(text: String, onTap: () -> Unit, onClear: () -> Unit) {
        val chip = LayoutInflater.from(activity)
            .inflate(R.layout.item_search_input_chip, row, false) as Chip
        chip.text = text
        chip.contentDescription = text
        chip.closeIconContentDescription = activity.getString(R.string.remove_filter, text)
        chip.setOnClickListener { onTap() }
        chip.setOnCloseIconClickListener { onClear() }
        row.addView(chip)
    }

    companion object {
        fun pretty(raw: String): String = SearchFiltersSheet.pretty(raw)
    }
}
