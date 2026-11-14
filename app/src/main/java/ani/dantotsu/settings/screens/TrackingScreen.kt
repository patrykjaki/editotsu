package ani.dantotsu.settings.screens

import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.connections.comments.CommentsOptIn
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MalListSync
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.connections.discord.rpc.DiscordRpcPublisher
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.databinding.ItemSettingAccountsBinding
import ani.dantotsu.settings.rows.SettingsCards
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.openSettings
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.AnilistSettingsActivity
import ani.dantotsu.settings.SettingsAccountActivity
import ani.dantotsu.settings.SubscriptionsBottomDialog
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Settings > Tracking: accounts, progress updates, notifications. */
object TrackingScreen : SettingsScreen("tracking", R.string.settings_tracking, R.drawable.ic_round_person_24, R.string.settings_tracking_desc) {

    override val activity get() = SettingsAccountActivity::class.java

    /** Everything below the account cards needs an AniList login, as before. */
    private val loggedIn = { Anilist.token != null }

    private var isImportingLists = false
    private var isImportingStatuses = false

    private fun intervalNames(c: Context, minutes: List<Int>) = minutes.map {
        val mins = it % 60
        val hours = it / 60
        if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}".trim()
        else c.getString(R.string.do_not_update)
    }

    private fun reschedule(host: SettingsHost) =
        TaskScheduler.create(host.activity, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(host.activity)

    override fun sections() = listOf(
        Section(R.string.settings_section_accounts, listOf(
            customRow("accounts", R.string.accounts_cards, R.layout.item_setting_accounts,
                keywords = listOf(R.string.accounts_keywords)) { host, view ->
                bindAccounts(host, ItemSettingAccountsBinding.bind(view))
            },
            switchRow(PrefName.DiscordRichPresenceEnabled, R.string.discord_rich_presence,
                R.string.discord_rich_presence_desc, R.drawable.interests_24, visible = loggedIn,
                // Off: clear the presence and unbind now, no restart needed.
                onChange = { _, on -> if (!on) DiscordRpcPublisher.teardown() }),
            actionRow("anilist_settings", R.string.anilist_settings, R.string.alsettings_desc, R.drawable.ic_anilist,
                opensScreen = true, visible = loggedIn,
                // AniList's own account settings live on that screen; search finds them through this row.
                keywords = listOf(R.string.airing_notifications, R.string.display_adult_content,
                    R.string.edit_bio, R.string.restrict_messages),
                onClick = { host, _ ->
                    host.activity.lifecycleScope.launch {
                        Anilist.query.getUserData()
                        host.activity.startActivity(Intent(host.activity, AnilistSettingsActivity::class.java))
                    }
                }),
            Row(
                id = PrefName.CommentsEnabled.name, title = R.string.comments_button,
                summary = R.string.comments_button_desc, icon = R.drawable.ic_round_comment_24,
                visible = loggedIn, keys = listOf(PrefName.CommentsEnabled),
                kind = Kind.Switch(
                    get = { CommentsOptIn.isEnabled() },
                    set = { host, on ->
                        CommentsOptIn.setEnabled(on)
                        snackString(host.activity.getString(R.string.restart_app_extra))
                    },
                ),
            ),
        )),
        Section(R.string.settings_section_progress,
            progressRows(PrefName.AskIndividualPlayer, R.string.ask_update_progress_anime,
                R.string.ask_update_progress_info_ep, PrefName.ChapterZeroPlayer,
                PrefName.UpdateForHPlayer, R.string.ask_update_progress_hentai) +
            progressRows(PrefName.AskIndividualReader, R.string.ask_update_progress_manga,
                R.string.ask_update_progress_info_chap, PrefName.ChapterZeroReader,
                PrefName.UpdateForHReader, R.string.ask_update_progress_doujin)
        ),
        Section(R.string.settings_section_notifications, listOf(
            actionRow("notification_permission", R.string.notification_permission,
                icon = R.drawable.ic_round_notifications_none_24, opensScreen = true, visible = loggedIn,
                current = { c ->
                    c.getString(if (ani.dantotsu.hasNotificationPermission(c)) R.string.notification_permission_on
                    else R.string.notification_permission_off)
                },
                // The system page grants the permission too, including after a denial.
                onClick = { host, _ -> openSettings(host.activity, null) }),
            choiceRow(PrefName.SubscriptionNotificationInterval, R.string.subscriptions_checking_time,
                R.drawable.ic_round_notifications_none_24, visible = loggedIn,
                labels = { intervalNames(it, SubscriptionNotificationWorker.checkIntervals.map { m -> m.toInt() }) },
                onChange = { host, _ -> reschedule(host) }).withLongClick { reschedule(it) },
            actionRow("view_subscriptions", R.string.view_subscriptions, R.string.view_subscriptions_desc,
                R.drawable.ic_round_search_24, visible = loggedIn,
                onClick = { host, _ ->
                    SubscriptionsBottomDialog.newInstance(SubscriptionHelper.getSubscriptions())
                        .show(host.activity.supportFragmentManager, "subscriptions")
                }),
            actionRow("import_lists", R.string.import_anilist_lists_to_subscriptions,
                R.string.import_anilist_lists_to_subscriptions_desc, R.drawable.ic_round_playlist_add_24,
                visible = loggedIn, onClick = { host, _ -> importLists(host) }),
            actionRow("import_statuses", R.string.import_anilist_statuses_to_subscriptions,
                R.string.import_anilist_statuses_to_subscriptions_desc, R.drawable.ic_round_playlist_add_24,
                visible = loggedIn, onClick = { host, _ -> importStatuses(host) }),
            actionRow(PrefName.AnilistFilteredTypes.name, R.string.anilist_notification_filters,
                R.string.anilist_notification_filters_desc, R.drawable.ic_anilist, visible = loggedIn,
                keys = listOf(PrefName.AnilistFilteredTypes), onClick = { host, _ -> editFilters(host) }),
            switchRow(PrefName.SubscriptionCheckingNotifications, R.string.notification_for_checking_subscriptions,
                R.string.notification_for_checking_subscriptions_desc, R.drawable.ic_round_smart_button_24,
                visible = loggedIn).withLongClick { openSettings(it.activity, null) },
            switchRow(PrefName.SubscriptionPromptAtEnd, R.string.subscription_prompt_at_end,
                R.string.subscription_prompt_at_end_desc, R.drawable.ic_round_smart_button_24, visible = loggedIn),
            choiceRow(PrefName.AnilistNotificationInterval, R.string.anilist_notifications_interval,
                R.drawable.ic_round_notifications_none_24, advanced = true, visible = loggedIn,
                labels = { intervalNames(it, AnilistNotificationWorker.checkIntervals.map { m -> m.toInt() }) },
                onChange = { host, _ -> reschedule(host) }),
            choiceRow(PrefName.CommentNotificationInterval, R.string.comment_notifications_interval,
                R.drawable.ic_round_notifications_none_24, advanced = true, visible = loggedIn,
                labels = { intervalNames(it, CommentNotificationWorker.checkIntervals.map { m -> m.toInt() }) },
                onChange = { host, _ -> reschedule(host) }),
            Row(
                id = PrefName.UseAlarmManager.name, title = R.string.use_alarm_manager_reliable,
                summary = R.string.use_alarm_manager_reliable_desc, icon = R.drawable.ic_anilist,
                advanced = true, visible = loggedIn, keys = listOf(PrefName.UseAlarmManager),
                kind = Kind.Switch(get = { PrefManager.getVal(PrefName.UseAlarmManager) }, set = ::setAlarmManager),
            ),
        )),
    )

    private fun Row.withLongClick(action: (SettingsHost) -> Unit) = Row(
        id = id, title = title, summary = summary, icon = icon, kind = kind, advanced = advanced,
        visible = visible, enabled = enabled, keywords = keywords, keys = keys, extra = extra,
        onLongClick = action,
    )

    /** Ask, chapter zero (off-limits while asking) and the adult switch for one media type. */
    private fun progressRows(
        ask: PrefName, askName: Int, askDesc: Int, zero: PrefName, adult: PrefName, adultName: Int,
    ) = listOf(
        switchRow(ask, askName, askDesc, R.drawable.ic_round_sync_24, visible = loggedIn, refresh = true),
        switchRow(zero, R.string.ask_update_progress_chapter_zero, R.string.ask_update_progress_info_zero,
            R.drawable.ic_round_sync_24, visible = loggedIn, enabled = { !PrefManager.getVal<Boolean>(ask) }),
        switchRow(adult, adultName, icon = R.drawable.ic_round_nsfw_24, visible = loggedIn,
            onChange = { host, on -> if (on) snackString(host.activity.getString(R.string.very_bold)) }),
    )

    /** Turning it on asks first and may open the exact-alarm page; cancel puts the switch back. */
    private fun setAlarmManager(host: SettingsHost, on: Boolean) = with(host.activity) {
        if (on) {
            customAlertDialog().apply {
                setTitle(R.string.use_alarm_manager)
                setMessage(R.string.use_alarm_manager_confirm)
                setPosButton(R.string.use) {
                    PrefManager.setVal(PrefName.UseAlarmManager, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                    ) {
                        // Alarms need the permission; the permission change receiver switches over once granted.
                        startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                            android.net.Uri.parse("package:$packageName")))
                    } else {
                        TaskScheduler.switchTo(this@with, useAlarmManager = true)
                    }
                    host.refresh()
                }
                setNegButton(R.string.cancel) {
                    PrefManager.setVal(PrefName.UseAlarmManager, false)
                    host.refresh()
                }
                show()
            }
        } else {
            PrefManager.setVal(PrefName.UseAlarmManager, false)
            TaskScheduler.switchTo(this, useAlarmManager = false)
        }
    }

    private fun bindAccounts(host: SettingsHost, b: ItemSettingAccountsBinding) {
        val activity = host.activity
        val restart = { (activity as? SettingsAccountActivity)?.restartMainOnBack() }
        SettingsCards.applyToCard(b.settingsAnilistCard)
        SettingsCards.applyToCard(b.settingsMalCard)
        b.settingsMalLogo.alpha = 1f
        b.settingsMALAvatarContainer.alpha = 1f
        b.settingsAccountHelp.setOnClickListener {
            CustomBottomDialog.newInstance().apply {
                setTitleText(activity.getString(R.string.account_help))
                addView(TextView(it.context).apply {
                    Markwon.builder(it.context).usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                        .setMarkdown(this, activity.getString(R.string.full_account_help))
                })
            }.show(activity.supportFragmentManager, "dialog")
        }

        if (Anilist.token == null) {
            b.settingsAnilistAvatar.setImageResource(R.drawable.ic_round_person_24)
            b.settingsAnilistAvatar.setOnClickListener(null)
            b.settingsAnilistUsername.visibility = View.GONE
            b.settingsAnilistTokenExpiry.visibility = View.GONE
            b.settingsAnilistBanner.visibility = View.GONE
            b.settingsAnilistScrim.visibility = View.GONE
            b.settingsAnilistLogin.setText(R.string.account_login)
            b.settingsAnilistLogin.setOnClickListener { Anilist.loginIntent(activity) }
            // MAL needs an AniList login first: same card, dimmed, nothing to tap but help.
            b.settingsMALLoginRequired.visibility = View.VISIBLE
            b.settingsMALLogin.visibility = View.GONE
            b.settingsMALUsername.visibility = View.GONE
            b.settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
            b.settingsMALAvatar.setOnClickListener(null)
            b.settingsMalLogo.alpha = 0.5f
            b.settingsMALAvatarContainer.alpha = 0.5f
            return
        }

        b.settingsAnilistLogin.setText(R.string.logout)
        b.settingsAnilistLogin.setOnClickListener {
            Anilist.removeSavedToken()
            restart()
            host.refresh()
        }
        b.settingsAnilistUsername.visibility = View.VISIBLE
        b.settingsAnilistUsername.text = Anilist.username
        b.settingsAnilistAvatar.loadImage(Anilist.avatar)
        b.settingsAnilistAvatar.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openLinkInBrowser(activity.getString(R.string.anilist_link,
                PrefManager.getVal<String>(PrefName.AnilistUserName)))
        }
        if (Anilist.bg != null) {
            b.settingsAnilistBanner.visibility = View.VISIBLE
            b.settingsAnilistScrim.visibility = View.VISIBLE
            b.settingsAnilistBanner.loadImage(Anilist.bg)
        } else {
            b.settingsAnilistBanner.visibility = View.GONE
            b.settingsAnilistScrim.visibility = View.GONE
        }
        val daysLeft = Anilist.getTokenExpiryDays()
        if (daysLeft != null) {
            b.settingsAnilistTokenExpiry.visibility = View.VISIBLE
            b.settingsAnilistTokenExpiry.text =
                if (daysLeft <= 0) activity.getString(R.string.anilist_reconnect_now)
                else activity.getString(R.string.anilist_reconnect_in, daysLeft)
            b.settingsAnilistTokenExpiry.setOnClickListener { Anilist.loginIntent(activity) }
        } else {
            b.settingsAnilistTokenExpiry.visibility = View.GONE
        }

        b.settingsMALLoginRequired.visibility = View.GONE
        b.settingsMALLogin.visibility = View.VISIBLE
        b.settingsMALUsername.visibility = View.VISIBLE
        b.settingsMALLogin.isEnabled = true
        b.settingsMALSync.visibility = if (MAL.isLoggedIn()) View.VISIBLE else View.GONE
        b.settingsMALSync.setOnClickListener { copyListsToMal(host, b.settingsMALSync) }
        when {
            MAL.isLoggedIn() -> {
                b.settingsMALLogin.setText(R.string.logout)
                b.settingsMALLogin.setOnClickListener {
                    MAL.removeSavedToken()
                    restart()
                    host.refresh()
                }
                b.settingsMALUsername.text = MAL.username
                if (!MAL.avatar.isNullOrBlank()) b.settingsMALAvatar.loadImage(MAL.avatar)
                else b.settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                b.settingsMALAvatar.setOnClickListener {
                    val uname = MAL.username
                    if (!uname.isNullOrBlank()) {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openLinkInBrowser(activity.getString(R.string.myanilist_link, uname))
                    }
                }
            }
            !MAL.isLoginAvailable() -> {
                // No-credential build: say it is unavailable, never offer a login that can only fail.
                b.settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                b.settingsMALAvatar.setOnClickListener(null)
                b.settingsMALUsername.visibility = View.GONE
                b.settingsMALLogin.setText(R.string.mal_login_unavailable)
                b.settingsMALLogin.isEnabled = false
                b.settingsMALLogin.setOnClickListener(null)
            }
            else -> {
                b.settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                b.settingsMALAvatar.setOnClickListener { MAL.loginIntent(activity) }
                b.settingsMALUsername.visibility = View.GONE
                b.settingsMALLogin.setText(R.string.account_login)
                b.settingsMALLogin.setOnClickListener { MAL.loginIntent(activity) }
            }
        }
    }

    /** Compares the AniList lists with MyAnimeList, asks, then writes what differs. */
    private fun copyListsToMal(host: SettingsHost, button: TextView) {
        val activity = host.activity
        val label = activity.getString(R.string.mal_sync)
        fun finish(message: String) {
            button.isEnabled = true
            button.text = label
            ani.dantotsu.snackString(message)
        }
        button.isEnabled = false
        button.setText(R.string.mal_sync_comparing)
        activity.lifecycleScope.launch {
            val (anime, manga) = withContext(Dispatchers.IO) {
                MalListSync.load(true) to MalListSync.load(false)
            }
            if (anime == null && manga == null) return@launch finish(activity.getString(R.string.mal_sync_read_failed))
            val total = (anime?.toWrite?.size ?: 0) + (manga?.toWrite?.size ?: 0)
            if (total == 0) return@launch finish(activity.getString(R.string.mal_sync_same))
            button.text = label
            activity.customAlertDialog().apply {
                setTitle(R.string.mal_sync_title)
                setMessage(R.string.mal_sync_confirm, anime?.toWrite?.size ?: 0, manga?.toWrite?.size ?: 0)
                setNegButton(R.string.cancel) { button.isEnabled = true }
                setPosButton(activity.getString(R.string.mal_sync_update, total)) {
                    activity.lifecycleScope.launch {
                        var done = 0
                        var failed = 0
                        for (side in listOfNotNull(anime, manga)) {
                            val before = done
                            failed += withContext(Dispatchers.IO) {
                                MalListSync.write(side.isAnime, side.toWrite) { n ->
                                    done = before + n
                                    button.post { button.text = activity.getString(R.string.mal_sync_progress, done, total) }
                                }
                            }
                        }
                        finish(
                            if (failed == 0) activity.getString(R.string.mal_sync_done, total)
                            else activity.getString(R.string.mal_sync_done_failed, total - failed, failed),
                        )
                    }
                }
                setCancelable(false)
                show()
            }
        }
    }

    private fun editFilters(host: SettingsHost) = with(host.activity) {
        val types = NotificationType.entries.map { it.name }
        val filteredTypes = PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes).toMutableSet()
        val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
        ChoiceSheet.multi(
            this, getString(R.string.anilist_notification_filters),
            types.map { name ->
                ChoiceOption(name.replace("_", " ").lowercase().replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                })
            },
            selected,
        ) { updatedSelected ->
            types.forEachIndexed { index, type ->
                if (updatedSelected[index]) filteredTypes.add(type) else filteredTypes.remove(type)
            }
            PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
        }
    }

    private fun importLists(host: SettingsHost): Unit = with(host.activity) {
        if (isImportingLists) {
            toast(getString(R.string.loading))
            return
        }
        isImportingLists = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull()
                if (userId == null) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.login_to_anilist_first))
                        isImportingLists = false
                    }
                    return@launch
                }
                val animeLists = Anilist.query.getMediaLists(true, userId)
                val mangaLists = Anilist.query.getMediaLists(false, userId)
                val selectableLists = linkedMapOf<String, ArrayList<Media>>()
                fun addSelectableLists(prefix: String, lists: MutableMap<String, ArrayList<Media>>) {
                    lists.forEach { (name, media) ->
                        if (name != "All" && name != "Favourites" && media.isNotEmpty()) {
                            selectableLists["$prefix • $name"] = media
                        }
                    }
                }
                addSelectableLists(getString(R.string.anime), animeLists)
                addSelectableLists(getString(R.string.manga), mangaLists)
                if (selectableLists.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.no_lists_available_to_import))
                        isImportingLists = false
                    }
                    return@launch
                }
                val titles = selectableLists.keys.toTypedArray()
                val selected = BooleanArray(titles.size) { false }
                withContext(Dispatchers.Main) {
                    ChoiceSheet.multi(
                        this@with, getString(R.string.select_lists_to_import), titles.map { ChoiceOption(it) }, selected,
                        // Back, a swipe or a tap outside closes it without importing.
                        onCancel = { isImportingLists = false },
                        confirm = ChoiceSheet.Confirm(R.string.import_action, R.drawable.ic_download_24) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                var importedCount = 0
                                titles.forEachIndexed { index, title ->
                                    if (selected[index]) {
                                        selectableLists[title]?.forEach { media ->
                                            if (existingIds.add(media.id)) {
                                                importedCount++
                                                SubscriptionHelper.saveSubscription(media, true)
                                            }
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.imported_subscriptions_count, importedCount))
                                    isImportingLists = false
                                }
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isImportingLists = false }
            }
        }
    }

    private fun importStatuses(host: SettingsHost): Unit = with(host.activity) {
        if (isImportingStatuses) {
            toast(getString(R.string.loading))
            return
        }
        isImportingStatuses = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull()
                if (userId == null) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.login_to_anilist_first))
                        isImportingStatuses = false
                    }
                    return@launch
                }
                val animeAll = Anilist.query.getMediaLists(true, userId)["All"] ?: arrayListOf()
                val mangaAll = Anilist.query.getMediaLists(false, userId)["All"] ?: arrayListOf()
                if (animeAll.isEmpty() && mangaAll.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.no_lists_available_to_import))
                        isImportingStatuses = false
                    }
                    return@launch
                }
                val statusKeys = resources.getStringArray(R.array.status)
                val animeStatuses = resources.getStringArray(R.array.status_anime)
                val mangaStatuses = resources.getStringArray(R.array.status_manga)
                val count = minOf(statusKeys.size, animeStatuses.size, mangaStatuses.size)
                val titles = ArrayList<String>(count * 2)
                val statusMeta = ArrayList<Pair<Boolean, String>>(count * 2)
                repeat(count) { index ->
                    titles.add("${getString(R.string.anime)} • ${animeStatuses[index]}")
                    statusMeta.add(true to statusKeys[index])
                    titles.add("${getString(R.string.manga)} • ${mangaStatuses[index]}")
                    statusMeta.add(false to statusKeys[index])
                }
                val selected = BooleanArray(titles.size) { false }
                withContext(Dispatchers.Main) {
                    ChoiceSheet.multi(
                        this@with, getString(R.string.select_statuses_to_import), titles.map { ChoiceOption(it) }, selected,
                        onCancel = { isImportingStatuses = false },
                        confirm = ChoiceSheet.Confirm(R.string.import_action, R.drawable.ic_download_24) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val animeSelected = mutableSetOf<String>()
                                val mangaSelected = mutableSetOf<String>()
                                statusMeta.forEachIndexed { index, (isAnime, status) ->
                                    if (selected[index]) {
                                        if (isAnime) animeSelected.add(status) else mangaSelected.add(status)
                                    }
                                }
                                val existingIds = SubscriptionHelper.getSubscriptions().keys.toMutableSet()
                                var importedCount = 0
                                fun importMedia(list: List<Media>, statuses: Set<String>) {
                                    list.forEach { media ->
                                        if (media.userStatus != null && statuses.contains(media.userStatus) &&
                                            existingIds.add(media.id)
                                        ) {
                                            importedCount++
                                            SubscriptionHelper.saveSubscription(media, true)
                                        }
                                    }
                                }
                                importMedia(animeAll, animeSelected)
                                importMedia(mangaAll, mangaSelected)
                                withContext(Dispatchers.Main) {
                                    toast(getString(R.string.imported_subscriptions_count, importedCount))
                                    isImportingStatuses = false
                                }
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isImportingStatuses = false }
            }
        }
    }
}
