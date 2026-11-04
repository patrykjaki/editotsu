package ani.dantotsu.settings.screens

import ani.dantotsu.R
import ani.dantotsu.restartApp
import ani.dantotsu.settings.ReaderSettingsActivity
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.Segment
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.segmentedRow
import ani.dantotsu.settings.rows.stepperRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale
import kotlin.math.roundToInt

/** Settings > Reading: defaults for the manga and novel readers. */
object ReaderScreen : SettingsScreen("reading", R.string.settings_reading, R.drawable.ic_round_import_contacts_24, R.string.settings_reading_desc) {

    override val activity get() = ReaderSettingsActivity::class.java

    private val dualPage = listOf(
        Segment(R.drawable.ic_round_close_24, R.string.dual_page_off),
        Segment(R.drawable.ic_round_screen_rotation_24, R.string.dual_page_auto),
        Segment(R.drawable.ic_round_menu_book_24, R.string.dual_page_force),
    )

    override fun sections() = listOf(
        Section(R.string.default_manga_settings, listOf(
            segmentedRow(PrefName.LayoutReader, R.string.layout, R.drawable.ic_round_view_column_24, listOf(
                Segment(R.drawable.ic_round_amp_stories_24, R.string.reader_layout_paged),
                Segment(R.drawable.ic_round_view_array_24, R.string.reader_layout_continuous_paged),
                Segment(R.drawable.ic_round_view_column_24, R.string.reader_layout_continuous),
            )),
            choiceRow(PrefName.Direction, R.string.direction, R.drawable.ic_round_swipe_up_alt_24,
                labels = { it.resources.getStringArray(R.array.manga_directions).toList() }),
            segmentedRow(PrefName.DualPageModeReader, R.string.dual_page, R.drawable.ic_round_menu_book_24, dualPage),
            switchRow(PrefName.AutoDetectWebtoon, R.string.auto_detect_webtoon, R.string.auto_detect_webtoon_info,
                R.drawable.ic_round_auto_awesome_24),
            switchRow(PrefName.TrueColors, R.string.true_colors, R.string.true_colors_info, R.drawable.ic_round_high_quality_24),
            switchRow(PrefName.CropBorders, R.string.crop_borders, icon = R.drawable.ic_round_auto_awesome_24),
            switchRow(PrefName.Padding, R.string.spaced_pages, icon = R.drawable.ic_round_space_bar_24),
            switchRow(PrefName.WrapImages, R.string.wrap_images, R.string.wrap_images_info, R.drawable.ic_round_fullscreen_24),
            switchRow(PrefName.KeepScreenOn, R.string.keep_screen_on, icon = R.drawable.ic_baseline_screen_lock_portrait_24),
            switchRow(PrefName.VolumeButtonsReader, R.string.volume_buttons, icon = R.drawable.ic_round_touch_app_24),
            switchRow(PrefName.LongClickImage, R.string.image_long_clicking, icon = R.drawable.ic_round_touch_app_24),
            switchRow(PrefName.Rotation, R.string.image_rotation, icon = R.drawable.ic_round_screen_rotation_alt_24,
                advanced = true),
            switchRow(PrefName.OverScrollMode, R.string.over_scroll, icon = R.drawable.ic_round_swipe_vertical_24,
                advanced = true),
            switchRow(PrefName.HideScrollBar, R.string.hide_scroll_bar, icon = R.drawable.ic_round_no_scroll_bar,
                advanced = true),
            switchRow(PrefName.HorizontalScrollBar, R.string.horizontal_scroll_bar, icon = R.drawable.ic_round_straighten_24,
                advanced = true),
            switchRow(PrefName.HidePageNumbers, R.string.hide_page_numbers, icon = R.drawable.ic_page_numbering,
                advanced = true),
        )),
        Section(R.string.default_novel_settings, listOf(
            segmentedRow(PrefName.LayoutNovel, R.string.layout, R.drawable.ic_round_view_column_24, listOf(
                Segment(R.drawable.ic_round_amp_stories_24, R.string.reader_layout_paged),
                Segment(R.drawable.ic_round_view_column_24, R.string.novel_layout_scrolled),
            )),
            segmentedRow(PrefName.DualPageModeNovel, R.string.dual_page, R.drawable.ic_round_menu_book_24, dualPage),
            switchRow(PrefName.UseDarkThemeNovel, R.string.use_dark_theme, icon = R.drawable.ic_round_brightness_4_24),
            switchRow(PrefName.UseOledThemeNovel, R.string.use_oled_theme, icon = R.drawable.ic_round_brightness_4_24),
            switchRow(PrefName.KeepScreenOnNovel, R.string.keep_screen_on, icon = R.drawable.ic_baseline_screen_lock_portrait_24),
            switchRow(PrefName.VolumeButtonsNovel, R.string.volume_buttons, icon = R.drawable.ic_round_touch_app_24),
            stepperRow(PrefName.LineHeight, R.string.novel_line_height, from = 0.8f, to = 3f, step = 0.1f,
                format = { _, v -> String.format(Locale.US, "%.1f", v) }, advanced = true),
            stepperRow(PrefName.Margin, R.string.novel_margin, from = 0f, to = 0.3f, step = 0.01f,
                format = { c, v -> c.getString(R.string.percent_value, (v * 100).roundToInt()) }, advanced = true),
            stepperRow(PrefName.MaxInlineSize, R.string.novel_max_inline_size, R.string.novel_max_inline_size_desc,
                from = 200f, to = 4000f, step = 10f, format = { _, v -> v.toInt().toString() }, advanced = true),
            stepperRow(PrefName.MaxBlockSize, R.string.novel_max_block_size, R.string.novel_max_block_size_desc,
                from = 200f, to = 4000f, step = 10f, format = { _, v -> v.toInt().toString() }, advanced = true),
        )),
        Section(R.string.general, listOf(
            switchRow(PrefName.ShowSource, R.string.source_info, icon = R.drawable.ic_round_info_24),
            switchRow(PrefName.ShowSystemBars, R.string.show_system_bars, icon = R.drawable.ic_round_smart_button_24),
        )),
        Section(R.string.manga, listOf(
            choiceRow(PrefName.MangaDefaultView, R.string.default_chp_view, R.drawable.view_list_24,
                labels = { listOf(it.getString(R.string.list), it.getString(R.string.compact)) }),
            switchRow(PrefName.IncludeMangaList, R.string.include_list, R.string.include_list_desc, R.drawable.view_list_24,
                onChange = { host, _ -> host.activity.restartApp() }),
            switchRow(PrefName.SmartDownloadManga, R.string.smart_download_manga, R.string.smart_download_manga_desc,
                R.drawable.ic_download_24),
        )),
    )
}
