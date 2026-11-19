package ani.dantotsu.media

import ani.dantotsu.others.setDropdownText
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The row under the search bar: every filter is a dropdown chip (the calendar's pattern) that opens a
 * choice sheet, plus one plain filter chip for 18+. A chip is selected while its filter is set and
 * then shows the value. M3 filter chips are on/off; anything with more states is a dropdown.
 */
class SearchFilterChips(
    private val activity: AppCompatActivity,
    private val row: LinearLayout,
    private val type: SearchType,
    private val currentQuery: () -> String,
    /** Anime/manga filters; null for characters, staff, studios and users (type chip only). */
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
        val sortLabels = activity.resources.getStringArray(R.array.sort_by).toList()
        single(R.string.chip_sort, Anilist.sortBy.indices.map { sortLabels.getOrElse(it) { Anilist.sortBy[it] } },
            Anilist.sortBy.indexOf(f.sort)) { f.sort = Anilist.sortBy.getOrNull(it) }
        genreTagChip(f)
        val formats = if (anime) Anilist.animeFormats else Anilist.mangaFormats
        single(R.string.format, formats.map(::pretty), formats.indexOf(f.format)) { f.format = formats.getOrNull(it) }
        val statuses = if (anime) Anilist.animeStatus else Anilist.mangaStatus
        single(R.string.chip_status, statuses.map(::pretty), statuses.indexOf(f.status?.replace("_", " "))) {
            f.status = statuses.getOrNull(it)?.replace(" ", "_")
        }
        val years = (1970..Calendar.getInstance().get(Calendar.YEAR) + 1).reversed().toList()
        val year = if (anime) f.seasonYear else f.startYear
        single(R.string.year, years.map { it.toString() }, years.indexOf(year)) {
            val y = years.getOrNull(it)
            if (anime) f.seasonYear = y else f.startYear = y
        }
        if (anime && !rescue) {
            single(R.string.season, Anilist.seasons.map(::pretty), Anilist.seasons.indexOf(f.season)) {
                f.season = Anilist.seasons.getOrNull(it)
            }
        }
        if (Anilist.userid != null && !rescue) {
            // three answers, so a dropdown: an on/off chip can't say "not on my list"
            val lists = listOf(R.string.filter_on_list_in, R.string.filter_on_list_not_in).map(activity::getString)
            single(R.string.list, lists, when (f.onList) { true -> 0; false -> 1; null -> -1 }) {
                f.onList = when (it) { 0 -> true; 1 -> false; else -> null }
            }
        }
        if (!rescue) {
            single(R.string.source, Anilist.source.map(::pretty), Anilist.source.indexOf(f.source?.replace("_", " "))) {
                f.source = Anilist.source.getOrNull(it)?.replace(" ", "_")
            }
            val codes = listOf("JP", "KR", "CN", "TW")
            val names = listOf(R.string.country_japan, R.string.country_korea, R.string.country_china, R.string.country_taiwan)
                .map(activity::getString)
            single(R.string.chip_country, names, codes.indexOf(f.countryOfOrigin)) { f.countryOfOrigin = codes.getOrNull(it) }
        }
        if (Anilist.adult) {
            chip(activity.getString(R.string.adult), dropdown = false).apply {
                isChecked = f.isAdult
                setOnClickListener {
                    f.isAdult = !f.isAdult
                    isChecked = f.isAdult
                    onChanged()
                }
            }
        }
        setFiltersFirst(f)
    }

    /** Subtle 1dp vertical rule between the search-type scope chip and the filter chips. */
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

    /**
     * Ten filters don't fit on a phone, so the ones in use move to the front (right after the type
     * chip and its divider): a set filter is never scrolled out of sight. Each sheet's "Any" clears its filter.
     */
    private fun setFiltersFirst(@Suppress("UNUSED_PARAMETER") f: AniMangaSearchResults) {
        val chips = (1 until row.childCount).mapNotNull { row.getChildAt(it) as? Chip }
        val set = chips.filter { it.isChecked }
        if (set.isEmpty()) return
        chips.forEach(row::removeView)
        (set + chips.filterNot { it.isChecked }).forEach(row::addView)
        (row.parent as? android.widget.HorizontalScrollView)?.post { (row.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0) }
    }

    private fun chip(text: CharSequence, dropdown: Boolean = true): Chip {
        val chip = LayoutInflater.from(activity).inflate(R.layout.item_search_chip, row, false) as Chip
        if (dropdown) {
            chip.setDropdownText(text)
            chip.textEndPadding = 0f
            chip.chipEndPadding = 5 * activity.resources.displayMetrics.density
        } else {
            chip.text = text
            chip.contentDescription = text
        }
        // set filters get the leading tick, dropdown or not (as in Google's own filter rows)
        chip.isCheckedIconVisible = true
        row.addView(chip)
        return chip
    }

    private fun typeChip() {
        val types = listOf(SearchType.ANIME, SearchType.MANGA, SearchType.CHARACTER, SearchType.STAFF, SearchType.STUDIO, SearchType.USER)
        val names = listOf(R.string.anime, R.string.manga, R.string.characters, R.string.staff, R.string.studios, R.string.users)
            .map(activity::getString)
        val c = chip(names[types.indexOf(type)])
        c.isChecked = true
        c.contentDescription = activity.getString(R.string.search_type_of, names[types.indexOf(type)])
        c.setOnClickListener {
            c.isChecked = true
            ChoiceSheet.single(activity, activity.getString(R.string.search_type), names.map { ChoiceOption(it) }, types.indexOf(type)) { picked ->
                val query = currentQuery()
                // each type has its own results and adapters: start that search with the same text
                activity.startActivity(
                    Intent(activity, SearchActivity::class.java)
                        .putExtra("type", types[picked].toAnilistString())
                        .apply { if (query.isNotBlank()) putExtra("query", query).putExtra("search", true) }
                )
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
                activity.finish()
            }
        }
    }

    /** One-of-N filter. "Any" first clears it. */
    private fun single(title: Int, options: List<String>, selected: Int, set: (Int) -> Unit) {
        val name = activity.getString(title)
        val c = chip(if (selected >= 0) options[selected] else name)
        c.isChecked = selected >= 0
        c.contentDescription = if (selected >= 0) "$name: ${options[selected]}" else name
        c.setOnClickListener {
            c.isChecked = selected >= 0
            val all = listOf(ChoiceOption(activity.getString(R.string.chip_any))) + options.map { ChoiceOption(it) }
            ChoiceSheet.single(activity, name, all, selected + 1) { picked ->
                set(picked - 1)
                onChanged()
            }
        }
    }

    /** Unified genres & tags chip: shows while something is included or excluded, or opens the sheet when tapped. */
    private fun genreTagChip(f: AniMangaSearchResults) {
        val genres = Anilist.genres.orEmpty()
        val tags = if (rescue) emptyList() else Anilist.tags?.get(f.isAdult).orEmpty()
        val inc = f.genres.orEmpty() + f.tags.orEmpty()
        val exc = f.excludedGenres.orEmpty() + f.excludedTags.orEmpty()
        if (genres.isEmpty() && tags.isEmpty() && inc.isEmpty() && exc.isEmpty()) return
        val name = activity.getString(R.string.genres_and_tags)
        val sum = GenreTagGroups.summary(inc, exc)
        val c = chip(sum ?: name)
        c.isChecked = sum != null
        c.contentDescription = if (sum == null) name else "$name: $sum"
        c.setOnClickListener {
            c.isChecked = sum != null
            GenreTagSheet.show(activity, f, rescue, onChanged)
        }
    }

    companion object {
        /** "NOT YET RELEASED" -> "Not yet released"; short codes (TV, OVA, ONA) stay as they are. */
        fun pretty(raw: String): String {
            val s = raw.replace('_', ' ')
            return if (s.length <= 3) s else s.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
