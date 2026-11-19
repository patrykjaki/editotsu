package ani.dantotsu.media

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AniMangaSearchResults
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.databinding.ItemSearchFilterTileBinding
import ani.dantotsu.databinding.SheetSearchFiltersBinding
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import java.util.Calendar

/**
 * Unified Search Filters bottom sheet: every filter for anime/manga at a glance in a 2-column
 * tile grid. Each tile opens its existing ChoiceSheet or GenreTagSheet (or toggles Adult), and
 * changes update both the tiles and the live search results immediately.
 */
object SearchFiltersSheet {

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

        lateinit var adapter: TileAdapter
        fun sync() {
            b.searchFiltersReset.isVisible = SearchStatePolicy.hasActiveFilters(filters)
            adapter.submit(buildTiles(context, anime, filters, rescue) {
                sync()
                onChanged()
            })
        }

        adapter = TileAdapter()
        b.searchFiltersGrid.layoutManager = GridLayoutManager(context, 2)
        b.searchFiltersGrid.adapter = adapter
        b.searchFiltersGrid.itemAnimator = null

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

    private data class FilterTile(
        val label: String,
        val value: String,
        val active: Boolean,
        val onClick: () -> Unit,
    )

    private fun buildTiles(
        context: Context,
        anime: Boolean,
        f: AniMangaSearchResults,
        rescue: Boolean,
        onMutated: () -> Unit,
    ): List<FilterTile> {
        val any = context.getString(R.string.chip_any)
        val tiles = ArrayList<FilterTile>(10)

        // Sort
        val sortVal = f.sort?.let { sortLabel(context, it) }
        tiles += FilterTile(
            label = context.getString(R.string.chip_sort),
            value = sortVal ?: any,
            active = sortVal != null,
            onClick = { openSort(context, f, onMutated) },
        )

        // Genres & tags
        val hasGenreData = !Anilist.genres.isNullOrEmpty() ||
            (!rescue && !Anilist.tags?.get(f.isAdult).isNullOrEmpty()) ||
            !f.genres.isNullOrEmpty() || !f.tags.isNullOrEmpty() ||
            !f.excludedGenres.isNullOrEmpty() || !f.excludedTags.isNullOrEmpty()
        if (hasGenreData) {
            val inc = f.genres.orEmpty() + f.tags.orEmpty()
            val exc = f.excludedGenres.orEmpty() + f.excludedTags.orEmpty()
            val summary = GenreTagGroups.summary(inc, exc)
            tiles += FilterTile(
                label = context.getString(R.string.genres_and_tags),
                value = summary ?: any,
                active = summary != null,
                onClick = { GenreTagSheet.show(context, f, rescue, onMutated) },
            )
        }

        // Format
        val formatVal = f.format?.let(::pretty)
        tiles += FilterTile(
            label = context.getString(R.string.format),
            value = formatVal ?: any,
            active = formatVal != null,
            onClick = { openFormat(context, anime, f, onMutated) },
        )

        // Status
        val statusVal = f.status?.let(::pretty)
        tiles += FilterTile(
            label = context.getString(R.string.chip_status),
            value = statusVal ?: any,
            active = statusVal != null,
            onClick = { openStatus(context, anime, f, onMutated) },
        )

        // Year
        val year = if (anime) f.seasonYear else f.startYear
        tiles += FilterTile(
            label = context.getString(R.string.year),
            value = year?.toString() ?: any,
            active = year != null,
            onClick = { openYear(context, anime, f, onMutated) },
        )

        // Season (anime only, non-rescue)
        if (anime && !rescue) {
            val seasonVal = f.season?.let(::pretty)
            tiles += FilterTile(
                label = context.getString(R.string.season),
                value = seasonVal ?: any,
                active = seasonVal != null,
                onClick = { openSeason(context, f, onMutated) },
            )
        }

        // List (logged-in non-rescue)
        if (Anilist.userid != null && !rescue) {
            val listVal = listLabel(context, f.onList)
            tiles += FilterTile(
                label = context.getString(R.string.list),
                value = listVal ?: any,
                active = listVal != null,
                onClick = { openList(context, f, onMutated) },
            )
        }

        // Source & Country (non-rescue)
        if (!rescue) {
            val sourceVal = f.source?.let(::pretty)
            tiles += FilterTile(
                label = context.getString(R.string.source),
                value = sourceVal ?: any,
                active = sourceVal != null,
                onClick = { openSource(context, f, onMutated) },
            )

            val countryVal = countryLabel(context, f.countryOfOrigin)
            tiles += FilterTile(
                label = context.getString(R.string.chip_country),
                value = countryVal ?: any,
                active = countryVal != null,
                onClick = { openCountry(context, f, onMutated) },
            )
        }

        // 18+ Adult toggle
        if (Anilist.adult) {
            tiles += FilterTile(
                label = context.getString(R.string.adult),
                value = context.getString(if (f.isAdult) R.string.filter_on else R.string.filter_off),
                active = f.isAdult,
                onClick = {
                    f.isAdult = !f.isAdult
                    onMutated()
                },
            )
        }

        return tiles
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
        return if (s.length <= 3) s else s.lowercase().replaceFirstChar { it.uppercase() }
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
        val options = Anilist.sortBy.indices.map { labels.getOrElse(it) { Anilist.sortBy[it] } }
        openSingle(context, R.string.chip_sort, options, Anilist.sortBy.indexOf(f.sort),
            { f.sort = Anilist.sortBy.getOrNull(it) }, onChanged)
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

    private class TileHolder(val binding: ItemSearchFilterTileBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class TileAdapter : RecyclerView.Adapter<TileHolder>() {
        private var items: List<FilterTile> = emptyList()

        fun submit(next: List<FilterTile>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder =
            TileHolder(
                ItemSearchFilterTileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

        override fun onBindViewHolder(holder: TileHolder, position: Int) {
            val tile = items[position]
            val b = holder.binding
            b.filterTileLabel.text = tile.label
            b.filterTileValue.text = tile.value
            val bgAttr = if (tile.active) {
                com.google.android.material.R.attr.colorSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorSurfaceVariant
            }
            val labelAttr = if (tile.active) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            val valueAttr = if (tile.active) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }
            b.filterTileCard.setCardBackgroundColor(MaterialColors.getColor(b.root, bgAttr))
            b.filterTileLabel.setTextColor(MaterialColors.getColor(b.root, labelAttr))
            b.filterTileValue.setTextColor(MaterialColors.getColor(b.root, valueAttr))
            b.filterTileCard.contentDescription = "${tile.label}: ${tile.value}"
            b.filterTileCard.setOnClickListener { tile.onClick() }
        }
    }
}
