package ani.dantotsu.settings.screens

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comments.CommentsOptIn
import ani.dantotsu.connections.discord.rpc.DiscordRpcPublisher
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.databinding.ItemSettingAccountsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.AnilistSettingsActivity
import ani.dantotsu.settings.SettingsAccountActivity
import ani.dantotsu.settings.rows.Kind
import ani.dantotsu.settings.rows.Row
import ani.dantotsu.settings.rows.Section
import ani.dantotsu.settings.rows.SettingsCards
import ani.dantotsu.settings.rows.SettingsHost
import ani.dantotsu.settings.rows.SettingsScreen
import ani.dantotsu.settings.rows.actionRow
import ani.dantotsu.settings.rows.customRow
import ani.dantotsu.settings.rows.switchRow
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.launch

/** Settings > Accounts & tracking: accounts, services, progress updates. */
object TrackingScreen : SettingsScreen("tracking", R.string.settings_tracking, R.drawable.ic_round_person_24, R.string.settings_tracking_desc) {

    override val activity get() = SettingsAccountActivity::class.java

    /** Everything below the account cards needs an AniList login, except Discord presence. */
    private val loggedIn = { Anilist.token != null }

    override fun sections() = listOf(
        Section(R.string.settings_section_accounts, listOf(
            customRow("accounts", R.string.accounts_cards, R.layout.item_setting_accounts,
                keywords = listOf(R.string.accounts_keywords)) { host, view ->
                bindAccounts(host, ItemSettingAccountsBinding.bind(view))
            },
            switchRow(PrefName.MalFollowsAniList, R.string.mal_follows_anilist, R.string.mal_follows_anilist_desc,
                R.drawable.ic_myanimelist, visible = { loggedIn() && MAL.isLoggedIn() }),
            actionRow("list_sync", R.string.list_sync, R.string.list_sync_desc, R.drawable.ic_round_sync_24,
                visible = { loggedIn() && MAL.isLoggedIn() },
                onClick = { host, _ ->
                    ani.dantotsu.settings.ListSyncSheet().show(host.activity.supportFragmentManager, "list_sync")
                }),
            switchRow(PrefName.DiscordRichPresenceEnabled, R.string.discord_rich_presence,
                // Presence needs no AniList account, so the switch shows signed out too.
                R.string.discord_rich_presence_desc, R.drawable.interests_24,
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

    private fun bindAccounts(host: SettingsHost, b: ItemSettingAccountsBinding) {
        val activity = host.activity
        val restart = { ani.dantotsu.MainActivity.accountChanged() }
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
            b.settingsAnilistLogin.visibility = View.VISIBLE
            b.settingsAnilistLogout.visibility = View.GONE
            b.settingsMALLogout.visibility = View.GONE
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

        // Signed in: the account card shows who you are; logging out is an icon, after a confirm.
        b.settingsAnilistLogin.visibility = View.GONE
        b.settingsAnilistLogout.visibility = View.VISIBLE
        b.settingsAnilistLogout.setOnClickListener {
            confirmLogout(activity) {
                Anilist.removeSavedToken()
                restart()
                host.refresh()
            }
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
        b.settingsMALLogout.visibility = View.GONE
        b.settingsMALUsername.visibility = View.VISIBLE
        b.settingsMALLogin.isEnabled = true
        when {
            MAL.isLoggedIn() -> {
                b.settingsMALLogin.visibility = View.GONE
                b.settingsMALLogout.visibility = View.VISIBLE
                b.settingsMALLogout.setOnClickListener {
                    confirmLogout(activity) {
                        MAL.removeSavedToken()
                        restart()
                        host.refresh()
                    }
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
                b.settingsMALLogout.visibility = View.GONE
                b.settingsMALLogin.setText(R.string.mal_login_unavailable)
                b.settingsMALLogin.isEnabled = false
                b.settingsMALLogin.setOnClickListener(null)
            }
            else -> {
                b.settingsMALAvatar.setImageResource(R.drawable.ic_round_person_24)
                b.settingsMALAvatar.setOnClickListener { MAL.loginIntent(activity) }
                b.settingsMALUsername.visibility = View.GONE
                b.settingsMALLogout.visibility = View.GONE
                b.settingsMALLogin.setText(R.string.account_login)
                b.settingsMALLogin.setOnClickListener { MAL.loginIntent(activity) }
            }
        }
    }

    private fun confirmLogout(activity: android.app.Activity, logout: () -> Unit) {
        activity.customAlertDialog().apply {
            setTitle(R.string.logout)
            setMessage(R.string.logout_confirm)
            setPosButton(R.string.logout) { logout() }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
