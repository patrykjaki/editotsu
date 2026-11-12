package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.GesturesListener
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.animethemes.MediaThemesFragment
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.comments.CommentsFragment
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.notifications.comment.MediaNameFetch
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {
    lateinit var launcher: LauncherWrapper
    lateinit var binding: ActivityMediaBinding

    /** False while onCreate bailed out before inflating (the activity is finishing). */
    val hasBinding get() = ::binding.isInitialized
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    /** Saved tab value ([MediaTabs]); [tabWindows] maps it to a page. */
    var selected = 0
    private var tabWindows: List<Int> = MediaTabs.windows(anime = true, comments = false)
    lateinit var navBar: AnimatedBottomBar
    var anime = true
    private var adult = false
    lateinit var media: Media

    /** False while the activity is bailing out of [onCreate] without any media. */
    val bindingReady get() = ::binding.isInitialized

    /** Ids of a media still being fetched, saved so a rotation during the load keeps them. */
    private var pendingId = -1
    private var pendingMalId = 0

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Batch 2 correction (review HOLD, Blocker 2): retain ONLY lightweight
        // identity (two ints) across recreation — never the serialized Media
        // graph (Assist/binder risk). The intent "media" extra stays removed.
        if (::media.isInitialized && media.name != "No media found") {
            outState.putInt("saved_media_id", media.id)
            outState.putInt("saved_media_mal_id", media.idMAL ?: 0)
        } else if (pendingId != -1) {
            outState.putInt("saved_media_id", pendingId)
            outState.putInt("saved_media_mal_id", pendingMalId)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        val intentMedia: Media? = intent.getSerialized("media") ?: mediaSingleton
        // Upstream 3f366f69: drop the large extra so Assist/screenshot serialization
        // never attempts to serialize the Media graph.
        intent.removeExtra("media")
        // Batch 2 correction: the intent "mediaId" survives recreation; the
        // saved identity covers opens that carried only the (now removed)
        // "media" extra. Intent key always wins when present.
        val intentId = intent.getIntExtra("mediaId", -1).takeIf { it != -1 }
        val savedId = savedInstanceState?.getInt("saved_media_id", -1)?.takeIf { it != -1 }
        val savedMalId = savedInstanceState?.getInt("saved_media_mal_id", 0)?.takeIf { it != 0 }
        // A configuration change keeps the ViewModel, so the media already on
        // screen is reused and nothing is fetched again.
        val retained = if (savedInstanceState != null) model.shownMedia else null
        val id = intentId ?: savedId ?: -1
        val needsFetch = retained == null && id != -1
        // When a fetch is needed after process death, the restored tab fragments
        // would start before the views exist; drop them; the pager recreates them.
        super.onCreate(if (needsFetch) null else savedInstanceState)
        ThemeManager(this).applyTheme(MediaSingleton.bitmap)
        // Activity-result launchers must be registered before the activity starts,
        // so this cannot wait for an async load.
        launcher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        if (!needsFetch) {
            setUp(retained ?: intentMedia ?: emptyMedia())
            return
        }
        pendingId = id
        pendingMalId = savedMalId ?: 0
        setContentView(FrameLayout(this).apply {
            addView(
                ProgressBar(this@MediaDetailsActivity),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        })
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                fetchMedia(id, intentId, savedMalId)
            }
            if (isFinishing || isDestroyed) return@launch
            // A failed fetch falls back to the media the screen was opened with.
            setUp(fetched ?: intentMedia ?: emptyMedia())
        }
    }

    private suspend fun fetchMedia(id: Int, intentId: Int?, savedMalId: Int?): Media? {
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
        return if (rescueMode) {
            // Prefer the MAL identity matching how this screen was opened:
            // intent opens use the intent id; saved-state opens use the saved MAL id.
            val malId = intentId ?: savedMalId ?: id
            MAL.query.getAnimeDetails(malId)?.let { Media(it, true) }
                ?: MAL.query.getMangaDetails(malId)?.let { Media(it, false) }
        } else {
            Anilist.query.getMedia(id, false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUp(initial: Media) {
        var media = initial
        this.media = media
        pendingId = -1
        if (media.name == "No media found") {
            snackString(media.name)
            // finish(), not a back press: a back callback can keep the activity alive,
            // and fragments restored by super.onCreate would then start without a binding.
            finish()
            return
        }
        model.shownMedia = media

        mediaSingleton = null
        initActivity(this)
        MediaSingleton.bitmap = null
        MediaSingleton.media = null

        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        navBar = binding.mediaBottomBar

        supportFragmentManager.addOnBackStackChangedListener {
            syncExtensionPrefsUi()
        }

        // Ui init


        binding.mediaBottomBarContainer?.setPadding(0, 0, 0, navBarHeight)

        AndroidBug5497Workaround.assistActivity(this) { keyboardVisible ->
            // Optionally hide nav bar when keyboard is visible
            navBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        }
        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaHome?.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        binding.mediaTitle.isSelected = true

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaHome?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("goToHome", true)
            }
            startActivity(intent)
            finish()
        }

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        if (bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val generator = RandomTransitionGenerator(
                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed) as Float))).toLong(),
                adi
            )
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        val banner =
            if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
        val viewPager = binding.mediaViewPager
        viewPager.offscreenPageLimit = 2
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())


        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)

        binding.mediaCoverImage.loadImage(media.cover)
        binding.mediaCoverImage.setOnLongClickListener {
            val coverTitle = getString(R.string.cover, media.userPreferredName)
            ImageViewDialog.newInstance(
                this,
                coverTitle,
                media.cover
            )
        }

        blurImage(banner, media.banner ?: media.cover)
        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onDoubleClick(event: MotionEvent) {
                if (!(PrefManager.getVal(PrefName.BannerAnimations) as Boolean))
                    snackString(getString(R.string.enable_banner_animations))
                else {
                    binding.mediaBanner.restart()
                    binding.mediaBanner.performClick()
                }
            }

            override fun onLongClick(event: MotionEvent) {
                val bannerTitle = getString(R.string.banner, media.userPreferredName)
                ImageViewDialog.newInstance(
                    this@MediaDetailsActivity,
                    bannerTitle,
                    media.banner ?: media.cover
                )
                banner.performClick()
            }
        })
        banner.setOnTouchListener { _, motionEvent -> gestureDetector.onTouchEvent(motionEvent);true }
        if (PrefManager.getVal(PrefName.Incognito)) {
            val mediaTitle = "    ${media.userPreferredName}"
            binding.mediaTitle.text = mediaTitle
            binding.incognito.visibility = View.VISIBLE
        } else {
            binding.mediaTitle.text = media.userPreferredName
        }
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        MediaNameFetch.cacheMedia(
            media.id,
            media.userPreferredName,
            media.cover ?: "",
            "#222222"
        )
        binding.mediaStatus.text = media.status ?: ""
        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)

        fun fav(media: Media):  PopImageButton? {
            //Fav Button
            return if (Anilist.userid != null && !rescueMode) {
                if (media.isFav) binding.mediaFav.setImageDrawable(
                    AppCompatResources.getDrawable(
                        this,
                        R.drawable.ic_round_favorite_24
                    )
                )

                PopImageButton(
                    scope,
                    binding.mediaFav,
                    R.drawable.ic_round_favorite_24,
                    R.drawable.ic_round_favorite_border_24,
                    R.color.bg_opp,
                    R.color.violet_400,
                    media.isFav
                ) {
                    media.isFav = it
                    Anilist.mutation.toggleFav(media.anime != null, media.id)
                    Refresh.all()
                }
            } else {
                binding.mediaFav.visibility = View.GONE
                null
            }
        }
        var isFavSyncRunning = false
        fun syncMediaFavStateIfNeeded(favButton: PopImageButton?) {
            if (rescueMode || Anilist.userid == null || favButton == null || media.isFav || isFavSyncRunning) return
            isFavSyncRunning = true
            scope.launch {
                try {
                    val favType = if (media.anime != null) {
                        ani.dantotsu.connections.anilist.AnilistMutations.FavType.ANIME
                    } else {
                        ani.dantotsu.connections.anilist.AnilistMutations.FavType.MANGA
                    }
                    val isUserFav = withContext(Dispatchers.IO) {
                        Anilist.query.isUserFav(favType, media.id)
                    }
                    if (isUserFav) {
                        media.isFav = true
                        if (!favButton.clicked) favButton.clicked()
                    }
                } finally {
                    isFavSyncRunning = false
                }
            }
        }

        @SuppressLint("ResourceType")
        fun total() {
            val text = SpannableStringBuilder().apply {

                val white =
                    this@MediaDetailsActivity.getThemeColor(com.google.android.material.R.attr.colorOnBackground)
                if (media.userStatus != null) {
                    append(if (media.anime != null) getString(R.string.watched_num) else getString(R.string.read_num))
                    val colorSecondary =
                        getThemeColor(com.google.android.material.R.attr.colorSecondary)
                    bold { color(colorSecondary) { append("${media.userProgress}") } }
                    append(
                        if (media.anime != null) getString(R.string.episodes_out_of) else getString(
                            R.string.chapters_out_of
                        )
                    )
                } else {
                    append(
                        if (media.anime != null) getString(R.string.episodes_total_of) else getString(
                            R.string.chapters_total_of
                        )
                    )
                }
                if (media.anime != null) {
                    if (media.anime!!.nextAiringEpisode != null) {
                        bold { color(white) { append("${media.anime!!.nextAiringEpisode}") } }
                        append(" / ")
                    }
                    bold { color(white) { append("${media.anime!!.totalEpisodes ?: "??"}") } }
                } else
                    bold { color(white) { append("${media.manga!!.totalChapters ?: "??"}") } }
            }
            binding.mediaTotal.text = text
        }

        fun progress() {
            val statuses: Array<String> = resources.getStringArray(R.array.status)
            val statusStrings =
                if (media.manga == null) resources.getStringArray(R.array.status_anime) else resources.getStringArray(
                    R.array.status_manga
                )
            val userStatus =
                if (media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)] else statusStrings[0]

            if (media.userStatus != null) {
                binding.mediaTotal.visibility = View.VISIBLE
                binding.mediaAddToList.text = userStatus
            } else {
                binding.mediaAddToList.setText(R.string.add_list)
            }
            total()
            binding.mediaAddToList.setOnClickListener {
                if (rescueMode) {
                    if (MAL.token != null) {
                        if (supportFragmentManager.findFragmentByTag("dialog") == null)
                            MediaListDialogFragment().show(supportFragmentManager, "dialog")
                    } else snackString("Please login to MAL")
                } else if (Anilist.userid != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null)
                        MediaListDialogFragment().show(supportFragmentManager, "dialog")
                } else snackString(getString(R.string.please_login_anilist))
            }
            binding.mediaAddToList.setOnLongClickListener {
                PrefManager.setCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
                snackString(getString(R.string.auto_update_reset))
                true
            }
        }
        progress()

        model.getMedia().observe(this) {
            if (it != null) {
                val oldId = media.id
                media = it
                model.shownMedia = it

                // mapping button for LOCAL media
                if (media.format?.startsWith("LOCAL") == true) {
                    binding.mediaMapping?.visibility = View.VISIBLE
                    binding.mediaMapping?.setOnClickListener {
                        val isAnime = media.anime != null
                        val isNovel = media.format == "LOCAL_NOVEL"
                        val folderName = media.folderName ?: media.name ?: media.nameRomaji
                        val dialog = LocalMappingSearchDialog.newInstance(
                            folderName = folderName,
                            isAnime = isAnime,
                            isNovel = isNovel
                        ) { _ ->
                            // Re-trigger it
                            val updatedMedia = media.copy(id = 0)
                            model.loading = false
                            model.loadMedia(updatedMedia)
                        }
                        dialog.show(supportFragmentManager, "localMapping")
                    }
                }

                scope.launch {
                    val favIcon = fav(it)
                    syncMediaFavStateIfNeeded(favIcon)
                    if (media.isFav != favIcon?.clicked) favIcon?.clicked()
                }


                binding.mediaNotify.setOnClickListener {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, media.shareLink)
                    startActivity(Intent.createChooser(i, media.userPreferredName))
                }
                binding.mediaNotify.setOnLongClickListener {
                    openLinkInBrowser(media.shareLink)
                    true
                }
                binding.mediaCover.setOnClickListener {
                    openLinkInBrowser(media.shareLink)
                }

                if (oldId == 0 && media.id != 0) {
                    if (media.format?.startsWith("LOCAL") == true) {
                        binding.mediaCoverImage.loadImage(media.cover)
                        blurImage(if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen, media.banner ?: media.cover)
                    }
                }
                progress()
            }
        }
        adult = media.isAdult
        if (media.anime != null) {
            viewPager.adapter =
                ViewPagerAdapter(
                    supportFragmentManager,
                    lifecycle,
                    SupportedMedia.ANIME,
                    media,
                    intent.getIntExtra("commentId", -1)
                )
        } else if (media.manga != null) {
            viewPager.adapter = ViewPagerAdapter(
                supportFragmentManager,
                lifecycle,
                if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") SupportedMedia.NOVEL else SupportedMedia.MANGA,
                media,
                intent.getIntExtra("commentId", -1)
            )
            anime = false
        }
        // The Comments tab is shown while comments are off too: it explains what turning them on sends.
        val commentsOn = !rescueMode
        tabWindows = MediaTabs.windows(anime, commentsOn)
        selected = media.selected!!.window.takeIf { it in tabWindows } ?: MediaTabs.MAIN
        binding.mediaTitle.translationX = -screenWidth

        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val watchTab = if (anime) {
            navBar.createTab(R.drawable.ic_round_movie_filter_24, R.string.watch, R.id.watch)
        } else if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") {
            navBar.createTab(R.drawable.ic_round_book_24, R.string.read, R.id.read)
        } else {
            navBar.createTab(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read)
        }
        val themesTab = navBar.createTab(R.drawable.ic_round_audiotrack_24, R.string.theme_tab, R.id.themes)
        val commentTab =
            navBar.createTab(R.drawable.ic_round_comment_24, R.string.comments, R.id.comment)
        tabWindows.forEach {
            navBar.addTab(when (it) {
                MediaTabs.INFO -> infoTab
                MediaTabs.THEMES -> themesTab
                MediaTabs.COMMENTS -> commentTab
                else -> watchTab
            })
        }
        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
            selected = MediaTabs.MAIN
        }
        if (intent.getStringExtra("FRAGMENT_TO_LOAD") != null && commentsOn) selected = MediaTabs.COMMENTS
        val page = MediaTabs.pageOf(selected, tabWindows)
        if (viewPager.currentItem != page) viewPager.post {
            viewPager.setCurrentItem(page, false)
        }
        binding.commentInputLayout.isVisible = selected == MediaTabs.COMMENTS
        navBar.selectTabAt(page)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = MediaTabs.windowAt(newIndex, tabWindows)
                binding.commentInputLayout.isVisible = selected == MediaTabs.COMMENTS
                viewPager.setCurrentItem(newIndex, true)
                val sel = model.loadSelected(media, isDownload)
                sel.window = selected
                model.saveSelected(media.id, sel)
            }
        })

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::navBar.isInitialized) return
        val rightMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) navBarHeight else 0
        val bottomMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) 0 else navBarHeight
        val params: ViewGroup.MarginLayoutParams =
            navBar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(right = rightMargin, bottom = bottomMargin)
    }

    // Upstream 3f366f69: keep Assist / Circle to Search from serializing activity extras.
    override fun onProvideAssistContent(outContent: android.app.assist.AssistContent?) {
        super.onProvideAssistContent(outContent)
        outContent?.intent = null
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Upstream 55f3621a: keep extension source settings open across
        // app switch; sync visibility from fragment presence.
        syncExtensionPrefsUi()
        binding.root.requestLayout()
    }

    private fun syncExtensionPrefsUi() {
        if (!::binding.isInitialized) return
        setSourcePrefsVisible(
            supportFragmentManager.findFragmentById(R.id.fragmentExtensionsContainer) != null
        )
    }

    /** The home and incognito badges float above everything, so they are parked while settings show. */
    private var prefsHidHome = false
    private var prefsHidIncognito = false

    /** Shows or hides the extension settings that open over the media page. */
    fun setSourcePrefsVisible(show: Boolean) {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        binding.fragmentExtensionsContainer.isVisible = show
        binding.mediaAppBar.isVisible = !show
        binding.mediaViewPager.isVisible = !show
        binding.mediaCover.isVisible = !show
        binding.mediaClose.isVisible = !show
        binding.commentInputLayout.isVisible = !show && selected == MediaTabs.COMMENTS
        binding.mediaHome?.let { home ->
            if (show && home.isVisible) {
                prefsHidHome = true
                home.isVisible = false
            } else if (!show && prefsHidHome) {
                prefsHidHome = false
                home.isVisible = true
            }
        }
        if (show && binding.incognito.isVisible) {
            prefsHidIncognito = true
            binding.incognito.isVisible = false
        } else if (!show && prefsHidIncognito) {
            prefsHidIncognito = false
            binding.incognito.isVisible = true
        }
        if (::navBar.isInitialized) {
            navBar.isVisible = !show
            if (!show) navBar.selectTabAt(MediaTabs.pageOf(selected, tabWindows))
        }
    }

    private enum class SupportedMedia {
        ANIME, MANGA, NOVEL
    }

    // ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val mediaType: SupportedMedia,
        private val media: Media,
        private val commentId: Int
    ) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        private val windows = MediaTabs.windows(
            anime = mediaType == SupportedMedia.ANIME,
            comments = !PrefManager.getVal<Boolean>(PrefName.RescueMode),
        )

        override fun getItemCount(): Int = windows.size

        override fun createFragment(position: Int): Fragment = when (MediaTabs.windowAt(position, windows)) {
            MediaTabs.INFO -> MediaInfoFragment()
            MediaTabs.THEMES -> MediaThemesFragment()
            MediaTabs.COMMENTS -> {
                val fragment = CommentsFragment()
                val bundle = Bundle()
                bundle.putInt("mediaId", media.id)
                bundle.putString("mediaName", media.mainName())
                bundle.putString("mediaFormat", media.format)
                if (commentId != -1) bundle.putInt("commentId", commentId)
                fragment.arguments = bundle
                fragment
            }
            else -> when (mediaType) {
                SupportedMedia.ANIME -> AnimeWatchFragment()
                SupportedMedia.MANGA -> MangaReadFragment()
                SupportedMedia.NOVEL -> NovelReadFragment()
            }
        }
    }

    //Collapsing UI Stuff
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0
    private var screenWidth: Float = 0f

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (!::binding.isInitialized) return
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility =
            if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val duration = (200 * (PrefManager.getVal(PrefName.AnimationSpeed) as Float)).toLong()
        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            binding.mediaBanner.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f)
                .setDuration(duration).start()
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.mediaBanner.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(
            false
        )
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        needsInitialClick: Boolean = false,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        fun setState(state: Boolean) {
            if (clicked != state) {
                clicked = state
                scope.launch {
                    clicked()
                }
            }
        }

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch {
                    clicked()
                }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100.milliseconds)

            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c1),
                    ContextCompat.getColor(context, c2)
                ).setDuration(120).start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
            delay(120.milliseconds)
            ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
            delay(200.milliseconds)
            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c2),
                    ContextCompat.getColor(context, c1)
                ).setDuration(200).start()
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }

    companion object {
        var mediaSingleton: Media? = null
    }
}
