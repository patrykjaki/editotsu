package ani.dantotsu.settings.screens

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.connections.subtitles.OnlineSubtitleLanguages
import ani.dantotsu.media.anime.player.ActiveVideoPipeline
import ani.dantotsu.media.anime.player.PlaybackSpeedSelection
import ani.dantotsu.media.anime.player.SubtitleStylingMode
import ani.dantotsu.media.anime.player.SubtitleStylingStore
import ani.dantotsu.media.anime.player.VideoFilterTheme
import ani.dantotsu.media.anime.player.upscaler.CunnyUpscalerProvider
import ani.dantotsu.media.anime.player.upscaler.UpscalerBundleManager
import ani.dantotsu.media.anime.player.upscaler.UpscalerChoices
import ani.dantotsu.restartApp
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.ResolutionPriorityDialog
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.rows.sliderRow
import ani.dantotsu.settings.rows.stepperRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.others.FlavorFeatures
import java.util.Locale
import kotlin.math.roundToInt

/** Settings > Player: playback, filters, subtitles, segments, gestures, anime defaults. */
object PlayerScreen : SettingsScreen("player", R.string.settings_player, R.drawable.ic_round_video_settings_24, R.string.settings_player_desc) {

    override val activity get() = PlayerSettingsActivity::class.java

    // Stored values are indices into these; the order must not change.
    private val SUB_LANGUAGES = listOf(
        "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish",
        "Dutch", "English", "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew",
        "Hindi", "Indonesian", "Irish", "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish",
        "Macedonian", "Mongolian", "Norwegian", "Polish", "Portuguese", "Punjabi", "Romanian",
        "Russian", "Serbian", "Slovak", "Slovenian", "Spanish", "Turkish", "Ukrainian", "Urdu",
        "Vietnamese",
    )

    /** Same order as MpvSubtitleFont. */
    private val FONTS = listOf(
        "Poppins Semi Bold", "Poppins Bold", "Poppins", "Poppins Thin", "Century Gothic",
        "Levenim MT Bold", "Blocky",
    )
    private val FONT_RES = listOf(
        R.font.poppins_semi_bold, R.font.poppins_bold, R.font.poppins, R.font.poppins_thin,
        R.font.century_gothic_regular, R.font.levenim_mt_bold, R.font.blocky,
    )

    private val PROVIDERS = listOf("Wyzie", "Stremio", "SubSource", "OpenSubtitles")
    private val PROVIDER_LABELS = listOf("Wyzie", "Stremio OpenSubtitles", "SubSource", "OpenSubtitles REST")

    private val DEBAND_KEYS = listOf("None", "CPU", "GPU")
    private val PROFILES = listOf(
        VideoFilterTheme.Default to R.string.color_profile_default,
        VideoFilterTheme.Vivid to R.string.color_profile_vivid,
        VideoFilterTheme.Cinema to R.string.color_profile_cinema,
        VideoFilterTheme.Vintage to R.string.color_profile_vintage,
    )
    private val SUB_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val BOOST_CAPS = listOf(0, 15, 30, 50)

