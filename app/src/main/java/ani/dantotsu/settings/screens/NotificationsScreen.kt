package ani.dantotsu.settings.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.media.Media
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.openSettings
import ani.dantotsu.settings.SettingsNotificationActivity
import ani.dantotsu.settings.SubscriptionsBottomDialog
import ani.dantotsu.settings.rows.ChoiceOption
import ani.dantotsu.settings.rows.ChoiceSheet
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.choiceRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Settings > Notifications: subscriptions, AniList & comments, reliability. */
object NotificationsScreen : SettingsScreen(
    "notifications",
    R.string.notifications,
    R.drawable.ic_round_notifications_none_24,
    R.string.settings_notifications_desc,
) {

    override val activity get() = SettingsNotificationActivity::class.java

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
        Section(R.string.subscriptions, listOf(
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
            switchRow(PrefName.SubscriptionCheckingNotifications, R.string.notification_for_checking_subscriptions,
                R.string.notification_for_checking_subscriptions_desc, R.drawable.ic_round_smart_button_24,
                visible = loggedIn).withLongClick { openSettings(it.activity, null) },
            switchRow(PrefName.SubscriptionPromptAtEnd, R.string.subscription_prompt_at_end,
                R.string.subscription_prompt_at_end_desc, R.drawable.ic_round_smart_button_24, visible = loggedIn),
        )),
        Section(R.string.settings_section_anilist_comments, listOf(
            actionRow(PrefName.AnilistFilteredTypes.name, R.string.anilist_notification_filters,
                R.string.anilist_notification_filters_desc, R.drawable.ic_anilist, visible = loggedIn,
                keys = listOf(PrefName.AnilistFilteredTypes), onClick = { host, _ -> editFilters(host) }),
            Row(
                id = PrefName.ShowNotificationRedDot.name, title = R.string.hide_notification_dot,
                icon = R.drawable.ic_round_notifications_active_24, keys = listOf(PrefName.ShowNotificationRedDot),
                kind = Kind.Switch(
                    get = { !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) },
                    set = { _, hide -> PrefManager.setVal(PrefName.ShowNotificationRedDot, !hide) },
                ),
            ),
            choiceRow(PrefName.AnilistNotificationInterval, R.string.anilist_notifications_interval,
                R.drawable.ic_round_notifications_none_24, advanced = true, visible = loggedIn,
                labels = { intervalNames(it, AnilistNotificationWorker.checkIntervals.map { m -> m.toInt() }) },
                onChange = { host, _ -> reschedule(host) }),
            choiceRow(PrefName.CommentNotificationInterval, R.string.comment_notifications_interval,
                R.drawable.ic_round_notifications_none_24, advanced = true, visible = loggedIn,
                labels = { intervalNames(it, CommentNotificationWorker.checkIntervals.map { m -> m.toInt() }) },
                onChange = { host, _ -> reschedule(host) }),
        )),
        Section(R.string.settings_section_reliability, listOf(
            actionRow("notification_permission", R.string.notification_permission,
                icon = R.drawable.ic_round_notifications_none_24, opensScreen = true, visible = loggedIn,
                current = { c ->
                    c.getString(if (ani.dantotsu.hasNotificationPermission(c)) R.string.notification_permission_on
                    else R.string.notification_permission_off)
                },
                // The system page grants the permission too, including after a denial.
                onClick = { host, _ -> openSettings(host.activity, null) }),
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
