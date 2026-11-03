package ani.dantotsu.settings.search

import android.content.Context
import android.os.Build
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.settings.AnilistSettingsActivity
import ani.dantotsu.settings.FAQActivity
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.ReaderSettingsActivity
import ani.dantotsu.settings.SettingsAboutActivity
import ani.dantotsu.settings.SettingsAccountActivity
import ani.dantotsu.settings.SettingsGroupActivity
import ani.dantotsu.settings.UserInterfaceSettingsActivity

object SettingsRegistry {

    fun getAllSettings(context: Context): List<SearchableSetting> {
        val list = mutableListOf<SearchableSetting>()

        list.add(
            SearchableSetting(
                title = context.getString(R.string.oled_theme_variant),
                desc = context.getString(R.string.oled_theme_variant_desc),
                icon = R.drawable.ic_round_brightness_4_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.oled_theme_variant)
            )
        )
        // MAX MERGE M3 (upstream 3dbce385).
        list.add(
            SearchableSetting(
                title = context.getString(R.string.use_system_font),
                desc = context.getString(R.string.use_system_font_desc),
                icon = R.drawable.ic_round_font_size_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.use_system_font)
            )
        )
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            list.add(
                SearchableSetting(
                    title = context.getString(R.string.use_material_you),
                    desc = context.getString(R.string.use_material_you_desc),
                    icon = R.drawable.ic_round_new_releases_24,
                    category = context.getString(R.string.settings_appearance),
                    breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                    targetActivity = UserInterfaceSettingsActivity::class.java,
                    highlightKey = context.getString(R.string.use_material_you)
                )
            )
            list.add(
                SearchableSetting(
                    title = context.getString(R.string.use_unique_theme_for_each_item),
                    desc = context.getString(R.string.use_unique_theme_for_each_item_desc),
                    icon = R.drawable.ic_palette,
                    category = context.getString(R.string.settings_appearance),
                    breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                    targetActivity = UserInterfaceSettingsActivity::class.java,
                    highlightKey = context.getString(R.string.use_unique_theme_for_each_item)
                )
            )
            list.add(
                SearchableSetting(
                    title = context.getString(R.string.use_custom_theme),
                    desc = context.getString(R.string.use_custom_theme_desc),
                    icon = R.drawable.ic_palette,
                    category = context.getString(R.string.settings_appearance),
                    breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                    targetActivity = UserInterfaceSettingsActivity::class.java,
                    highlightKey = context.getString(R.string.use_custom_theme)
                )
            )
            list.add(
                SearchableSetting(
                    title = context.getString(R.string.color_picker),
                    desc = context.getString(R.string.color_picker_desc),
                    icon = R.drawable.ic_palette,
                    category = context.getString(R.string.settings_appearance),
                    breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                    targetActivity = UserInterfaceSettingsActivity::class.java,
                    highlightKey = context.getString(R.string.color_picker)
                )
            )
        }

        list.add(
            SearchableSetting(
                title = context.getString(R.string.download_manager_select),
                desc = context.getString(R.string.download_manager_select_desc),
                icon = R.drawable.ic_download_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.download_manager_select)
            )
        )
        // MAX MERGE M3 (upstream 3dbce385).
        list.add(
            SearchableSetting(
                title = context.getString(R.string.download_wifi_only),
                desc = context.getString(R.string.download_wifi_only_desc),
                icon = R.drawable.lan_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.download_wifi_only)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.app_lock),
                desc = context.getString(R.string.app_lock_desc),
                icon = R.drawable.ic_round_lock_open_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.app_lock)
            )
        )
        // MAX MERGE M3.
        list.add(
            SearchableSetting(
                title = context.getString(R.string.change_download_directory),
                desc = context.getString(R.string.change_download_directory_desc),
                icon = R.drawable.ic_round_source_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.change_download_directory)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.always_continue_content),
                desc = context.getString(R.string.always_continue_content_desc),
                icon = R.drawable.ic_round_delete_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.always_continue_content)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.hide_private),
                desc = context.getString(R.string.hide_private_desc),
                icon = R.drawable.ic_round_remove_red_eye_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.hide_private)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.search_source_list),
                desc = context.getString(R.string.search_source_list_desc),
                icon = R.drawable.ic_round_search_sources_24,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.search_source_list)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.recentlyListOnly),
                desc = context.getString(R.string.recentlyListOnly_desc),
                icon = R.drawable.ic_round_new_releases_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.recentlyListOnly)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.adult_only_content),
                desc = context.getString(R.string.adult_only_content_desc),
                icon = R.drawable.ic_round_nsfw_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.adult_only_content),
                isVisible = Anilist.adult
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.immersive_mode),
                desc = context.getString(R.string.immersive_mode_info),
                icon = R.drawable.ic_round_fullscreen_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.immersive_mode)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.hide_notification_dot),
                desc = context.getString(R.string.hide_notification_dot),
                icon = R.drawable.ic_round_notifications_active_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.hide_notification_dot)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.home_layout_show),
                desc = context.getString(R.string.home_layout_show),
                icon = R.drawable.ic_round_playlist_add_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.home_layout_show)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.small_view),
                desc = context.getString(R.string.small_view),
                icon = R.drawable.ic_round_art_track_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.small_view)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.carousel_clearlogo),
                desc = context.getString(R.string.carousel_clearlogo_desc),
                icon = R.drawable.ic_round_auto_awesome_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.carousel_clearlogo)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.banner_animations),
                desc = context.getString(R.string.banner_animations),
                icon = R.drawable.ic_round_animation_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.banner_animations)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.layout_animations),
                desc = context.getString(R.string.layout_animations),
                icon = R.drawable.ic_round_animation_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.layout_animations)
            )
        )
        // MAX MERGE M3.
        list.add(
            SearchableSetting(
                title = context.getString(R.string.hide_spoiler_tags),
                desc = context.getString(R.string.hide_spoiler_tags_desc),
                icon = R.drawable.format_spoiler_24,
                category = context.getString(R.string.settings_appearance),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_appearance)}",
                targetActivity = UserInterfaceSettingsActivity::class.java,
                highlightKey = context.getString(R.string.hide_spoiler_tags)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.prefer_dub),
                desc = context.getString(R.string.prefer_dub_desc),
                icon = R.drawable.ic_round_audiotrack_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.prefer_dub)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.show_yt),
                desc = context.getString(R.string.show_yt_desc),
                icon = R.drawable.ic_round_play_circle_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.show_yt)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.include_list),
                desc = context.getString(R.string.include_list_anime_desc),
                icon = R.drawable.view_list_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.include_list)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.theme_pref_resolution),
                desc = context.getString(R.string.anime_themes_title),
                icon = R.drawable.ic_round_high_quality_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.theme_pref_resolution)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.theme_pref_source),
                desc = context.getString(R.string.anime_themes_title),
                icon = R.drawable.ic_round_movie_filter_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.theme_pref_source)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.theme_pref_credits),
                desc = context.getString(R.string.anime_themes_title),
                icon = R.drawable.ic_round_video_settings_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.theme_pref_credits)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.theme_pref_subtitles),
                desc = context.getString(R.string.anime_themes_title),
                icon = R.drawable.ic_round_subtitles_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.theme_pref_subtitles)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.default_speed),
                desc = context.getString(R.string.default_playback_speed, "1x"),
                icon = R.drawable.ic_round_play_circle_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.default_speed)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.cursed_speeds),
                desc = context.getString(R.string.cursed_speeds),
                icon = R.drawable.ic_round_play_circle_24,
                category = context.getString(R.string.settings_player),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_player)}",
                targetActivity = PlayerSettingsActivity::class.java,
                highlightKey = context.getString(R.string.cursed_speeds)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.true_colors),
                desc = context.getString(R.string.true_colors),
                icon = R.drawable.ic_palette,
                category = context.getString(R.string.settings_reading),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_reading)}",
                targetActivity = ReaderSettingsActivity::class.java,
                highlightKey = context.getString(R.string.true_colors)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.crop_borders),
                desc = context.getString(R.string.crop_borders),
                icon = R.drawable.ic_round_screen_rotation_alt_24,
                category = context.getString(R.string.settings_reading),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_reading)}",
                targetActivity = ReaderSettingsActivity::class.java,
                highlightKey = context.getString(R.string.crop_borders)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.anime_add_repository),
                desc = context.getString(R.string.anime_add_repository_desc),
                icon = R.drawable.ic_github,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.anime_add_repository)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.manga_add_repository),
                desc = context.getString(R.string.manga_add_repository_desc),
                icon = R.drawable.ic_github,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.manga_add_repository)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.proxy),
                desc = context.getString(R.string.proxy_desc),
                icon = R.drawable.swap_horizontal_circle_24,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.proxy)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.NSFWExtention),
                desc = context.getString(R.string.NSFWExtention_desc),
                icon = R.drawable.ic_round_nsfw_24,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.NSFWExtention)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.torrent_encryption),
                desc = context.getString(R.string.torrent_encryption_desc),
                icon = R.drawable.ic_round_lock_24,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.torrent_encryption)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.torrent_wifi_only),
                desc = context.getString(R.string.torrent_wifi_only_desc),
                icon = R.drawable.lan_24,
                category = context.getString(R.string.settings_sources),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_sources)}",
                targetActivity = SettingsGroupActivity::class.java,
                highlightKey = context.getString(R.string.torrent_wifi_only)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.torrent_cache_quota),
                desc = context.getString(R.string.torrent_cache_quota_desc),
                icon = R.drawable.ic_round_folder_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.torrent_cache_quota)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.clear_torrent_cache),
                desc = context.getString(R.string.clear_torrent_cache_desc),
                icon = R.drawable.ic_round_delete_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.clear_torrent_cache)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.clear_app_cache),
                desc = context.getString(R.string.clear_app_cache_desc),
                icon = R.drawable.ic_round_delete_24,
                category = context.getString(R.string.label_data_storage),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.label_data_storage)}",
                targetActivity = ani.dantotsu.settings.data.SettingsDataActivity::class.java,
                highlightKey = context.getString(R.string.clear_app_cache)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.subscriptions_checking_time),
                desc = context.getString(R.string.subscriptions_checking_time),
                icon = R.drawable.ic_round_notifications_none_24,
                category = context.getString(R.string.settings_tracking),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_tracking)}",
                targetActivity = SettingsAccountActivity::class.java,
                highlightKey = context.getString(R.string.subscriptions_checking_time)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.comment_notification_checking_time),
                desc = context.getString(R.string.comment_notification_checking_time_desc),
                icon = R.drawable.ic_round_notifications_none_24,
                category = context.getString(R.string.settings_tracking),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_tracking)}",
                targetActivity = SettingsAccountActivity::class.java,
                highlightKey = context.getString(R.string.comment_notification_checking_time)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.discord_rich_presence),
                desc = context.getString(R.string.discord_rich_presence_desc),
                icon = R.drawable.interests_24,
                category = context.getString(R.string.settings_tracking),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_tracking)}",
                targetActivity = SettingsAccountActivity::class.java,
                highlightKey = context.getString(R.string.discord_rich_presence),
                isVisible = true
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.anilist_settings),
                desc = context.getString(R.string.alsettings_desc),
                icon = R.drawable.ic_anilist,
                category = context.getString(R.string.settings_tracking),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_tracking)}",
                targetActivity = AnilistSettingsActivity::class.java,
                highlightKey = context.getString(R.string.anilist_settings)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.comments_button),
                desc = context.getString(R.string.comments_button_desc),
                icon = R.drawable.ic_round_comment_24,
                category = context.getString(R.string.settings_tracking),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.settings_tracking)}",
                targetActivity = SettingsAccountActivity::class.java,
                highlightKey = context.getString(R.string.comments_button),
                isVisible = (Anilist.token != null)
            )
        )

        list.add(
            SearchableSetting(
                title = context.getString(R.string.faq),
                desc = context.getString(R.string.faq_desc),
                icon = R.drawable.ic_round_help_24,
                category = context.getString(R.string.about),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.about)}",
                targetActivity = FAQActivity::class.java,
                highlightKey = context.getString(R.string.faq)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.devs),
                desc = context.getString(R.string.devs_desc),
                icon = R.drawable.ic_round_accessible_forward_24,
                category = context.getString(R.string.about),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.about)}",
                targetActivity = SettingsAboutActivity::class.java,
                highlightKey = context.getString(R.string.devs)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.forks),
                desc = context.getString(R.string.forks_desc),
                icon = R.drawable.ic_round_restaurant_24,
                category = context.getString(R.string.about),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.about)}",
                targetActivity = SettingsAboutActivity::class.java,
                highlightKey = context.getString(R.string.forks)
            )
        )
        list.add(
            SearchableSetting(
                title = context.getString(R.string.privacy_policy),
                desc = context.getString(R.string.privacy_policy_desc),
                icon = R.drawable.ic_incognito_24,
                category = context.getString(R.string.about),
                breadcrumbs = "${context.getString(R.string.settings)} > ${context.getString(R.string.about)}",
                targetActivity = SettingsAboutActivity::class.java,
                highlightKey = context.getString(R.string.privacy_policy)
            )
        )

        return list
    }

    fun search(context: Context, query: String): List<SearchableSetting> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return emptyList()

        val all = getAllSettings(context).filter { it.isVisible }

        val scored = all.mapNotNull { setting ->
            val title = setting.title.lowercase()
            val desc = setting.desc?.lowercase() ?: ""
            val category = setting.category.lowercase()

            var score = 0
            when {
                title == trimmed -> score += 100
                title.startsWith(trimmed) -> score += 75
                title.contains(trimmed) -> score += 50
                desc.contains(trimmed) -> score += 25
                category.contains(trimmed) -> score += 15
                else -> {
                    val queryWords = trimmed.split(" ").filter { it.isNotBlank() }
                    var wordMatches = 0
                    for (word in queryWords) {
                        if (title.contains(word) || desc.contains(word) || category.contains(word)) {
                            wordMatches++
                        }
                    }
                    if (wordMatches > 0) {
                        score += wordMatches * 10
                    }
                }
            }

            if (score > 0) setting to score else null
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