    private val onlineSubsOn = { PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled) }
    private val timestampsOn = { PrefManager.getVal<Boolean>(PrefName.TimeStampsEnabled) }

    private val applyToPlayer: (SettingsHost, Any) -> Unit = { _, _ -> ActiveVideoPipeline.refreshFromPrefs() }
    private val refreshPreview: (SettingsHost, Any) -> Unit = { host, _ -> host.refresh() }

    private fun speedLabels(): List<String> =
        PlaybackSpeedSelection.values(PrefManager.getVal(PrefName.CursedSpeeds)).map { "${it}x" }

    private fun percent(c: Context, v: Float) = c.getString(R.string.percent_value, (v * 100).roundToInt())

    /**
     * A row whose value is not a plain index pref: the row shows [label] of the [current]
     * option and a tap lists them all.
     */
    private fun pickRow(
        id: String,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        keys: List<PrefName>,
        labels: (Context) -> List<String>,
        current: (Context) -> Int,
        advanced: Boolean = false,
        visible: () -> Boolean = { true },
        enabled: () -> Boolean = { true },
        onOpen: (SettingsHost) -> Unit = {},
        onPick: (SettingsHost, Int) -> Unit,
    ) = actionRow(
        id, title, icon = icon, advanced = advanced, visible = visible, enabled = enabled, keys = keys,
        current = { c -> labels(c).getOrNull(current(c)) ?: c.getString(R.string.color_profile_custom) },
    ) { host, _ ->
        val c = host.activity
        onOpen(host)
        c.customAlertDialog().apply {
            setTitle(c.getString(title))
            singleChoiceItems(labels(c).toTypedArray(), current(c)) { index ->
                onPick(host, index)
                host.refresh()
            }
            show()
        }
    }

    /** Several of [values] at once, stored as a string set. */
    private fun multiRow(
        key: PrefName,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        values: List<String>,
        labels: List<String>,
        /** Stored set as [values]; the languages pref may hold old names instead of codes. */
        read: (Set<String>) -> Set<String> = { it },
    ) = actionRow(
        key.name, title, icon = icon, enabled = onlineSubsOn, keys = listOf(key),
        current = { c ->
            val set = read(PrefManager.getVal(key))
            values.indices.filter { values[it] in set }.joinToString(", ") { labels[it] }
                .ifEmpty { c.getString(R.string.nothing_selected) }
        },
    ) { host, _ ->
        val c = host.activity
        val set = read(PrefManager.getVal(key))
        c.customAlertDialog().apply {
            setTitle(c.getString(title))
            multiChoiceItems(labels.toTypedArray(), BooleanArray(values.size) { values[it] in set }) { checked ->
                PrefManager.setVal(key, values.filterIndexed { i, _ -> checked[i] }.toSet())
                host.refresh()
            }
            setPosButton(R.string.ok)
            show()
        }
    }

    /** Milliseconds typed into a dialog, clamped to ±5000. */
    private fun delayRow(key: PrefName, @StringRes title: Int) = actionRow(
        key.name, title, icon = R.drawable.ic_round_audiotrack_24, advanced = true, keys = listOf(key),
        current = { c -> c.getString(R.string.ms_value, PrefManager.getVal<Int>(key)) },
    ) { host, _ ->
        val c = host.activity
        val input = EditText(c).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(PrefManager.getVal<Int>(key).toString())
            setSelection(text.length)
            hint = c.getString(R.string.delay_range)
        }
        val margin = (24 * c.resources.displayMetrics.density).toInt()
        val container = FrameLayout(c).apply {
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(input)
        }
        c.customAlertDialog().apply {
            setTitle(c.getString(title))
            setCustomView(container)
            setPosButton(R.string.ok) {
                val value = input.text.toString().toIntOrNull()?.coerceIn(-5000, 5000) ?: 0
                PrefManager.setVal(key, value)
                ActiveVideoPipeline.refreshFromPrefs()
                host.refresh()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun colorRow(key: PrefName, @StringRes title: Int, @StringRes summary: Int? = null, advanced: Boolean = false) =
        actionRow(
            key.name, title, summary, R.drawable.ic_round_color_24, advanced = advanced,
            keys = listOf(key),
            current = { String.format(Locale.US, "#%08X", PrefManager.getVal<Int>(key)) },
        ) { host, _ -> (host.activity as? PlayerSettingsActivity)?.pickColor(key, title) }

    private fun themeRow(@StringRes title: Int, @DrawableRes icon: Int, key: PrefName, labels: (Context) -> List<String>) =
        pickRow(
            key.name, title, icon, listOf(key), labels,
            current = { PrefManager.getVal<Int>(key).coerceIn(0, labels(it).lastIndex) },
        ) { _, i -> PrefManager.setVal(key, i) }

    private val subtitlePreview = customRow(
        "sub_preview", R.string.sub_preview, R.layout.item_setting_sub_preview, inSection = true,
    ) { _, view ->
        // Only what the player's subtitle style applies: font, size, text colour, background.
        view.findViewById<TextView>(R.id.subPreviewText).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, PrefManager.getVal<Int>(PrefName.FontSize).toFloat())
            val font = FONT_RES.getOrElse(PrefManager.getVal(PrefName.Font)) { FONT_RES[0] }
            typeface = ResourcesCompat.getFont(context, font)
            setTextColor(PrefManager.getVal<Int>(PrefName.PrimaryColor))
            setBackgroundColor(PrefManager.getVal<Int>(PrefName.SubBackground))
        }
    }

    override fun sections() = listOf(
        Section(R.string.video, listOf(
            // Speed new episodes start at; the player's speed button overrides it per show.
            pickRow(
                PrefName.DefaultSpeed.name, R.string.default_speed, R.drawable.ic_round_slow_motion_video_24,
                listOf(PrefName.DefaultSpeed), labels = { speedLabels() },
                current = { PrefManager.getVal<Int>(PrefName.DefaultSpeed).coerceIn(0, speedLabels().lastIndex) },
            ) { _, i -> PrefManager.setVal(PrefName.DefaultSpeed, i) },
            // 50x playback, for the brave. Both lists have 1x at a different index.
            switchRow(PrefName.CursedSpeeds, R.string.cursed_speeds, icon = R.drawable.ic_round_accessible_forward_24,
                refresh = true) { _, cursed -> PrefManager.setVal(PrefName.DefaultSpeed, if (cursed) 0 else 5) },
            // How the video fills the screen when playback starts (original, zoom, stretch).
            choiceRow(PrefName.Resize, R.string.default_resize_mode, R.drawable.ic_round_fullscreen_24, labels = {
                listOf(it.getString(R.string.resize_original), it.getString(R.string.resize_zoom),
                    it.getString(R.string.resize_stretch))
            }),
        )),
        Section(R.string.player_filters, listOf(
            // Smooths colour banding in dark gradients; CPU uses ffmpeg gradfun, GPU uses libmpv's deband.
            pickRow(
                PrefName.VideoDebandMode.name, R.string.deband_mode, R.drawable.ic_round_gradient_24,
                listOf(PrefName.VideoDebandMode),
                labels = { listOf(it.getString(R.string.deband_off), it.getString(R.string.deband_cpu), it.getString(R.string.deband_gpu)) },
                current = { DEBAND_KEYS.indexOf(PrefManager.getVal<String>(PrefName.VideoDebandMode)).coerceAtLeast(0) },
            ) { _, i ->
                PrefManager.setVal(PrefName.VideoDebandMode, DEBAND_KEYS[i])
                ActiveVideoPipeline.refreshFromPrefs()
            },
            // CuNNy shader upscaler (mpv); off by default. Applied to the playing video at once.
            pickRow(
                "upscaler", R.string.upscaler, R.drawable.ic_round_high_quality_24, advanced = true,
                keys = listOf(PrefName.UpscalerProviderId, PrefName.UpscalerProfileId),
                labels = { c ->
                    UpscalerChoices.choices(upscaler(c)).map { choice ->
                        choice.profileName?.let { c.getString(R.string.upscaler_profile, it) }
                            ?: c.getString(R.string.upscaler_off)
                    }
                },
                current = { c ->
                    UpscalerChoices.selectedIndex(
                        UpscalerChoices.choices(upscaler(c)),
                        PrefManager.getVal(PrefName.UpscalerProviderId),
                        PrefManager.getVal(PrefName.UpscalerProfileId),
                    )
                },
                onOpen = { host ->
                    if (upscaler(host.activity) == null) snackString(host.activity.getString(R.string.upscaler_unavailable))
                },
            ) { host, i ->
                val choice = UpscalerChoices.choices(upscaler(host.activity))[i]
                PrefManager.setVal(PrefName.UpscalerProviderId, choice.providerId)
                PrefManager.setVal(PrefName.UpscalerProfileId, choice.profileId)
                ActiveVideoPipeline.refreshFromPrefs()
            },
            // Dynamic range compression: quieter loud scenes, louder dialogue.
            switchRow(PrefName.AudioNightMode, R.string.audio_night_mode, R.string.audio_night_mode_hint,
                R.drawable.ic_round_volume_up_24, onChange = applyToPlayer),
            // Presets for brightness, contrast, saturation, gamma, hue and sharpen (six prefs).
            pickRow(
                "color_profile", R.string.color_profile, R.drawable.ic_round_color_picker_24,
                keys = listOf(PrefName.VideoBrightness, PrefName.VideoContrast, PrefName.VideoSaturation,
                    PrefName.VideoGamma, PrefName.VideoHue, PrefName.VideoSharpen),
                labels = { c -> PROFILES.map { c.getString(it.second) } },
                // No pref stores the profile; match the six values. -1 shows "Custom".
                current = { PROFILES.indexOfFirst { (theme, _) -> isApplied(theme) } },
            ) { _, i ->
                val theme = PROFILES[i].first
                PrefManager.setVal(PrefName.VideoBrightness, theme.brightness)
                PrefManager.setVal(PrefName.VideoContrast, theme.contrast)
                PrefManager.setVal(PrefName.VideoSaturation, theme.saturation)
                PrefManager.setVal(PrefName.VideoGamma, theme.gamma)
                PrefManager.setVal(PrefName.VideoHue, theme.hue)
                PrefManager.setVal(PrefName.VideoSharpen, theme.sharpen)
                ActiveVideoPipeline.refreshFromPrefs()
            },
            // Audio and subtitle offsets in ms, for streams that are out of sync.
            delayRow(PrefName.AudioDelayMs, R.string.audio_delay),
            delayRow(PrefName.SubtitleDelayMs, R.string.subtitle_delay),
            // Stretches subtitle timing, for subs made for a different frame rate.
            pickRow(
                PrefName.SubtitleSpeed.name, R.string.subtitle_speed, R.drawable.ic_round_audiotrack_24,
                listOf(PrefName.SubtitleSpeed), advanced = true,
                labels = { c -> SUB_SPEEDS.map { c.getString(R.string.speed_multiplier, it.toString()) } },
                current = { SUB_SPEEDS.indexOf(PrefManager.getVal<Float>(PrefName.SubtitleSpeed)) },
            ) { _, i ->
                PrefManager.setVal(PrefName.SubtitleSpeed, SUB_SPEEDS[i])
                ActiveVideoPipeline.refreshFromPrefs()
            },
            // How far the volume gesture may go above 100%.
            pickRow(
                PrefName.VolumeBoostCap.name, R.string.volume_boost_cap, R.drawable.ic_round_volume_up_24,
                listOf(PrefName.VolumeBoostCap), advanced = true,
                labels = { c -> BOOST_CAPS.map { c.getString(R.string.plus_percent, it) } },
                current = { BOOST_CAPS.indexOf(PrefManager.getVal<Int>(PrefName.VolumeBoostCap)) },
            ) { _, i ->
                PrefManager.setVal(PrefName.VolumeBoostCap, BOOST_CAPS[i])
                ActiveVideoPipeline.refreshFromPrefs()
            },
        )),
        Section(R.string.online_subtitles, listOf(
            // Fetches subtitles from online providers when the source has none or few.
            switchRow(PrefName.OnlineSubtitlesEnabled, R.string.online_subtitles, icon = R.drawable.ic_download_24,
                refresh = true),
            multiRow(PrefName.OnlineSubtitleProviders, R.string.online_subtitle_providers,
                R.drawable.ic_round_view_list_24, PROVIDERS, PROVIDER_LABELS),
            multiRow(PrefName.OnlineSubtitleLanguages, R.string.online_subtitle_languages,
                R.drawable.ic_round_translate_24, OnlineSubtitleLanguages.CODES,
                OnlineSubtitleLanguages.CODES.map(OnlineSubtitleLanguages::englishName),
                read = OnlineSubtitleLanguages::codes),
        )),
        Section(R.string.subtitles, listOf(
            // Live sample of the colour, font and size settings below.
            subtitlePreview,
            // Whose styling soft subs use: the source's (ASS), Editotsu's, by source type, or custom per origin.
            pickRow(
                PrefName.SubtitleStylingMode.name, R.string.subtitle_styling, R.drawable.ic_round_subtitles_24,
                listOf(PrefName.SubtitleStylingMode),
                labels = { c -> SubtitleStylingMode.entries.map { c.getString(stylingLabel(it)) } },
                current = { SubtitleStylingMode.entries.indexOf(SubtitleStylingStore.currentMode()) },
            ) { _, i -> SubtitleStylingStore.setMode(SubtitleStylingMode.entries[i]) },
            switchRow(PrefName.CustomStreamSourceStyling, R.string.custom_stream_source_styling,
                icon = R.drawable.ic_round_subtitles_24,
                visible = { SubtitleStylingStore.currentMode() == SubtitleStylingMode.CUSTOM }),
            switchRow(PrefName.CustomLocalSourceStyling, R.string.custom_local_source_styling,
                icon = R.drawable.ic_round_subtitles_24,
                visible = { SubtitleStylingStore.currentMode() == SubtitleStylingMode.CUSTOM }),
            // Colours, outline, font, size and language for subtitles drawn by Editotsu.
            colorRow(PrefName.PrimaryColor, R.string.primary_sub_color_select),
            colorRow(PrefName.SecondaryColor, R.string.secondary_sub_color_select),
            choiceRow(PrefName.Font, R.string.sub_font_select, R.drawable.ic_round_format_text_24,
                onChange = refreshPreview, labels = { FONTS }),
            stepperRow(PrefName.FontSize, R.string.subtitle_font_size, icon = R.drawable.ic_round_format_text_24,
                from = 8f, to = 72f, step = 1f, format = { _, v -> v.toInt().toString() },
                onChange = refreshPreview),
            choiceRow(PrefName.SubLanguage, R.string.subtitle_langauge, R.drawable.ic_round_subtitles_24,
                labels = { SUB_LANGUAGES }),
            // Box behind each line.
            colorRow(PrefName.SubBackground, R.string.sub_background_color_select),
            // Scales subtitles that keep the source's styling.
            sliderRow(PrefName.SourceSubScale, R.string.source_sub_size, icon = R.drawable.ic_round_color_24,
                from = 0.5f, to = 2f, step = 0.05f, format = ::percent),
            // Outline width and distance from the bottom edge.
            sliderRow(PrefName.SubStroke, R.string.textview_sub_stroke, icon = R.drawable.ic_round_color_24,
                from = 0f, to = 30f, step = 1f, format = { _, v -> v.toInt().toString() }, advanced = true),
            sliderRow(PrefName.SubBottomMargin, R.string.textview_sub_bottom_margin, icon = R.drawable.ic_round_color_24,
                from = 0f, to = 50f, step = 1f, format = { _, v -> v.toInt().toString() }, advanced = true),
        )),
        Section(R.string.timestamps, listOf(
            // Loads opening/ending/recap segments (AniSkip) for skip buttons and auto skip.
            switchRow(PrefName.TimeStampsEnabled, R.string.always_load_time_stamps, icon = R.drawable.ic_round_art_track_24,
                refresh = true),
            switchRow(PrefName.ShowTimeStampButton, R.string.show_skip_time_stamp_button,
                icon = R.drawable.ic_round_fast_forward_24, refresh = true),
            switchRow(PrefName.AutoHideTimeStamps, R.string.auto_hide_time_stamps, R.string.hide_skip_button,
                R.drawable.ic_round_art_track_24, enabled = { PrefManager.getVal(PrefName.ShowTimeStampButton) }),
            switchRow(PrefName.AutoSkipOPED, R.string.auto_skip_op_ed, R.string.requires_time_stamps_to_be_enabled,
                R.drawable.ic_round_play_disabled_24, enabled = timestampsOn),
            switchRow(PrefName.AutoSkipRecap, R.string.auto_skip_recap, R.string.requires_time_stamps_to_be_enabled,
                R.drawable.ic_round_play_disabled_24, enabled = timestampsOn),
            switchRow(PrefName.AutoPlay, R.string.auto_play_next_episode, R.string.auto_play_next_episode_info,
                R.drawable.ic_round_skip_next_24),
            switchRow(PrefName.AutoSkipFiller, R.string.auto_skip_fillers, R.string.auto_skip_fillers_info,
                R.drawable.ic_round_play_disabled_24),
            // Routes segment requests through a proxy, for networks where the handshake fails.
            switchRow(PrefName.UseProxyForTimeStamps, R.string.timestamp_proxy, R.string.timestamp_proxy_desc,
                R.drawable.ic_round_dns_24, advanced = true),
        )),
        Section(R.string.behaviour, listOf(
            // Watched share after which tracker progress updates and the next episode preloads.
            sliderRow(PrefName.WatchPercentage, R.string.watch_complete_percentage, R.string.watch_complete_percentage_info,
                R.drawable.ic_round_art_track_24, from = 0f, to = 1f, step = 0.01f, format = ::percent),
            switchRow(PrefName.FocusPause, R.string.pause_video_focus, icon = R.drawable.ic_round_pause_24),
            // Swipe gestures, double tap to seek, and the seek / skip button lengths.
            switchRow(PrefName.Gestures, R.string.gestures, icon = R.drawable.ic_round_swipe_vertical_24),
            switchRow(PrefName.FastForward, R.string.fast_forward, icon = R.drawable.ic_round_fast_forward_24),
            switchRow(PrefName.DoubleTap, R.string.double_tap, R.string.double_tap_info, R.drawable.ic_round_touch_app_24),
            sliderRow(PrefName.SeekTime, R.string.seek_time, R.string.seek_time_info, R.drawable.ic_round_fast_forward_24,
                from = 1f, to = 50f, step = 1f, format = { c, v -> c.getString(R.string.seconds_value, v.toInt()) }),
            stepperRow(PrefName.SkipTime, R.string.skip_time, R.string.skip_time_info, R.drawable.ic_round_fast_forward_24,
                from = 0f, to = 300f, step = 5f, format = { c, v -> c.getString(R.string.seconds_value, v.toInt()) }),
        )),
        Section(R.string.other, listOf(
            // Player buttons and playback paths: picture in picture, cast, rotate, internal cast.
            switchRow(PrefName.Pip, R.string.picture_in_picture, icon = R.drawable.ic_round_picture_in_picture_alt_24),
            switchRow(PrefName.Cast, R.string.show_cast_button, icon = R.drawable.ic_round_cast_24,
                visible = { FlavorFeatures.googleServices }),
            switchRow(PrefName.RotationPlayer, R.string.show_rotate_button, icon = R.drawable.ic_round_screen_rotation_alt_24),
            switchRow(PrefName.UseInternalCast, R.string.try_internal_cast_experimental, icon = R.drawable.cast_warning,
                advanced = true, visible = { FlavorFeatures.googleServices }),
        )),
        Section(R.string.anime, listOf(
            // Anime pages: episode list layout, dub preference, downloads.
            choiceRow(PrefName.AnimeDefaultView, R.string.default_ep_view, R.drawable.view_list_24, labels = {
                listOf(it.getString(R.string.list), it.getString(R.string.grid), it.getString(R.string.compact))
            }),
            switchRow(PrefName.SettingsPreferDub, R.string.prefer_dub, R.string.prefer_dub_desc,
                R.drawable.ic_round_audiotrack_24),
            switchRow(PrefName.ShowYtButton, R.string.show_yt, R.string.show_yt_desc, R.drawable.ic_round_play_circle_24),
            switchRow(PrefName.IncludeAnimeList, R.string.include_list, R.string.include_list_anime_desc,
                R.drawable.view_list_24, onChange = { host, _ -> host.activity.restartApp() }),
            actionRow(
                "preferred_download_resolutions", R.string.preferred_download_resolutions,
                icon = R.drawable.ic_round_high_quality_24,
                current = { PrefManager.getPreferredDownloadResolutions(null).joinToString(", ") },
            ) { host, _ -> ResolutionPriorityDialog.show(host.activity, null) { host.refresh() } },
            switchRow(PrefName.AutoSelectResolutionPriority, R.string.auto_select_resolution_priority,
                R.string.auto_select_resolution_priority_desc, R.drawable.ic_round_high_quality_24),
            switchRow(PrefName.SmartDownloadAnime, R.string.smart_download_anime, R.string.smart_download_anime_desc,
                R.drawable.ic_download_24),
        )),
        Section(R.string.anime_themes_section, listOf(
            // The Themes tab on anime pages (AnimeThemes openings and endings): which video file
            // plays when there are several. Its own section, not part of the anime rows above.
            themeRow(R.string.theme_pref_resolution, R.drawable.ic_round_high_quality_24,
                PrefName.AnimeThemesPreferredResolution) { c ->
                listOf(c.getString(R.string.theme_opt_auto), "1080p", "720p", c.getString(R.string.theme_opt_480p_or_lower))
            },
            themeRow(R.string.theme_pref_source, R.drawable.ic_round_movie_filter_24,
                PrefName.AnimeThemesPreferredSource) { c ->
                listOf(c.getString(R.string.theme_opt_auto), c.getString(R.string.theme_opt_bd), c.getString(R.string.theme_opt_web))
            },
            themeRow(R.string.theme_pref_credits, R.drawable.ic_round_video_settings_24,
                PrefName.AnimeThemesPreferredCredits) { c ->
                listOf(c.getString(R.string.theme_opt_auto), c.getString(R.string.theme_opt_creditless),
                    c.getString(R.string.theme_opt_with_credits))
            },
            themeRow(R.string.theme_pref_subtitles, R.drawable.ic_round_subtitles_24,
                PrefName.AnimeThemesPreferredSubtitles) { c ->
                listOf(c.getString(R.string.theme_opt_auto), c.getString(R.string.theme_opt_no_subtitles),
                    c.getString(R.string.theme_opt_subtitled))
            },
        )),
    )

    private fun upscaler(c: Context) = UpscalerBundleManager.getInstance(c).providerFor(CunnyUpscalerProvider.ID)

    private fun isApplied(theme: VideoFilterTheme) =
        PrefManager.getVal<Int>(PrefName.VideoBrightness) == theme.brightness &&
            PrefManager.getVal<Int>(PrefName.VideoContrast) == theme.contrast &&
            PrefManager.getVal<Int>(PrefName.VideoSaturation) == theme.saturation &&
            PrefManager.getVal<Int>(PrefName.VideoGamma) == theme.gamma &&
            PrefManager.getVal<Int>(PrefName.VideoHue) == theme.hue &&
            PrefManager.getVal<Float>(PrefName.VideoSharpen) == theme.sharpen

    private fun stylingLabel(mode: SubtitleStylingMode) = when (mode) {
        SubtitleStylingMode.AUTO_BY_SOURCE -> R.string.subtitle_styling_automatic
        SubtitleStylingMode.EDITOTSU_ALWAYS -> R.string.subtitle_styling_editotsu
        SubtitleStylingMode.SOURCE_ALWAYS -> R.string.subtitle_styling_source
        SubtitleStylingMode.CUSTOM -> R.string.subtitle_styling_custom
    }
}
