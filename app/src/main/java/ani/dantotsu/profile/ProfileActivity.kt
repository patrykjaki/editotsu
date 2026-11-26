package ani.dantotsu.profile

import android.view.ViewGroup
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityProfileBinding
import ani.dantotsu.databinding.ItemProfileAppBarBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.user.ListActivity
import ani.dantotsu.openImage
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.profile.activity.ActivityFragment
import ani.dantotsu.profile.activity.ActivityFragment.Companion.ActivityType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.navBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.themes.followThemeStatusBarIcons
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs

class ProfileActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {
    lateinit var binding: ActivityProfileBinding
    private lateinit var bindingProfileAppBar: ItemProfileAppBarBinding
    private var selected: Int = 0
    lateinit var navBar: AnimatedBottomBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (savedInstanceState != null) {
            selected = savedInstanceState.getInt("selectedTab", 0)
        }
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        followThemeStatusBarIcons()
        val context = this
        navBar = binding.profileNavBar
        val feedTab = navBar.createTab(R.drawable.ic_round_filter_24, "Feed")
        val profileTab = navBar.createTab(R.drawable.ic_round_person_24, "Profile")
        val statsTab = navBar.createTab(R.drawable.ic_stats_24, "Stats")
        navBar.addTab(profileTab)
        navBar.addTab(feedTab)
        navBar.addTab(statsTab)
        navBar.visibility = View.GONE
        binding.profileNavBarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val userid = intent.getIntExtra("userId", -1)
            val username = intent.getStringExtra("username") ?: ""
            val respond =
                if (userid != -1) Anilist.query.getUserProfile(userid) else
                    Anilist.query.getUserProfile(username)
            val user = respond?.data?.user
            if (user == null) {
                toast(R.string.user_not_found)
                finish()
                return@launch
            }

