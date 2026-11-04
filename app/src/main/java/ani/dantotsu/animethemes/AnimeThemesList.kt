package ani.dantotsu.animethemes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.inSpans
import androidx.core.view.ViewCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemAnimeThemeRowBinding
import ani.dantotsu.databinding.ItemAnimeThemeSectionBinding
import ani.dantotsu.databinding.ItemAnimeThemeVersionBinding
import ani.dantotsu.databinding.LayoutAnimeThemesBinding
import com.google.android.material.color.MaterialColors

/**
 * Openings & endings on the Themes tab: an Openings and an Endings group, four rows
 * each until "Show all". A row plays the version it shows; themes with several versions
 * open their versions in place, and choosing one plays it and makes it the row's version.
 * Rows the episode shield hides are blurred (API 31+) until tapped once.
 */
class AnimeThemesList(
    private val binding: LayoutAnimeThemesBinding,
    private val choices: List<ThemeChoice>,
    private val isShielded: (ThemeTrack) -> Boolean,
    private val onPlay: (tapped: ThemeTrack, shown: List<ThemeTrack>) -> Unit,
    private val onPlayAll: (queue: List<ThemeTrack>) -> Unit = {},
    private val onSettings: () -> Unit = {},
    private val downloads: ThemeDownloadControls? = null,
) {
    val root: View get() = binding.root
    private val context: Context = binding.root.context
    private val inflater = LayoutInflater.from(context)

    /** Version each row shows; starts at the shield-aware default, changes when one is chosen. */
    private val shown: MutableList<ThemeTrack> =
        choices.map { ThemeQueue.preferredVersion(it, isShielded) }.toMutableList()
    private val openRows = mutableSetOf<Int>()
    private val revealed = mutableSetOf<Int>()
    private val rows = mutableMapOf<Int, ItemAnimeThemeRowBinding>()

    init {
        binding.themesSettings.setOnClickListener { onSettings() }
        ThemeAudioToggle.bind(binding.themesAudioOnly)
        val indexed = choices.withIndex()
        section(R.string.theme_section_openings, R.string.theme_show_all_openings, indexed.filter { !it.value.isEnding }.map { it.index })
        section(R.string.theme_section_endings, R.string.theme_show_all_endings, indexed.filter { it.value.isEnding }.map { it.index })
    }

    private fun section(label: Int, showAll: Int, indices: List<Int>) {
        if (indices.isEmpty()) return
        val s = ItemAnimeThemeSectionBinding.inflate(inflater, binding.themesSections, false)
        s.sectionLabel.setText(label)
        val playAll = context.getString(if (label == R.string.theme_section_endings) R.string.theme_play_all_endings else R.string.theme_play_all_openings)
        s.sectionPlayAll.contentDescription = playAll
        s.sectionPlayAll.tooltipText = playAll
        s.sectionPlayAll.setOnClickListener {
            val queue = indices.map { shown[it] }.filterNot { hiddenNow(it) }
            if (queue.isNotEmpty()) onPlayAll(queue)
        }
        indices.forEach { i ->
            val row = ItemAnimeThemeRowBinding.inflate(inflater, s.sectionRows, false)
            rows[i] = row
            bindRow(i)
            s.sectionRows.addView(row.root)
        }
        var expanded = false
        fun apply() {
            indices.forEachIndexed { pos, i -> rows[i]!!.root.visibility = if (expanded || pos < COLLAPSED) View.VISIBLE else View.GONE }
            val visible = if (expanded) indices.size else minOf(indices.size, COLLAPSED)
            indices.take(visible).forEachIndexed { pos, i -> shape(rows[i]!!.root, pos == 0, pos == visible - 1) }
            s.sectionToggle.text = if (expanded) context.getString(R.string.theme_show_less)
            else context.getString(showAll, indices.size)
            s.sectionToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (expanded) R.drawable.ic_round_arrow_drop_up_24 else R.drawable.ic_round_arrow_drop_down_24, 0,
            )
        }
        s.sectionToggle.visibility = if (indices.size > COLLAPSED) View.VISIBLE else View.GONE
        s.sectionToggle.setOnClickListener { expanded = !expanded; apply() }
        apply()
        binding.themesSections.addView(s.root)
    }

    /** Shield-hidden and not revealed by a tap on its row. */
    private fun hiddenNow(track: ThemeTrack): Boolean {
        val i = shown.indexOfFirst { it.entryId == track.entryId && it.themeId == track.themeId }
        return isShielded(track) && i !in revealed
    }

    private fun bindRow(i: Int) {
        val row = rows[i] ?: return
        val choice = choices[i]
        val track = shown[i]
        val hidden = isShielded(track) && i !in revealed
        val flag = themeFlagText(context, track.warning)
        val episodes = ThemeQueue.episodesLabel(track.episodes)
        val hiddenLabel = context.getString(R.string.theme_hidden_until, ThemeQueue.firstEpisode(track.episodes) ?: 0)

        row.themeTag.text = track.tag
        row.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
        row.themeSong.setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        if (hidden && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The title stays in place, blurred; the line below says why.
            row.themeSong.text = track.songTitle.ifBlank { track.tag }
            row.themeSong.setRenderEffect(RenderEffect.createBlurEffect(BLUR_PX, BLUR_PX, Shader.TileMode.CLAMP))
            row.themeMeta.text = meta(null, hiddenLabel, flag)
        } else if (hidden) {
            row.themeSong.text = hiddenLabel
            row.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins)
            row.themeSong.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            row.themeMeta.text = meta(null, episodes, flag)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) row.themeSong.setRenderEffect(null)
            row.themeSong.text = track.songTitle.ifBlank { track.tag }
            row.themeMeta.text = meta(track.artist, episodes, flag)
        }
        row.themeMeta.visibility = if (row.themeMeta.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        val tag = if (choice.versions.size > 1) "${track.tag} v${track.version}" else track.tag
        row.themeMain.contentDescription = if (hidden) {
            listOfNotNull(tag, hiddenLabel, flag).joinToString(", ")
        } else {
            listOfNotNull(tag, row.themeSong.text.toString(), row.themeMeta.text?.toString()?.takeIf { it.isNotBlank() })
                .joinToString(", ")
        }
        ViewCompat.replaceAccessibilityAction(
            row.themeMain,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            context.getString(if (hidden) R.string.theme_reveal else R.string.theme_play),
            null,
        )
        row.themeMain.setOnClickListener {
            if (isShielded(shown[i]) && i !in revealed) {
                revealed += i
                bindRow(i)
            } else {
                onPlay(shown[i], shown.mapIndexed { j, t -> if (j == i || !hiddenNow(t)) t else null }.filterNotNull())
            }
        }
        bindFavourite(row, track)
        bindDownload(row, track)

        val count = choice.versions.size
        if (count < 2) {
            row.themeVersionsToggle.visibility = View.GONE
            row.themeVersions.visibility = View.GONE
            return
        }
        val open = i in openRows
        val label = context.getString(R.string.theme_version_of, track.version, count)
        row.themeVersionsToggle.visibility = View.VISIBLE
        row.themeVersionsToggle.text = label
        row.themeVersionsToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (open) R.drawable.ic_round_arrow_drop_up_24 else R.drawable.ic_round_arrow_drop_down_24, 0,
        )
        row.themeVersionsToggle.contentDescription = context.getString(R.string.theme_versions_action, track.tag, label)
        ViewCompat.setStateDescription(
            row.themeVersionsToggle,
            context.getString(if (open) R.string.theme_versions_open else R.string.theme_versions_closed),
        )
        row.themeVersionsToggle.setOnClickListener {
            if (!openRows.add(i)) openRows.remove(i)
            bindRow(i)
        }
        row.themeVersions.visibility = if (open) View.VISIBLE else View.GONE
        row.themeVersions.removeAllViews()
        if (!open) return
        choice.versions.forEach { v ->
            val vr = ItemAnimeThemeVersionBinding.inflate(inflater, row.themeVersions, false)
            val vHidden = isShielded(v)
            val first = ThemeQueue.firstEpisode(v.episodes)
            vr.versionNumber.text = "v${v.version}"
            vr.versionText.text = if (vHidden && first != null) context.getString(R.string.theme_hidden_until, first)
            else ThemeQueue.episodesLabel(v.episodes) ?: v.tag
            vr.versionText.setTextColor(color(
                if (vHidden) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface
            ))
            val vFlag = themeFlagText(context, v.warning)
            vr.versionFlag.text = vFlag
            vr.versionFlag.visibility = if (vFlag == null) View.GONE else View.VISIBLE
            val selected = v.entryId == track.entryId
            vr.versionCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            vr.root.isSelected = selected
            vr.root.contentDescription = listOfNotNull("v${v.version}", vr.versionText.text.toString(), vFlag).joinToString(", ")
            if (selected) ViewCompat.setStateDescription(vr.root, context.getString(R.string.theme_version_selected))
            vr.root.setOnClickListener {
                shown[i] = v
                bindRow(i)
                downloads?.refresh(shown.toList())
                onPlay(v, shown.toList())
            }
            row.themeVersions.addView(vr.root)
        }
    }

    private fun bindFavourite(row: ItemAnimeThemeRowBinding, track: ThemeTrack) {
        fun apply() {
            val fav = ThemeFavourites.isFavourite(track.themeId)
            row.themeFavourite.setImageResource(if (fav) R.drawable.ic_round_favorite_24 else R.drawable.ic_round_favorite_border_24)
            row.themeFavourite.imageTintList = ColorStateList.valueOf(color(
                if (fav) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
            ))
            val label = context.getString(if (fav) R.string.theme_favourite_remove else R.string.theme_favourite_add)
            row.themeFavourite.contentDescription = label
            row.themeFavourite.tooltipText = label
        }
        apply()
        row.themeFavourite.setOnClickListener {
            ThemeFavourites.toggle(track.mediaId, track.showTitle, track)
            apply()
        }
    }

    /** "Artist · Eps 1–12 · Spoiler", the flag in the error colour. */
    private fun meta(artist: String?, episodes: String?, flag: String?): CharSequence {
        val sb = SpannableStringBuilder()
        listOfNotNull(artist?.takeIf { it.isNotBlank() }, episodes).forEach {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(it)
        }
        if (flag != null) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.inSpans(ForegroundColorSpan(color(androidx.appcompat.R.attr.colorError))) { append(flag) }
        }
        return sb
    }

    private fun shape(view: View, first: Boolean, last: Boolean) = ThemeRowShape.apply(view, first, last)

    /** Download button per row; hidden when the list has no download controls. */
    private fun bindDownload(row: ItemAnimeThemeRowBinding, track: ThemeTrack) {
        val controls = downloads
        (row.themeDownload.parent as View).visibility = if (controls == null) View.GONE else View.VISIBLE
        controls?.bind(row.themeDownload, row.themeDownloadProgress, track, mediaId = 0)
    }

    /** Tracks the rows show, for reading their download states. */
    fun shownTracks(): List<ThemeTrack> = shown.toList()

    /** Redraws the download buttons after the states changed. */
    fun refreshDownloads() {
        rows.forEach { (i, row) -> bindDownload(row, shown[i]) }
    }

    private fun color(attr: Int) = MaterialColors.getColor(context, attr, 0)

    companion object {
        const val COLLAPSED = 4
        private const val BLUR_PX = 18f

        fun inflate(
            parent: ViewGroup,
            choices: List<ThemeChoice>,
            isShielded: (ThemeTrack) -> Boolean,
            onPlay: (tapped: ThemeTrack, shown: List<ThemeTrack>) -> Unit,
            onPlayAll: (queue: List<ThemeTrack>) -> Unit = {},
            onSettings: () -> Unit = {},
            downloads: ThemeDownloadControls? = null,
        ): AnimeThemesList {
            val b = LayoutAnimeThemesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AnimeThemesList(b, choices, isShielded, onPlay, onPlayAll, onSettings, downloads)
        }
    }
}

/** "Spoiler", "NSFW", "Spoiler · NSFW"; null when AnimeThemes flags nothing. */
internal fun themeFlagText(context: Context, warning: ThemeWarningType): String? = when (warning) {
    ThemeWarningType.NONE -> null
    ThemeWarningType.SPOILER -> context.getString(R.string.theme_flag_spoiler)
    ThemeWarningType.NSFW -> context.getString(R.string.theme_flag_nsfw)
    ThemeWarningType.SPOILER_AND_NSFW ->
        context.getString(R.string.theme_flag_spoiler) + " · " + context.getString(R.string.theme_flag_nsfw)
}
