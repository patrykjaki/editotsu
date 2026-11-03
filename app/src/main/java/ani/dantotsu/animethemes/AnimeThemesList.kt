package ani.dantotsu.animethemes

import android.content.Context
import android.content.res.ColorStateList
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
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Openings & endings on the details screen: an Openings and an Endings group, four rows
 * each until "Show all". A row plays the version it shows; themes with several versions
 * open their versions in place, and choosing one plays it and makes it the row's version.
 */
class AnimeThemesList(
    private val binding: LayoutAnimeThemesBinding,
    private val choices: List<ThemeChoice>,
    private val isShielded: (ThemeTrack) -> Boolean,
    private val onPlay: (tapped: ThemeTrack, shown: List<ThemeTrack>) -> Unit,
) {
    private val context: Context = binding.root.context
    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density

    /** Version each row shows; starts at the shield-aware default, changes when one is chosen. */
    private val shown: MutableList<ThemeTrack> =
        choices.map { ThemeQueue.preferredVersion(it, isShielded) }.toMutableList()
    private val openRows = mutableSetOf<Int>()
    private val rows = mutableMapOf<Int, ItemAnimeThemeRowBinding>()

    init {
        val indexed = choices.withIndex()
        section(R.string.theme_section_openings, R.string.theme_show_all_openings, indexed.filter { !it.value.isEnding }.map { it.index })
        section(R.string.theme_section_endings, R.string.theme_show_all_endings, indexed.filter { it.value.isEnding }.map { it.index })
    }

    private fun section(label: Int, showAll: Int, indices: List<Int>) {
        if (indices.isEmpty()) return
        val s = ItemAnimeThemeSectionBinding.inflate(inflater, binding.themesSections, false)
        s.sectionLabel.setText(label)
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

    private fun bindRow(i: Int) {
        val row = rows[i] ?: return
        val choice = choices[i]
        val track = shown[i]
        val hidden = isShielded(track)
        val flag = themeFlagText(context, track.warning)
        val episodes = ThemeQueue.episodesLabel(track.episodes)

        row.themeTag.text = track.tag
        if (hidden) {
            row.themeSong.text = context.getString(R.string.theme_hidden_until, ThemeQueue.firstEpisode(track.episodes) ?: 0)
            row.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins)
            row.themeSong.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        } else {
            row.themeSong.text = track.songTitle.ifBlank { track.tag }
            row.themeSong.typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
            row.themeSong.setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        }
        row.themeMeta.text = meta(if (hidden) null else track.artist, episodes, flag)
        row.themeMeta.visibility = if (row.themeMeta.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        row.themeMain.contentDescription = listOfNotNull(
            if (choice.versions.size > 1) "${track.tag} v${track.version}" else track.tag,
            row.themeSong.text.toString(),
            row.themeMeta.text?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        row.themeMain.setOnClickListener { onPlay(shown[i], shown.toList()) }

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
                onPlay(v, shown.toList())
            }
            row.themeVersions.addView(vr.root)
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

    /** Grouped list: large outer corners on the first and last visible row, small between. */
    private fun shape(view: View, first: Boolean, last: Boolean) {
        val big = 20 * density
        val small = 4 * density
        val model = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (first) big else small)
            .setTopRightCornerSize(if (first) big else small)
            .setBottomLeftCornerSize(if (last) big else small)
            .setBottomRightCornerSize(if (last) big else small)
            .build()
        view.background = MaterialShapeDrawable(model).apply {
            fillColor = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorSurfaceVariant))
        }
        view.clipToOutline = true
    }

    private fun color(attr: Int) = MaterialColors.getColor(context, attr, 0)

    companion object {
        const val COLLAPSED = 4

        fun inflate(
            parent: ViewGroup,
            choices: List<ThemeChoice>,
            isShielded: (ThemeTrack) -> Boolean,
            onPlay: (tapped: ThemeTrack, shown: List<ThemeTrack>) -> Unit,
        ): View {
            val b = LayoutAnimeThemesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            AnimeThemesList(b, choices, isShielded, onPlay)
            return b.root
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