            withContext(Dispatchers.Main) {
                binding.profileViewPager.adapter =
                    ViewPagerAdapter(supportFragmentManager, lifecycle, user)
                binding.profileViewPager.setOffscreenPageLimit(2)
                binding.profileViewPager.setCurrentItem(selected, false)
                navBar.visibility = View.VISIBLE
                navBar.selectTabAt(selected)
                // swiping between pages moves the tab bar with it
                binding.profileViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        if (position == selected) return
                        selected = position
                        navBar.selectTabAt(position)
                    }
                })
                navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
                    override fun onTabSelected(
                        lastIndex: Int,
                        lastTab: AnimatedBottomBar.Tab?,
                        newIndex: Int,
                        newTab: AnimatedBottomBar.Tab
                    ) {
                        selected = newIndex
                        binding.profileViewPager.setCurrentItem(selected, true)
                    }
                })

                bindingProfileAppBar = ItemProfileAppBarBinding.bind(binding.root).apply {
                    binding.profileProgressBar.visibility = View.GONE
                    val showFollowControls = user.id != Anilist.userid && Anilist.userid != null
                    followButton.isGone = !showFollowControls
                    profileFollowsYouBadge.isVisible = showFollowControls && user.isFollower

                    fun renderFollowButton() {
                        val following = user.isFollowing
                        followButton.text = getString(
                            when {
                                following && user.isFollower -> R.string.mutual
                                following -> R.string.following
                                user.isFollower -> R.string.profile_follow_back
                                else -> R.string.follow
                            }
                        )
                        followButton.setIconResource(
                            if (following) R.drawable.ic_check else R.drawable.ic_add
                        )
                        val bgAttr = if (following) {
                            com.google.android.material.R.attr.colorSecondaryContainer
                        } else {
                            androidx.appcompat.R.attr.colorPrimary
                        }
                        val fgAttr = if (following) {
                            com.google.android.material.R.attr.colorOnSecondaryContainer
                        } else {
                            com.google.android.material.R.attr.colorOnPrimary
                        }
                        val bgColor = MaterialColors.getColor(followButton, bgAttr)
                        val fgColor = MaterialColors.getColor(followButton, fgAttr)
                        followButton.backgroundTintList = ColorStateList.valueOf(bgColor)
                        followButton.setTextColor(fgColor)
                        followButton.iconTint = ColorStateList.valueOf(fgColor)
                    }

                    if (showFollowControls) {
                        renderFollowButton()
                        followButton.setOnClickListener {
                            followButton.isEnabled = false
                            lifecycleScope.launch(Dispatchers.IO) {
                                val res = Anilist.mutation.toggleFollow(user.id)
                                withContext(Dispatchers.Main) {
                                    followButton.isEnabled = true
                                    if (res?.data?.toggleFollow != null) {
                                        snackString(R.string.success)
                                        user.isFollowing = res.data.toggleFollow.isFollowing
                                        renderFollowButton()
                                    }
                                }
                            }
                        }
                    }

                    profileAppBar.visibility = View.VISIBLE
                    profileMenuButton.setOnClickListener {
                        val popup = PopupMenu(context, profileMenuButton)
                        popup.menuInflater.inflate(R.menu.menu_profile, popup.menu)
                        popup.setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                R.id.action_view_on_anilist -> {
                                    openLinkInBrowser(getString(R.string.anilist_link, user.name))
                                    true
                                }

                                R.id.action_share_profile -> {
                                    val shareIntent = Intent(Intent.ACTION_SEND)
                                    shareIntent.type = "text/plain"
                                    shareIntent.putExtra(
                                        Intent.EXTRA_TEXT,
                                        getString(R.string.anilist_link, user.name)
                                    )
                                    startActivity(
                                        Intent.createChooser(
                                            shareIntent,
                                            getString(R.string.share_profile)
                                        )
                                    )
                                    true
                                }

                                R.id.action_copy_user_id -> {
                                    copyToClipboard(user.id.toString(), true)
                                    true
                                }

                                R.id.action_block_user -> {
                                    if (user.id == Anilist.userid) {
                                        snackString(R.string.cannot_block_yourself)
                                        return@setOnMenuItemClickListener true
                                    }
                                    customAlertDialog().apply {
                                        setTitle(R.string.warning)
                                        setMessage("Toggle block status for ${user.name}?")
                                        setPosButton(R.string.ok) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val success = Anilist.mutation.toggleBlock(user.id)
                                                withContext(Dispatchers.Main) {
                                                    snackString(
                                                        if (success) "Updated block status"
                                                        else "Failed to update block status"
                                                    )
                                                }
                                            }
                                        }
                                        setNegButton(R.string.cancel)
                                        show()
                                    }
                                    true
                                }

                                else -> false
                            }
                        }
                        popup.show()
                    }

                    profileUserAvatar.loadImage(user.avatar?.medium)
                    profileUserAvatar.openImage(
                        context.getString(R.string.avatar, user.name),
                        user.avatar?.large ?: user.avatar?.medium ?: ""
                    )
                    profileUserName.text = user.name
                    profileUserName.setOnClickListener {
                        copyToClipboard(profileUserName.text.toString(), true)
                    }

                    val animateBanner = PrefManager.getVal<Boolean>(PrefName.BannerAnimations)
                    profileBannerImage.isVisible = animateBanner
                    profileBannerImageNoKen.isVisible = !animateBanner
                    val activeBanner: ImageView =
                        if (animateBanner) profileBannerImage else profileBannerImageNoKen

                    blurImage(
                        activeBanner,
                        user.bannerImage ?: user.avatar?.medium
                    )
                    profileBannerImage.updateLayoutParams { height += statusBarHeight }
                    profileBannerImageNoKen.updateLayoutParams { height += statusBarHeight }
                    profileBannerGradient.updateLayoutParams { height += statusBarHeight }
                    profileTopBar.updatePadding(top = profileTopBar.paddingTop + statusBarHeight)
                    // collapsed, the header keeps a surface strip under the status bar so the
                    // pages' own sticky rows (stats filters) never slide under the clock
                    profileCollapsing.minimumHeight = statusBarHeight

                    activeBanner.openImage(
                        context.getString(R.string.banner, user.name),
                        user.bannerImage ?: user.avatar?.large ?: user.avatar?.medium ?: ""
                    )

                    mMaxScrollSize = profileAppBar.totalScrollRange
                    profileAppBar.addOnOffsetChangedListener(context)

                    profileFollowerCount.text =
                        ChartBuilder.formatInt(respond.data.followerPage?.pageInfo?.total ?: 0)
                    profileFollowerCountContainer.setOnClickListener {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, FollowActivity::class.java)
                                .putExtra("title", getString(R.string.followers))
                                .putExtra("userId", user.id),
                            null
                        )
                    }
                    profileFollowingCount.text =
                        ChartBuilder.formatInt(respond.data.followingPage?.pageInfo?.total ?: 0)
                    profileFollowingCountContainer.setOnClickListener {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, FollowActivity::class.java)
                                .putExtra("title", getString(R.string.following))
                                .putExtra("userId", user.id),
                            null
                        )
                    }

                    profileAnimeCount.text = ChartBuilder.formatInt(user.statistics.anime.count)
                    profileAnimeCountContainer.setOnClickListener {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, ListActivity::class.java)
                                .putExtra("anime", true)
                                .putExtra("userId", user.id)
                                .putExtra("username", user.name),
                            null
                        )
                    }

                    profileMangaCount.text = ChartBuilder.formatInt(user.statistics.manga.count)
                    profileMangaCountContainer.setOnClickListener {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, ListActivity::class.java)
                                .putExtra("anime", false)
                                .putExtra("userId", user.id)
                                .putExtra("username", user.name),
                            null
                        )
                    }

                    profileCloseButton.setOnClickListener {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
    }

    fun openStatsTab() {
        selected = 2
        binding.profileViewPager.setCurrentItem(2, true)
        if (this::navBar.isInitialized) {
            navBar.selectTabAt(2)
        }
    }

    private var isCollapsed = false
    private val collapseThresholdPercent = 70
    private var mMaxScrollSize = 0

    override fun onOffsetChanged(appBar: AppBarLayout, verticalOffset: Int) {
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        if (mMaxScrollSize == 0) return
        val percentage = abs(verticalOffset) * 100 / mMaxScrollSize

        with(bindingProfileAppBar) {
            if (percentage >= collapseThresholdPercent && !isCollapsed) {
                isCollapsed = true
                profileBannerImage.pause()
            } else if (percentage < collapseThresholdPercent && isCollapsed) {
                isCollapsed = false
                if (PrefManager.getVal(PrefName.BannerAnimations)) {
                    profileBannerImage.resume()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selectedTab", selected)
    }

    override fun onResume() {
        if (this::navBar.isInitialized) {
            navBar.selectTabAt(selected)
        }
        super.onResume()
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val user: Query.UserProfile
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ProfileFragment.newInstance(user)
            1 -> ActivityFragment.newInstance(ActivityType.OTHER_USER, user.id)
            2 -> StatsFragment.newInstance(user)
            else -> ProfileFragment.newInstance(user)
        }
    }
}
