package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.graphics.drawable.Icon
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.System
import android.util.AttributeSet
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import ani.dantotsu.Mapper
import ani.dantotsu.defaultHeaders
import ani.dantotsu.animethemes.ThemeDownloads
import ani.dantotsu.animethemes.ThemeFavourites
import ani.dantotsu.animethemes.ThemeFileKind
import ani.dantotsu.animethemes.ThemeQueue
import ani.dantotsu.animethemes.ThemeRadioSession
import ani.dantotsu.animethemes.ThemeReach
import ani.dantotsu.animethemes.ThemeReachResult
import ani.dantotsu.animethemes.ThemeTrack
import ani.dantotsu.media.anime.player.PlaybackRequest
import ani.dantotsu.media.anime.player.PlaybackSourceClass
import kotlinx.serialization.builtins.ListSerializer
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.rpc.DiscordPresenceThrottle
import ani.dantotsu.connections.subtitles.OpenSubRestItem
import ani.dantotsu.connections.subtitles.OpenSubtitlesRestApi
import ani.dantotsu.connections.subtitles.StremioSub
import ani.dantotsu.connections.subtitles.StremioSubtitles
import ani.dantotsu.connections.subtitles.SubSourceSub
import ani.dantotsu.connections.subtitles.SubSourceSubtitles
import ani.dantotsu.connections.subtitles.WyzieSub
import ani.dantotsu.connections.subtitles.WyzieSubtitles
import ani.dantotsu.databinding.ActivityExoplayerBinding
import ani.dantotsu.hideSystemBars
import ani.dantotsu.isOnline
import ani.dantotsu.media.EpisodeMapper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.continueListWith
import ani.dantotsu.media.isUnlinkedPlayback
import ani.dantotsu.torrent.TorrentRelayLink
import ani.dantotsu.media.anime.player.CastScreenView
import ani.dantotsu.media.anime.player.DantotsuPlayerManager
import ani.dantotsu.media.anime.player.DantotsuPlayerView
import ani.dantotsu.media.anime.player.EpisodeResumeKeys
import ani.dantotsu.media.anime.player.EpisodePlaybackSession
import ani.dantotsu.media.anime.player.PlayerDisplayFormat
import ani.dantotsu.media.anime.player.PlaybackSpeedSelection
import ani.dantotsu.media.anime.player.ErrorCategory
import ani.dantotsu.media.anime.player.PlaybackError
import ani.dantotsu.media.anime.player.PlaybackListener
import ani.dantotsu.media.anime.player.PlaybackState
import ani.dantotsu.media.anime.player.PlayerAniSkipManager
import ani.dantotsu.media.anime.player.PlayerCastManager
import ani.dantotsu.media.anime.player.PlayerDiscordManager
import ani.dantotsu.media.anime.player.PlayerGestureManager
import ani.dantotsu.media.anime.player.PlayerProgressManager
import ani.dantotsu.media.anime.player.PlayerSubtitleManager
import ani.dantotsu.media.anime.player.PlayerTrack
import ani.dantotsu.media.anime.player.ResizeMode
import ani.dantotsu.media.anime.player.SubtitleLanguageMatch
import ani.dantotsu.media.anime.player.TrackType
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.parsers.preferredPlayableFallback
import ani.dantotsu.settings.PlayerSettingsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.bumptech.glide.Glide
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.anime.AnimeDownloader
import ani.dantotsu.download.video.Helper
import ani.dantotsu.download.video.findBestVideoForDownload
import ani.dantotsu.media.MediaType
import kotlin.math.max
import kotlin.math.min

/**
 * Resume-position prefs key (M7A G2 HOLD1): namespaced by media id, so
 * per-torrent synthetic identities isolate playback state — torrent B can
 * never restore torrent A's position, while real media keeps its exact
 * existing keys (behavior unchanged). Pure, JVM-tested (A → B → A).
 */
internal fun resumePositionKey(mediaId: Int, episodeKey: String): String =
    "${mediaId}_$episodeKey"

@UnstableApi
@SuppressLint("ClickableViewAccessibility")
class ExoplayerView : AppCompatActivity(), PlaybackListener {

    private val resumeWindow = "resumeWindow"
    private val resumePosition = "resumePosition"
    private val playerFullscreen = "playerFullscreen"
    private val playerOnPlay = "playerOnPlay"

    lateinit var playerManager: DantotsuPlayerManager
        private set
    lateinit var subtitleManager: PlayerSubtitleManager
        private set
    lateinit var gestureManager: PlayerGestureManager
        private set
    lateinit var aniSkipManager: PlayerAniSkipManager
        private set
    lateinit var discordManager: PlayerDiscordManager
        private set
    lateinit var castManager: PlayerCastManager
        private set
    lateinit var progressManager: PlayerProgressManager
        private set

    private lateinit var binding: ActivityExoplayerBinding
    private lateinit var playerView: DantotsuPlayerView
    private lateinit var exoPlay: ImageButton
    private lateinit var exoSource: ImageButton
    private lateinit var exoSettings: ImageButton
    private lateinit var exoSubtitle: ImageButton
    private lateinit var exoAudioTrack: ImageButton
    private lateinit var exoRotate: ImageButton
    private lateinit var exoSpeed: ImageButton
    private lateinit var exoScreen: ImageButton
    private lateinit var exoNext: ImageButton
    private lateinit var exoPrev: ImageButton
    private lateinit var exoSkipOpEd: ImageButton
    private lateinit var exoPip: ImageButton
    private lateinit var exoScreenshot: ImageButton
    private lateinit var exoSleepTimer: ImageButton
    private val sleepTimer = SleepTimer()

    /** Theme mode: what happens when a theme ends. */
    private enum class ThemeRepeat { OFF, ALL, ONE }
    private var themeRepeat = ThemeRepeat.OFF
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private val sleepTimerFire = Runnable {
        if (sleepTimer.onDeadline(java.lang.System.currentTimeMillis())) {
            playerManager.playbackEngine?.let { engine ->
                // Themes have no resume position (and no media in theme mode).
                if (!themeMode) saveActivePosition()
                engine.pause()
            }
            toast(getString(R.string.sleep_timer_paused))
            exoSleepTimer.alpha = 1f
        }
    }
    private lateinit var exoBrightness: Slider
    private lateinit var exoVolume: Slider
    private lateinit var exoBrightnessCont: View
    private lateinit var exoVolumeCont: View
    private lateinit var exoSkip: View
    private lateinit var skipTimeButton: View
    private lateinit var skipTimeText: TextView
    private lateinit var timeStampText: TextView
    private lateinit var animeTitle: TextView
    private lateinit var videoInfo: TextView
    private lateinit var episodeTitle: Spinner
    private lateinit var customCastButton: CustomCastButton
    private lateinit var castScreenView: CastScreenView
    private lateinit var timeline: ExtendedTimeBar
    private lateinit var exoPositionText: TextView
    private lateinit var exoDurationText: TextView
    private lateinit var exoBufferingIndicator: View

    private var orientationListener: OrientationEventListener? = null
    private var hasExtSubtitles = false

    /**
     * The media this player instance was opened for. The picker replaces the companion
     * [Companion.media] before it starts the next player, and this instance still saves its
     * position and clears its caches on the way out, so it must never read the companion
     * after onCreate.
     */
    private lateinit var media: Media

    private lateinit var episode: Episode
    private lateinit var episodes: MutableMap<String, Episode>
    private lateinit var episodeArr: List<String>
    private lateinit var episodeTitleArr: ArrayList<String>
    private var currentEpisodeIndex = 0
    private var epChanging = false

    private var extractor: VideoExtractor? = null
    private var video: Video? = null
    private var subtitle: Subtitle? = null

    private var currentWindow = 0
    private var playbackPosition: Long = 0
    private val playbackSession = EpisodePlaybackSession()
    private var isFullscreen: Int = 0

    // Theme mode: AnimeThemes openings/endings in this player. No episode list, resume,
    // AniList progress, AniSkip, Discord or source picker; only the themes queue.
    private var themeMode = false
    private var themeTracks: List<ThemeTrack> = emptyList()
    private var themeIndex = 0
    private var themeLastPositionMs = 0L
    private var themeAnime = ""
    /** Every attempt for the current theme (saved copy, audio, video, other videos). */
    private var themePlan: List<ThemeQueue.Attempt> = emptyList()
    private var themeAttempt = 0
    private var themeJob: Job? = null
    /** Shuffle from completed shows: the queue grows from [ThemeRadioSession]. */
    private var themeRadio = false
    private var themeRadioJob: Job? = null
    private lateinit var themeFailedPanel: View
    private lateinit var themeCenter: View
    private lateinit var themeFavourite: ImageButton
    private lateinit var themeAdapter: NoPaddingArrayAdapter<String>

    // Episodes: "now playing" chip for the opening/ending on screen (inside the controls).
    private var nowPlayingChoices: List<ani.dantotsu.animethemes.ThemeChoice> = emptyList()
    private var nowPlayingTrack: ThemeTrack? = null
    private var nowPlayingView: TextView? = null
    private var isPlayerPlaying = true
    // Discontinuity-based throttle: publishes only on seeks, scrub stops, and play/pause —
    // NOT during ordinary linear playback. Replaces the previous 2-second periodic approach
    // that could exhaust Discord's ~5 updates / 20 seconds rate limit.
    private val discordThrottle = DiscordPresenceThrottle()
    private var changingServer = false
    // True while the viewer touched the player within AutoplayPolicy.IDLE_TIMEOUT_MS.
    private var interacted = true
    private val autoplayIdleHandler = Handler(Looper.getMainLooper())
    private val autoplayIdleReset = Runnable { interacted = false }
    private val autoplayEndGate = AutoplayEndGate()
    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)
    private var isBuffering = true
    private var playerErrorRetryCount = 0
    private var rotation = 0
    private var wasPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    val model: MediaDetailsViewModel by viewModels()
    private val client = OkHttpClient()

    /**
     * Set while the player opens one of its own screens (player settings, the subtitle file
     * picker), so onUserLeaveHint does not shrink the player into PiP. Cleared on resume.
     */
    private var launchingChildScreen = false
    /** Whether playback was running when player settings opened; only then it resumes. */
    private var playingBeforeSettings = false

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { applyLocalSubtitle(it) }
    }

    private val onChangeSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        if (this::subtitleManager.isInitialized) {
            subtitleManager.applySubtitlePreferences()
        }
        if (playingBeforeSettings && this::playerManager.isInitialized && playerManager.isInitialized) {
            playerManager.playbackEngine?.play()
        }
        playingBeforeSettings = false
    }

    class ExtendedTimeBar @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : DefaultTimeBar(context, attrs) {
        private var isForceDisabled: Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return if (isForceDisabled) {
                false
            } else {
                super.onTouchEvent(event)
            }
        }

        fun setForceDisabled(forceDisabled: Boolean) {
            this.isForceDisabled = forceDisabled
        }
    }

    companion object {
        var initialized = false
        lateinit var media: Media
        private const val MAX_PLAYER_ERROR_RETRIES = 1

        const val ACTION_PIP_PREV_EP = "ani.dantotsu.PIP_PREV_EP"
        const val ACTION_PIP_REWIND = "ani.dantotsu.PIP_REWIND"
        const val ACTION_PIP_PLAY_PAUSE = "ani.dantotsu.PIP_PLAY_PAUSE"
        const val ACTION_PIP_SKIP = "ani.dantotsu.PIP_SKIP"
        const val ACTION_PIP_NEXT_EP = "ani.dantotsu.PIP_NEXT_EP"

        private const val EXTRA_THEME_MODE = "themeMode"
        private const val EXTRA_THEMES = "themes"
        private const val EXTRA_THEME_START = "themeStart"
        private const val EXTRA_THEME_ANIME = "themeAnime"
        private const val EXTRA_THEME_RADIO = "themeRadio"

        /**
         * Plays AnimeThemes openings/endings in this player, starting at [start]. With
         * [radio] the queue keeps growing from [ThemeRadioSession] when it runs out.
         */
        fun openThemes(context: Context, tracks: List<ThemeTrack>, start: Int, animeTitle: String, radio: Boolean = false) {
            if (tracks.isEmpty()) return
            context.startActivity(
                Intent(context, ExoplayerView::class.java)
                    .putExtra(EXTRA_THEME_MODE, true)
                    .putExtra(EXTRA_THEMES, Mapper.json.encodeToString(ListSerializer(ThemeTrack.serializer()), tracks))
                    .putExtra(EXTRA_THEME_START, start)
                    .putExtra(EXTRA_THEME_ANIME, animeTitle)
                    .putExtra(EXTRA_THEME_RADIO, radio)
            )
        }
    }

    override fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = window.decorView.rootWindowInsets?.displayCutout
            if (displayCutout != null && displayCutout.boundingRects.size > 0) {
                val notch = min(
                    displayCutout.boundingRects[0].width(),
                    displayCutout.boundingRects[0].height()
                )
                if (this::gestureManager.isInitialized) {
                    gestureManager.updateNotchHeight(notch)
                }
            }
        }
        super.onAttachedToWindow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = intent.getBooleanExtra(EXTRA_THEME_MODE, false)

        if (!initialized && !themeMode) {
            startMainActivity(this)
            finish()
            return
        }

        ThemeManager(this).applyTheme()
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = binding.playerView
        hideSystemBars()

        // Bind Views
        exoPlay = playerView.findViewById(androidx.media3.ui.R.id.exo_play)
        exoSource = playerView.findViewById(R.id.exo_source)
        exoSettings = playerView.findViewById(R.id.exo_settings)
        exoSubtitle = playerView.findViewById(R.id.exo_sub)
        exoAudioTrack = playerView.findViewById(R.id.exo_audio)

        exoRotate = playerView.findViewById(R.id.exo_rotate)
        exoSpeed = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed)
        exoScreen = playerView.findViewById(R.id.exo_screen)
        exoBrightness = playerView.findViewById(R.id.exo_brightness)
        exoVolume = playerView.findViewById(R.id.exo_volume)
        exoBrightnessCont = playerView.findViewById(R.id.exo_brightness_cont)
        exoVolumeCont = playerView.findViewById(R.id.exo_volume_cont)
        exoPip = playerView.findViewById(R.id.exo_pip)
        exoScreenshot = playerView.findViewById(R.id.exo_screenshot)
        exoSleepTimer = playerView.findViewById(R.id.exo_sleep_timer)
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)
        // The dot before the chapter name only shows when there is a name.
        val timeStampSep = playerView.findViewById<View>(R.id.exo_time_stamp_sep)
        timeStampText.doAfterTextChanged { timeStampSep.isVisible = !it.isNullOrEmpty() }
        animeTitle = playerView.findViewById(R.id.exo_anime_title)
        episodeTitle = playerView.findViewById(R.id.exo_ep_sel)
        customCastButton = playerView.findViewById(R.id.exo_cast)
        timeline = playerView.findViewById(androidx.media3.ui.R.id.exo_progress)
        exoPositionText = playerView.findViewById(androidx.media3.ui.R.id.exo_position)
        exoDurationText = playerView.findViewById(androidx.media3.ui.R.id.exo_duration)
        exoBufferingIndicator = playerView.findViewById(androidx.media3.ui.R.id.exo_buffering)

        exoSource.setOnClickListener { sourceClick() }

        // Setup Timeline Scrubber
        timeline.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {}
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                exoPositionText.text = formatTime(position)
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) {
                    playerManager.playbackEngine?.seekTo(position)
                    // Use the scrub target directly — seekTo is async on mpv, so
                    // playbackEngine.positionMs may still return the pre-seek value.
                    if (this@ExoplayerView::episode.isInitialized && discordThrottle.onScrubStop(position)) {
                        discordManager.updatePresence(
                            media, this@ExoplayerView.episode,
                            position,
                            playerManager.playbackEngine?.durationMs ?: 0L,
                            isPlayerPlaying
                        )
                    }
                }
            }
        })

        // Initialize Managers
        subtitleManager = PlayerSubtitleManager(this, model) {
            playerManager.playbackEngine
        }
        playerManager = DantotsuPlayerManager(this, playerView, subtitleManager, client) { error ->
            onPlayerError(error)
        }
        gestureManager = PlayerGestureManager(
            this, playerView, exoBrightnessCont, exoVolumeCont, exoBrightness, exoVolume,
            { playerManager.playbackEngine }, { playerManager.isInitialized }
        )
        playerView.autoHide = { gestureManager.handleController() }
        aniSkipManager = PlayerAniSkipManager(
            this, timeline, model, exoSkipOpEd, exoSkip, skipTimeButton, skipTimeText, timeStampText,
            { playerManager.playbackEngine }
        )
        discordManager = PlayerDiscordManager(this)
        progressManager = PlayerProgressManager(
            this, model, { playbackSession.progress(selectedEpisodeKey()) }
        )

        castManager = PlayerCastManager(this, playerView) { isPlaying ->
            isPlayerPlaying = isPlaying
            updatePipActions(isPlaying)
            playerView.keepScreenOn = isPlaying
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (this::media.isInitialized && this::episode.isInitialized) {
                discordManager.updatePresence(
                    media, episode,
                    playerManager.playbackEngine?.positionMs ?: 0L,
                    playerManager.playbackEngine?.durationMs ?: 0L,
                    isPlaying
                )
            }
        }

        castScreenView = CastScreenView(
            activity = this,
            binding = binding.castScreenView,
            castManager = castManager,
            onPlayPauseClick = {
                castManager.togglePlayPause()
            },
            onPreviousClick = {
                if (currentEpisodeIndex > 0) {
                    changeEpisode(currentEpisodeIndex - 1)
                } else {
                    snackString(getString(R.string.first_episode), this)
                }
            },
            onNextClick = {
                progressManager.nextEpisode { i ->
                    changeEpisode(currentEpisodeIndex + i)
                }
            },
            onSeekTo = { posMs: Long ->
                castManager.seekTo(posMs)
            },
            onPlaylistClick = {
                episodeTitle.performClick()
            },
            onSpeedClick = {
                exoSpeed.performClick()
            },
            onSubtitlesClick = {
                exoSubtitle.performClick()
            },
            onAudioClick = {
                exoAudioTrack.performClick()
            },
            onQualityClick = {
                exoSource.performClick()
            },
            onCustomSkipClick = {
                exoSkip.performLongClick()
            },
            onSkipIntroClick = {
                aniSkipManager.skipCurrentInterval()
            }
        )
        castManager.castScreenView = castScreenView

        castManager.onSessionStartedListener = started@{ deviceName, owner ->
            if (!playbackSession.acceptsEngine(owner, selectedEpisodeKey())) return@started
            owner?.pause()
            if (this::media.isInitialized) {
                castScreenView.updateMediaInfo(media, if (this::episode.isInitialized) episode else null)
            }
            castScreenView.updateDeviceName(deviceName)
            castScreenView.show(true)
        }

        castManager.onSessionEndedListener = { owner, resumePositionMs, shouldPlay ->
            castScreenView.hide(true)
            owner?.takeIf { playbackSession.acceptsEngine(it, selectedEpisodeKey()) }?.let { engine ->
                engine.seekTo(resumePositionMs)
                if (shouldPlay) engine.play() else engine.pause()
            }
        }

        // Sensor & Orientation
        if (System.getInt(contentResolver, System.ACCELEROMETER_ROTATION, 0) != 1) {
            if (PrefManager.getVal(PrefName.RotationPlayer)) {
                orientationListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
                    override fun onOrientationChanged(orientation: Int) {
                        when (orientation) {
                            in 45..135 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            }
                            in 225..315 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                        }
                    }
                }
                orientationListener?.enable()
            }
            exoRotate.setOnClickListener {
                requestedOrientation = rotation
                it.visibility = View.GONE
            }
        }
        // Landscape only (either side), with or without system auto-rotate: portrait
        // squeezed the video and left the controls laid out for the wrong orientation.
        // The manifest sets the same, so the player never opens in portrait first.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (savedInstanceState != null) {
            currentWindow = savedInstanceState.getInt(resumeWindow)
            playbackPosition = savedInstanceState.getLong(resumePosition)
            isFullscreen = savedInstanceState.getInt(playerFullscreen)
            isPlayerPlaying = savedInstanceState.getBoolean(playerOnPlay)
        }

        // UI Controls
        playerView.findViewById<ImageButton>(R.id.exo_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Theme mode plays OP/ED videos: no skip times to poll for.
        aniSkipManager.init(track = !themeMode)

        // Play/Pause
        exoPlay.setOnClickListener {
            if (this::playerManager.isInitialized && playerManager.isInitialized) {
                if (this::castManager.isInitialized && castManager.isCasting()) {
                    if (castManager.castPlayer?.isPlaying == true) {
                        castManager.pause()
                    } else {
                        castManager.play()
                    }
                } else {
                    playerManager.playbackCoordinator?.togglePlayPause() ?: playerManager.play()
                }
            }
        }

        // PiP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pipEnabled = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    PrefManager.getVal(PrefName.Pip)
            if (pipEnabled) {
                exoPip.visibility = View.VISIBLE
                exoPip.setOnClickListener { enterPipMode() }
            } else {
                exoPip.visibility = View.GONE
            }
        }

        // Screenshot button (bottom right): see takePlayerScreenshot.
        exoScreenshot.setOnClickListener {
            takePlayerScreenshot()
        }
        exoSleepTimer.setOnClickListener { showSleepTimerDialog() }

        // Lock button
        val container = playerView.findViewById<View>(R.id.exo_controller_cont)
        val screen = playerView.findViewById<View>(R.id.exo_black_screen)
        val lockButton = playerView.findViewById<ImageButton>(R.id.exo_unlock)
        playerView.findViewById<ImageButton>(R.id.exo_lock).setOnClickListener {
            gestureManager.isLocked = true
            screen.visibility = View.GONE
            container.visibility = View.GONE
            lockButton.visibility = View.VISIBLE
            timeline.setForceDisabled(true)
        }
        lockButton.setOnClickListener {
            gestureManager.isLocked = false
            screen.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            it.visibility = View.GONE
            timeline.setForceDisabled(false)
        }

        // Skip Time
        var skipTime = PrefManager.getVal<Int>(PrefName.SkipTime)
        if (skipTime > 0) {
            exoSkip.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
            exoSkip.setOnClickListener {
                playerManager.playbackEngine?.seekRelative(skipTime * 1000L)
            }
            exoSkip.setOnLongClickListener {
                val dialog = Dialog(this, R.style.MyPopup)
                dialog.setContentView(R.layout.item_seekbar_dialog)
                dialog.setCancelable(true)
                dialog.setCanceledOnTouchOutside(true)
                dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val slider = dialog.findViewById<Slider>(R.id.seekbar)
                slider.value = snapSkipSliderValue(skipTime)
                slider.addOnChangeListener { _, value, _ ->
                    skipTime = value.toInt()
                    PrefManager.setVal(PrefName.SkipTime, skipTime)
                    playerView.findViewById<TextView>(R.id.exo_skip_time).text = skipTime.toString()
                    dialog.findViewById<TextView>(R.id.seekbar_value).text = skipTime.toString()
                }
                slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(s: Slider) {}
                    override fun onStopTrackingTouch(s: Slider) { dialog.dismiss() }
                })
                dialog.findViewById<TextView>(R.id.seekbar_title).text = getString(R.string.skip_time)
                dialog.findViewById<TextView>(R.id.seekbar_value).text = skipTime.toString()
                dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                dialog.show()
                true
            }
        } else {
            exoSkip.visibility = View.GONE
        }

        // Initialize Gestures
        gestureManager.initGestures()

        if (themeMode) {
            setupThemeMode()
            return
        }

        // Handle Media
        if (!initialized) return startMainActivity(this)
        media = Companion.media
        model.setMedia(media)
        setupNowPlaying()
        title = media.userPreferredName
        val eps = media.anime?.episodes
        if (eps.isNullOrEmpty()) {
            startMainActivity(this)
            finish()
            return
        }
        episodes = eps.toMutableMap()
        videoInfo = playerView.findViewById(R.id.exo_video_info)
        model.watchSources = if (media.isAdult) HAnimeSources else AnimeSources

        model.epChanged.observe(this) { epChanging = !it }
        animeTitle.text = media.userPreferredName

        episodeArr = episodes.keys.toList()
        val currentSelected = episodes.getEpisodeKey(media.anime?.selectedEpisode)
            ?: episodeArr.firstOrNull() ?: run {
                startMainActivity(this)
                finish()
                return
            }
        media.anime!!.selectedEpisode = currentSelected
        currentEpisodeIndex = max(0, episodeArr.indexOf(currentSelected))

        episodeTitleArr = arrayListOf()
        episodes.forEach {
            val ep = it.value
            val cleanedTitle = MediaNameAdapter.removeEpisodeNumberCompletely(ep.title ?: "")
            episodeTitleArr.add(
                "Episode ${PlayerDisplayFormat.episodeNumber(ep.number)}${if (ep.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}"
            )
        }

        progressManager.media = media
        progressManager.episodes = episodes
        progressManager.episodeArr = episodeArr
        progressManager.episodeTitleArr = episodeTitleArr
        progressManager.currentEpisodeIndex = currentEpisodeIndex

        // Episode Spinner
        episodeTitle.adapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, episodeTitleArr)
        episodeTitle.setSelection(currentEpisodeIndex)
        episodeTitle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position != currentEpisodeIndex) {
                    changeEpisode(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Prev / Next Episode Buttons
        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener {
            if (this::playerManager.isInitialized && playerManager.isInitialized) {
                if (this::progressManager.isInitialized) {
                    progressManager.nextEpisode { i ->
                        changeEpisode(currentEpisodeIndex + i)
                    }
                }
            }
        }

        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                changeEpisode(currentEpisodeIndex - 1)
            } else {
                snackString(getString(R.string.first_episode), this)
            }
        }

        // Episode Observer
        model.getEpisode().observe(this) { ep ->
            hideSystemBars()
            if (ep != null && !epChanging) {
                val epKey = episodes.getEpisodeKey(ep.number)
                    ?: episodeArr.find { episodes[it] === ep || episodes[it]?.number == ep.number }
                    ?: return@observe
                // Reject a late load result from a previously selected episode.
                if (epKey != selectedEpisodeKey()) return@observe
                val savedPosition = savedEpisodePosition(epKey)
                val startPosition = playbackSession.resumePosition(epKey, savedPosition)
                // Server change: across an engine swap the
                // old trackers must not keep polling a released engine, and
                // the pre-release save must not re-fire mid-switch (the
                // explicit sourceClick save already captured it). Trackers
                // restart below after initPlayer.
                if (changingServer) {
                    if (this::progressManager.isInitialized) progressManager.stopTracking()
                    if (this::aniSkipManager.isInitialized) aniSkipManager.stopTracking()
                } else {
                    saveActivePosition()
                }
                releasePlayer()
                playbackPosition = startPosition
                episode = ep
                media.selected = model.loadSelected(media)
                model.setMedia(media)
                currentEpisodeIndex = max(0, episodeArr.indexOf(epKey))
                progressManager.currentEpisodeIndex = currentEpisodeIndex
                if (currentEpisodeIndex in 0 until episodeTitleArr.size) {
                    episodeTitle.setSelection(currentEpisodeIndex)
                }
                if (this::aniSkipManager.isInitialized) {
                    aniSkipManager.resetForNewEpisode()
                }
                initPlayer()
                changingServer = false
                if (this::progressManager.isInitialized) {
                    progressManager.startTracking()
                }
                if (this::aniSkipManager.isInitialized) {
                    aniSkipManager.startTracking()
                }
            }
        }

        // FullScreen / Resize
        isFullscreen = PrefManager.getCustomVal("${media.id}_fullscreenInt", PrefManager.getVal<Int>(PrefName.Resize))
        applyResizeMode(isFullscreen)

        // Scaling button (bottom right): cycles fit -> zoom (crop) -> stretch, remembered per show.
        exoScreen.setOnClickListener {
            isFullscreen = if (isFullscreen < 2) isFullscreen + 1 else 0
            applyResizeMode(isFullscreen)
            val supported = playerManager.playbackEngine?.supportsResizeMode(resizeModeFor(isFullscreen)) != false
            val label = getString(when (isFullscreen) {
                1 -> R.string.player_scale_zoom
                2 -> R.string.player_scale_stretch
                else -> R.string.player_scale_original
            })
            snackString(if (supported) label else "$label: ${getString(R.string.player_not_available_drm)}", this)
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        // Settings Button
        exoSettings.setOnClickListener {
            playingBeforeSettings = playerManager.playbackEngine?.isPlaying == true
            playerManager.playbackEngine?.let { engine ->
                saveActivePosition()
                engine.pause()
            }
            val intent = Intent(this, PlayerSettingsActivity::class.java).apply {
                putExtra("subtitle", subtitle)
            }
            if (playerManager.playbackEngine?.supportsVideoPipeline == false) {
                toast(getString(R.string.player_filters_not_available_drm))
            }
            beginChildScreen()
            onChangeSettings.launch(intent)
        }
        // Long-press on the gear: the "secret menu" listing gestures that have no button.
        exoSettings.setOnLongClickListener {
            showPlayerShortcuts()
            true
        }

        // Speed Dialog
        val speeds = PlaybackSpeedSelection.values(PrefManager.getVal(PrefName.CursedSpeeds))
        val speedsName = speeds.map { "${it}x" }.toTypedArray()
        val savedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
        var curSpeed = savedIndex.coerceIn(0, speeds.size - 1)

        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speedsName, curSpeed) { i ->
                    PrefManager.setCustomVal("${media.id}_speed", i)
                    val speed = speeds.getOrNull(i) ?: 1f
                    curSpeed = i
                    playerManager.playbackEngine?.setPlaybackSpeed(speed)
                    discordThrottle.onPlaybackSpeedChanged()
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        // AutoPlay Interacted Tracking: opening the player counts as interaction,
        // every touch restarts the 1-hour idle window.
        markAutoplayInteraction()
        playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
            markAutoplayInteraction()
            false
        }

        // Progress Dialog & Initial Episode
        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        val showProgressDialog = if (PrefManager.getVal(PrefName.AskIndividualPlayer)) {
            PrefManager.getCustomVal("${media.id}_progressDialog", true)
        } else false
        val selectedEpKey = media.anime?.selectedEpisode ?: episodeArr.firstOrNull()
        val initialEp = selectedEpKey?.let { episodes[it] } ?: episodes.values.firstOrNull()
        if (initialEp == null) {
            startMainActivity(this)
            finish()
            return
        }

        if (!incognito && showProgressDialog && Anilist.userid != null &&
            !media.isUnlinkedPlayback() &&
            (if (media.isAdult) PrefManager.getVal(PrefName.UpdateForHPlayer) else true)
        ) {
            customAlertDialog().apply {
                setTitle(getString(R.string.auto_update, media.userPreferredName))
                setCancelable(false)
                setPosButton(R.string.yes) {
                    PrefManager.setCustomVal("${media.id}_progressDialog", false)
                    PrefManager.setCustomVal("${media.id}_save_progress", true)
                    model.setEpisode(initialEp, "invoke")
                }
                setNegButton(R.string.no) {
                    PrefManager.setCustomVal("${media.id}_progressDialog", false)
                    PrefManager.setCustomVal("${media.id}_save_progress", false)
                    toast(getString(R.string.reset_auto_update))
                    model.setEpisode(initialEp, "invoke")
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        } else {
            model.setEpisode(initialEp, "invoke")
        }
    }

    private fun resizeModeFor(mode: Int): ResizeMode = when (mode) {
        0 -> ResizeMode.FIT
        1 -> ResizeMode.ZOOM
        2 -> ResizeMode.STRETCH
        else -> ResizeMode.FIT
    }

    private fun applyResizeMode(mode: Int) {
        playerManager.playbackEngine?.setResizeMode(resizeModeFor(mode))
    }

    /** Settings > Subtitle language as a name ("Polish"). */
    private fun settingsSubtitleLanguage(): String =
        SubtitleLanguageMatch.settingsLanguage(PrefManager.getVal<Int>(PrefName.SubLanguage))

    private fun selectedEpisodeKey(): String? =
        if (this::episodes.isInitialized) episodes.getEpisodeKey(media.anime?.selectedEpisode) else null

    private fun savedEpisodePosition(key: String): Long {
        val alias = EpisodeResumeKeys.compatibleAlias(key, episodes.keys)
        val fallback = if (alias != null && alias != key) PrefManager.getCustomVal(resumePositionKey(media.id, alias), 0L) else 0L
        return PrefManager.getCustomVal(resumePositionKey(media.id, key), fallback)
    }

    private fun saveActivePosition() {
        playbackSession.savePosition { key, position ->
            PrefManager.setCustomVal(resumePositionKey(media.id, key), position)
            val alias = EpisodeResumeKeys.compatibleAlias(key, episodes.keys)
            if (alias != null && alias != key) PrefManager.setCustomVal(resumePositionKey(media.id, alias), position)
        }
    }

    private fun changeEpisode(index: Int) {
        if (!this::episodeArr.isInitialized || index !in episodeArr.indices) return
        val newEpKey = episodeArr[index]
        val targetEpisode = episodes[newEpKey] ?: return
        val prevEpKey = playbackSession.binding?.episodeKey ?: selectedEpisodeKey()
        changingServer = false
        discordThrottle.onOwnershipChange()
        // Publish/save A while A still owns the engine, then retire it BEFORE selecting B.
        progressManager.updateAniProgress()
        saveActivePosition()
        releasePlayer()
        // releasePlayer() leaves the previous episode's position in playbackPosition; the
        // next episode starts from its own saved position (0 when never watched). The
        // episode observer resets it too, but it skips while epChanging is set, and the
        // auto-play path then built the player at the previous episode's time.
        playbackPosition = savedEpisodePosition(newEpKey)
        prevEpKey?.let { subtitleManager.clearTransientSubtitleCache("${media.id}-$it") }
        aniSkipManager.resetForNewEpisode()
        currentEpisodeIndex = index
        progressManager.currentEpisodeIndex = index
        media.anime?.selectedEpisode = newEpKey
        model.setMedia(media)
        model.epChanged.postValue(false)
        model.setEpisode(targetEpisode, "change")
        model.onEpisodeClick(media, newEpKey, supportFragmentManager, false, prevEpKey ?: "", false)
    }

    private fun initPlayer() {
        gestureManager.checkNotch()
        aniSkipManager.resetForNewEpisode()
        val selEp = media.anime?.selectedEpisode ?: episodeArr.firstOrNull() ?: return
        media.anime!!.selectedEpisode = selEp
        // M7A G2: unlinked playback records no resume anchor and never
        // enters continue-watching — per-id prefs stay synthetic-free.
        if (!media.isUnlinkedPlayback()) {
            PrefManager.setCustomVal("${media.id}_current_ep", selEp)

            val list = (PrefManager.getNullableCustomVal("continueAnimeList", listOf<Int>(), List::class.java) as List<Int>).toMutableList()
            val updated = continueListWith(list, media.id)
            PrefManager.setCustomVal("continueAnimeList", updated)
        }

        val stoppedExtractor = extractor
        val stoppedVideo = video
        lifecycleScope.launch(Dispatchers.IO) { stoppedExtractor?.onVideoStopped(stoppedVideo) }

        // FINAL-PORT C4 correction (HOLD1-B): explicit selection first;
        // preferred wins only among playable extractors, then the first
        // playable nonpreferred choice. An empty preferred extractor can
        // never shadow playable video (or abort playback).
        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor }
            ?: preferredPlayableFallback(episode.extractors) ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: ext.videos.firstOrNull() ?: return

        val lang = settingsSubtitleLanguage()
        var savedSubLang: String? = PrefManager.getNullableCustomVal(legacySubLangKey(media.id, episode.number), null, String::class.java)
        if (savedSubLang?.startsWith("Online:") == true) {
            // The per-episode store is now the source of
            // truth for online choices; legacy keys migrate away.
            PrefManager.setCustomVal(legacySubLangKey(media.id, episode.number), null)
            savedSubLang = null
        }
        val intentSubtitle: ani.dantotsu.parsers.Subtitle? = intent.getSerialized("subtitle")
        // A validated
        // persisted record suppresses legacy selection here; the actual
        // attach happens after the engine exists, so a
        // restore is never reported without being attached-or-scheduled.
        // Explicit intent subtitle, Off, or embedded/local choice disables
        // restore entirely.
        val mayRestoreSaved = intentSubtitle == null &&
            savedSubLang != "None" &&
            savedSubLang?.startsWith("[Local]") != true &&
            savedSubLang?.startsWith("Embedded:") != true
        val pendingOnlineRestore = mayRestoreSaved &&
            subtitleManager.peekSavedOnlineSubtitle(media.id, episode.number) != null
        subtitle = intentSubtitle
            ?: if (pendingOnlineRestore) {
                null
            } else when {
                savedSubLang == null -> when (episode.selectedSubtitle) {
                    null, -1 -> SubtitleLanguageMatch.pick(ext.subtitles, lang) { it.language }
                    else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                }
                savedSubLang == "None" -> null
                savedSubLang.startsWith("Online:") -> null
                savedSubLang.startsWith("[Local]") -> null
                savedSubLang.startsWith("Embedded:") -> null
                else -> ext.subtitles.find { it.language == savedSubLang }
            }

        hasExtSubtitles = ext.subtitles.isNotEmpty()
        if (subtitle == null && !pendingOnlineRestore && hasExtSubtitles && savedSubLang != "None" &&
            savedSubLang?.startsWith("Online:") != true &&
            savedSubLang?.startsWith("[Local]") != true &&
            savedSubLang?.startsWith("Embedded:") != true
        ) {
            subtitle = SubtitleLanguageMatch.pick(ext.subtitles, lang) { it.language }
        }
        subtitleManager.initialSubtitleLabel = subtitle?.language ?: lang
        if (subtitle != null) {
            PrefManager.setCustomVal(legacySubLangKey(media.id, episode.number), subtitle!!.language)
            subtitleManager.setActiveServerSubtitle(subtitle)
        }

        exoSource.setOnClickListener { sourceClick() }

        if (isOnline(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (media.idIMDB == null) media.idIMDB = IdMappers.getImdbId(media.id)
                    val selectedEpisodeStr = media.anime?.selectedEpisode ?: "1"
                    val epObj = if (this@ExoplayerView::episode.isInitialized) episode else media.anime?.episodes?.getEpisode(selectedEpisodeStr)
                    val episodeNum = MediaNameAdapter.findEpisodeNumber(epObj?.number ?: selectedEpisodeStr)?.toInt()
                        ?: epObj?.number?.filter { it.isDigit() }?.toIntOrNull()
                        ?: selectedEpisodeStr.toIntOrNull()
                        ?: 1
                    EpisodeMapper.mapEpisode(media, episodeNum, epObj)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        exoSubtitle.isVisible = true
        exoSubtitle.setOnClickListener { subClick() }

        val playingVideo = video
        lifecycleScope.launch(Dispatchers.IO) { ext.onVideoPlayed(playingVideo) }

        if (ext.server.offline) {
            val titleName = ext.server.name.split("/").first()
            val episodeName = ext.server.name.split("/").last()
            val directory = ani.dantotsu.download.DownloadsManager.getSubDirectory(this, ani.dantotsu.media.MediaType.ANIME, false, titleName, episodeName)
            if (directory != null) {
                val file = directory.listFiles()?.firstOrNull {
                    it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".") &&
                    (it.name?.endsWith(".mp4", ignoreCase = true) == true ||
                     it.name?.endsWith(".mkv", ignoreCase = true) == true ||
                     it.name?.endsWith(".webm", ignoreCase = true) == true ||
                     it.name?.endsWith(".ts", ignoreCase = true) == true ||
                     it.type?.startsWith("video/") == true)
                } ?: directory.listFiles()?.firstOrNull {
                    it.isFile && !it.name.orEmpty().contains("subtitle", ignoreCase = true) && !it.name.orEmpty().startsWith(".")
                }
                if (file != null) {
                    video?.file?.url = file.uri.toString()
                }
            }
        }
        castManager.setupCastButton(
            customCastButton, media, video, subtitle, hasExtSubtitles,
            episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        )

        val currentVideo = video
        val relay = currentVideo?.let { TorrentRelayLink.parse(it.file.url) }
        if (currentVideo != null && (relay != null || currentVideo.file.url.startsWith("magnet:") || currentVideo.file.url.endsWith(".torrent"))) {
            val torrentManager = Injekt.get<ani.dantotsu.torrent.TorrentServerManager>()
            if (torrentManager.isAvailable()) {
                // A relay link (set on this Video by the buffering dialog or the manual
                // torrent sheet) plays only while its torrent is in the session; it may
                // have been removed since, so the torrent is added again from its source.
                val url = relay?.let { torrentManager.sourceFor(it.hash) } ?: currentVideo.file.url
                val index = relay?.index ?: ani.dantotsu.torrent.TorrentLinks.fileIndex(url)
                val hash = relay?.hash ?: try {
                    if (url.startsWith("magnet:")) torrentManager.parseMagnetHash(url) else ""
                } catch (_: Exception) { "" }

                // Warmup in background
                torrentManager.prebufferTorrent(url, index, currentVideo.quality.toString())

                // Played through a copy: the episode's Video keeps its magnet, so a later
                // play of it (replay, re-pick, copy link) still has the real source.
                if (hash.isNotBlank()) {
                    video = currentVideo.copy(file = currentVideo.file.copy(url = torrentManager.getLink(hash, index)))
                }
            }
        }

        buildMpvPlayer()
        // HOLD2 blocker 1: attach the pending restore only now that the
        // engine exists. Same Main-thread section as the peek — no
        // suspension between, so no interleave; the URL fallback path
        // captures a fresh generation inside applyOnlineSubtitleUrl.
        if (pendingOnlineRestore) {
            subtitleManager.restoreSavedOnlineSubtitle(media.id, episode.number)
        }
        // Smart-download hook. Fires per
        // playback start; the trigger itself is duplicate-guarded, so
        // re-entry (server changes, episode moves) is harmless.
        checkSmartDownloadAnime()
    }

    private fun buildMpvPlayer() {
        playerErrorRetryCount = 0
        hideSystemBars()

        val selEp = media.anime?.selectedEpisode
        val cleanEp = selEp?.let { EpisodeResumeKeys.compatibleAlias(it, episodes.keys) }
        val savedMax = selEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }
            ?.takeIf { it != Long.MAX_VALUE }
            ?: (cleanEp?.let { PrefManager.getCustomVal("${media.id}_${cleanEp}_max", Long.MAX_VALUE) }?.takeIf { it != Long.MAX_VALUE })

        val savedPosition = ani.dantotsu.media.anime.player.ResumeStart.startPosition(playbackPosition, savedMax)
        playbackPosition = savedPosition

        val speeds = PlaybackSpeedSelection.values(PrefManager.getVal(PrefName.CursedSpeeds))
        val savedSpeedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
            .coerceIn(0, speeds.size - 1)
        val currentSpeed = speeds[savedSpeedIndex]

        // HOLD3 D4 + HOLD4 B5 fail-closed: explicit DRM signals (valid or
        // malformed) never reach MPV; only the total absence of DRM means
        // clear content.
        val drmDecision = ani.dantotsu.media.anime.player.DrmBackendPolicy.decideForVideo(
            video?.drm,
            video?.drmParseError
        ) { uuid ->
            runCatching { android.media.MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)
        }
        if (drmDecision is ani.dantotsu.media.anime.player.DrmBackendPolicy.BackendDecision.Unsupported) {
            // HOLD3 D4 fail-closed: an explicitly DRM-identified stream with
            // an unsupported/malformed configuration is NOT clear content —
            // never attempt MPV. Explicit error → toast + source picker.
            onPlayerError(
                PlaybackError(
                    category = ErrorCategory.SOURCE,
                    message = "DRM unavailable: ${drmDecision.reason}",
                    fatal = true,
                    retryable = false
                )
            )
            return
        }
        val engine = playerManager.initPlayer(
            savedPosition,
            currentSpeed,
            object : PlaybackListener {},
            // DRM-tagged streams route to the narrow
            // Media3 DRM backend; everything else stays on MPV.
            preferDrmBackend = drmDecision is ani.dantotsu.media.anime.player.DrmBackendPolicy.BackendDecision.DrmReady
        )
        val active = playbackSession.bind(selEp ?: episode.number, engine)
        engine.addListener(playbackSession.listener(active, { selectedEpisodeKey() }, this))
        videoInfo.text = video?.quality?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", true) } ?: ""

        val episodeDisplayName = episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        val savedSubLang = PrefManager.getNullableCustomVal(legacySubLangKey(media.id, episode.number), null, String::class.java)
        val request = playerManager.buildPlaybackRequest(
            video = video!!,
            subtitles = extractor?.subtitles,
            startPositionMs = savedPosition,
            title = episodeDisplayName,
            seriesTitle = media.userPreferredName,
            episodeNumber = episode.number,
            coverUrl = media.cover,
            preferredSubLang = savedSubLang,
            fallbackSubLang = settingsSubtitleLanguage(),
            embedUrl = extractor?.server?.embed?.url,
            audioTracks = extractor?.audioTracks ?: emptyList(),
            // Beta03: validated extension mpv per-file options (AniKoto etc.).
            mpvFileOptions = extractor?.mpvFileOptions.orEmpty()
        )
        // Beta07 styling policy: classify playback ORIGIN (extension stream
        // vs torrent vs download vs local) and apply the resolved style
        // before loadMedia. extractor.server.offline marks download playback.
        val playbackOrigin = ani.dantotsu.media.anime.player.originFromSourceClass(
            request.sourceClass,
            isDownload = extractor?.server?.offline == true
        )
        subtitleManager.notePlaybackOrigin(playbackOrigin)
        subtitleManager.applySubtitlePreferences(playbackOrigin)
        engine.loadMedia(request)

        // While casting, the new episode goes to the TV and the phone stays paused.
        val sentToCast = castManager.updateCurrentMedia(
            buildCastItem(episodeDisplayName), engine, video, playerManager.playbackCoordinator,
            startPositionMs = savedPosition,
        )

        // Request audio focus and start playback through the coordinator.
        // Without this, MPV auto-plays (pause=no default) but the coordinator
        // never requests audio focus, leaving the session unprotected and
        // potentially killed by Android's audio system.
        if (!sentToCast) playerManager.playbackCoordinator?.play() ?: engine.play()

        applyResizeMode(isFullscreen)
        progressManager.updateWidgetState(isExiting = false)
    }

    /**
     * The item internal cast sends to the receiver: the stream and, when the receiver can
     * show its format (WebVTT, TTML), the active server subtitle.
     */
    private fun buildCastItem(episodeDisplayName: String): androidx.media3.common.MediaItem {
        val sub = subtitle
        val subConfig = sub?.file?.url?.takeIf { it.isNotBlank() }?.let { url ->
            val mime = ani.dantotsu.media.anime.cast.CastSubtitles.mimeFor(sub.type.name, url) ?: return@let null
            androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mime)
                .setLabel(sub.language)
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        return androidx.media3.common.MediaItem.Builder()
            .setUri(video!!.file.url)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("${media.userPreferredName} : $episodeDisplayName")
                    .build()
            )
            .setSubtitleConfigurations(listOfNotNull(subConfig))
            .build()
    }

    private fun releasePlayer() {
        playerManager.playbackEngine?.let { engine ->
            isPlayerPlaying = engine.isPlaying
            playbackPosition = engine.positionMs
        }
        playbackSession.invalidate()
        gestureManager.stopTemporarySpeed()
        progressManager.resetForNewEpisode()
        aniSkipManager.stopTracking()
        progressManager.updateWidgetState(isExiting = true)
        discordManager.clear()
        playerManager.releasePlayer()
    }

    private fun sourceClick() {
        changingServer = true

        // The list is forced for this dialog only; the remembered release stays for the
        // next episodes (it used to be saved as null here, which turned auto-play off).
        playerManager.playbackEngine?.let { engine ->
            saveActivePosition()
            engine.pause()
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val epNum = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
        model.onEpisodeClick(
            media,
            epNum,
            this.supportFragmentManager,
            launch = false,
            forcePicker = true,
        )
    }

    private fun subClick() {
        saveActivePosition()
        media.selected?.let { model.saveSelected(media.id, it) }
        val dialog = SubtitleDialogFragment()
        dialog.show(supportFragmentManager, "dialog")
    }

    // Public contract methods
    fun requestLocalSubtitle() {
        beginChildScreen()
        getContent.launch(arrayOf("*/*"))
    }

    fun applyLocalSubtitle(uri: Uri) {
        subtitleManager.applyLocalSubtitle(uri, media)
    }

    fun reApplyLocalSubtitle(uriString: String) {
        applyLocalSubtitle(Uri.parse(uriString))
    }

    fun applyOnlineSubtitle(subtitle: StremioSub, displayName: String = subtitle.lang, provider: String = "OpenSubtitles") {
        subtitleManager.applyOnlineSubtitle(subtitle, displayName, provider)
    }

    fun applyWyzieSubtitle(subtitle: WyzieSub) {
        subtitleManager.applyWyzieSubtitle(subtitle)
    }

    fun applySubSourceSubtitle(sub: SubSourceSub) {
        subtitleManager.applySubSourceSubtitle(sub)
    }

    fun applyOpenSubRestSubtitle(item: OpenSubRestItem) {
        subtitleManager.applyOpenSubRestSubtitle(item)
    }

    fun onSelectTrack(track: PlayerTrack?, type: TrackType) {
        val engine = playerManager.playbackEngine ?: return
        when (type) {
            TrackType.AUDIO -> {
                engine.selectAudioTrack(track?.id)
                snackString("Audio: ${track?.name ?: "Off"}", this)
            }
            TrackType.SUBTITLE -> {
                engine.selectSubtitleTrack(track?.id)
                snackString("Subtitle: ${track?.name ?: "Off"}", this)
            }
            TrackType.VIDEO -> {}
        }
    }

    // PlaybackListener callbacks
    override fun onPositionChanged(positionMs: Long, durationMs: Long) {
        timeline.setPosition(positionMs)
        timeline.setDuration(durationMs)
        exoPositionText.text = formatTime(positionMs)
        exoDurationText.text = formatTime(durationMs)
        // Discontinuity-based Discord re-publish: only fires on real seeks, not during normal playback.
        val currentSpeed = playerManager.playbackEngine?.playbackSpeed ?: 1.0f
        if (this::episode.isInitialized && isPlayerPlaying && discordThrottle.onPlaybackPositionChanged(positionMs, currentSpeed)) {
            discordManager.updatePresence(media, episode, positionMs, durationMs, true)
        }
        if (!themeMode) updateNowPlaying(positionMs)
    }

    /** Looks up the show's themes (cache first) for the now-playing chip; no lookup offline. */
    private fun setupNowPlaying() {
        val view = playerView.findViewById<TextView>(R.id.exo_now_playing)
        nowPlayingView = view
        view.setOnClickListener {
            val track = nowPlayingTrack ?: return@setOnClickListener
            val fav = ThemeFavourites.toggle(media.id, media.userPreferredName, track)
            if (fav) toast(getString(R.string.theme_favourite_added))
            bindNowPlaying(track)
        }
        if (media.anime == null || PrefManager.getVal<Boolean>(PrefName.OfflineMode)) return
        lifecycleScope.launch {
            val result = runCatching {
                ani.dantotsu.animethemes.AnimeThemes.repository.lookup(media.id, media.idMAL)
            }.getOrNull()
            val data = (result as? ani.dantotsu.animethemes.ThemesLookup.Found)?.data ?: return@launch
            nowPlayingChoices = ThemeQueue.choices(data, ani.dantotsu.animethemes.MediaThemesFragment.themePrefs())
                .map { ThemeQueue.withShow(it, media.id, media.userPreferredName) }
        }
    }

    private fun updateNowPlaying(positionMs: Long) {
        val view = nowPlayingView ?: return
        val ending = ani.dantotsu.animethemes.NowPlaying.kindAt(model.timeStamps.value, positionMs / 1000.0)
        val epNum = ani.dantotsu.animethemes.NowPlaying.episodeNumber(
            if (this::episode.isInitialized) episode.number else media.anime?.selectedEpisode
        )
        val track = if (ending == null || epNum == null || nowPlayingChoices.isEmpty()) null
        else ThemeQueue.nowPlaying(nowPlayingChoices, ending, epNum)
        if (track?.themeId != nowPlayingTrack?.themeId || (track == null) != (view.visibility != View.VISIBLE)) {
            nowPlayingTrack = track
            if (track == null) view.visibility = View.GONE else bindNowPlaying(track)
        }
    }

    private fun bindNowPlaying(track: ThemeTrack) {
        val view = nowPlayingView ?: return
        val fav = ThemeFavourites.isFavourite(track.themeId)
        view.text = ani.dantotsu.animethemes.NowPlaying.label(track)
        // Same hearts as the theme lists: tapping the chip toggles the favourite.
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (fav) R.drawable.ic_round_favorite_24 else R.drawable.ic_round_favorite_border_24, 0, 0, 0,
        )
        view.contentDescription = view.text.toString() + ", " +
            getString(if (fav) R.string.theme_favourite_remove else R.string.theme_favourite_add)
        view.visibility = View.VISIBLE
    }

    /** The engine's current tracks, for sheets that are recreated without arguments. */
    var currentTracks: List<PlayerTrack> = emptyList()
        private set

    override fun onTracksChanged(tracks: List<PlayerTrack>) {
        currentTracks = tracks
        val audioTracks = tracks.filter { it.type == TrackType.AUDIO }
        val subTracks = tracks.filter { it.type == TrackType.SUBTITLE }

        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            TrackGroupDialogFragment.newInstance(TrackType.AUDIO)
                .show(supportFragmentManager, "dialog")
        }

        exoSubtitle.isVisible = true
        exoSubtitle.setOnClickListener {
            subClick()
        }
    }

    override fun onDurationKnown(generationId: Long, durationMs: Long) {
        if (progressManager.episodeLength <= 0f && durationMs > 0L) {
            progressManager.episodeLength = durationMs.toFloat()
            PrefManager.setCustomVal("${media.id}_${media.anime?.selectedEpisode}_max", durationMs)
        }
        checkAndLoadTimestamps()
    }

    override fun onPlaybackStateChanged(state: PlaybackState) {
        when (state) {
            is PlaybackState.Ready -> {
                exoBufferingIndicator.visibility = View.GONE
                isBuffering = false
                val engine = playerManager.playbackEngine
                if (engine != null && progressManager.episodeLength <= 0f && engine.durationMs > 0L) {
                    progressManager.episodeLength = engine.durationMs.toFloat()
                    PrefManager.setCustomVal("${media.id}_${media.anime?.selectedEpisode}_max", engine.durationMs)
                }
                checkAndLoadTimestamps()
                autoplayEndGate.rearm()
            }
            is PlaybackState.Buffering -> {
                exoBufferingIndicator.visibility = View.VISIBLE
                isBuffering = true
                autoplayEndGate.rearm()
            }
            is PlaybackState.Ended -> {
                exoBufferingIndicator.visibility = View.GONE
                isBuffering = false
                val engine = playerManager.playbackEngine
                val dur = state.durationMs.takeIf { it > 0L } ?: (engine?.durationMs ?: 0L)
                val pos = state.positionMs.takeIf { it > 0L } ?: (engine?.positionMs ?: 0L)
                val watchThreshold = PrefManager.getVal<Float>(PrefName.WatchPercentage)

                val watchThresholdMet = dur > 0L && (pos >= (dur - 5000L) || (pos.toFloat() / dur.toFloat()) >= watchThreshold)
                val nearPhysicalEnd = dur > 0L && pos >= (dur - 5000L)

                if (watchThresholdMet) {
                    progressManager.updateAniProgress(forceComplete = true)
                }
                if (nearPhysicalEnd && sleepTimer.onEpisodeEnded()) {
                    exoSleepTimer.alpha = 1f
                    toast(getString(R.string.sleep_timer_stopped))
                    return
                }
                val decision = AutoplayPolicy.decide(
                    autoPlayOn = PrefManager.getVal<Boolean>(PrefName.AutoPlay),
                    nearPhysicalEnd = nearPhysicalEnd,
                    interactedRecently = interacted
                )
                if (decision != AutoplayPolicy.Decision.NONE && autoplayEndGate.tryConsume()) {
                    if (decision == AutoplayPolicy.Decision.ADVANCE) {
                        progressManager.nextEpisode { i ->
                            changeEpisode(currentEpisodeIndex + i)
                        }
                    } else {
                        toast(getString(R.string.autoplay_cancelled))
                    }
                }
            }
            is PlaybackState.Error -> {
                exoBufferingIndicator.visibility = View.GONE
                isBuffering = false
                onPlayerError(state.error)
            }
            is PlaybackState.Idle -> {
                exoBufferingIndicator.visibility = View.GONE
                isBuffering = false
            }
        }
    }

    private fun checkAndLoadTimestamps() {
        val engine = playerManager.playbackEngine ?: return
        if (!aniSkipManager.isTimeStampsLoaded && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
            val dur = engine.durationMs
            val extTimestamps = extractor?.server?.video?.timestamps ?: emptyList()
            // No duration gate here. Live AniSkip still
            // needs duration, but CSV/IntroDB bounded ranges may resolve
            // without it; the ViewModel dispatches per provider.
            val epString = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
            val epNum = Regex("""\d+""").find(epString)?.value?.toIntOrNull()
                ?: epString.trim().toIntOrNull()
                ?: 1
            val epObj = if (this::episode.isInitialized) episode else null
            // One lookup per episode, and one more once the duration is known.
            val requestKey = "$epString:${dur > 0L}"
            if (aniSkipManager.timeStampsRequestKey == requestKey) return
            aniSkipManager.timeStampsRequestKey = requestKey
            lifecycleScope.launch(Dispatchers.IO) {
                model.loadTimeStamps(
                    media.idMAL,
                    epNum,
                    if (dur > 0L) dur / 1000L else 0L,
                    PrefManager.getVal(PrefName.UseProxyForTimeStamps),
                    extTimestamps,
                    episode = epObj
                )
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            updatePipActions(isPlaying)
            playerView.keepScreenOn = isPlaying
            (exoPlay.drawable as? Animatable)?.start()
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (this::episode.isInitialized && discordThrottle.onPlayPause()) {
                discordManager.updatePresence(
                    media, episode,
                    playerManager.playbackEngine?.positionMs ?: 0L,
                    playerManager.playbackEngine?.durationMs ?: 0L,
                    isPlaying
                )
            }
            updatePipParams()
        }
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            aspectRatio = Rational(width, height)
            videoInfo.text = getString(R.string.video_quality, height)
            updatePipParams()
        }
    }

    private fun getClampedAspectRatio(ratio: Rational?): Rational {
        if (ratio == null || ratio.numerator <= 0 || ratio.denominator <= 0 || ratio.isZero || ratio.isNaN || ratio.isInfinite) {
            return Rational(16, 9)
        }
        val floatVal = ratio.toFloat()
        val minVal = 1000f / 2390f
        val maxVal = 2390f / 1000f
        return when {
            floatVal < minVal -> Rational(1000, 2390)
            floatVal > maxVal -> Rational(2390, 1000)
            else -> ratio
        }
    }

    /**
     * Picture-in-picture buttons (play/pause, seek, next).
     * Actions route strictly through stable seams — PlaybackCoordinator /
     * PlaybackEngine for transport, [changeEpisode] for episode moves. No
     * Exo internals, no Media3 session changes.
     */
    private var pipReceiver: BroadcastReceiver? = null

    private fun pipActionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildPipActions(isPlaying: Boolean): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        // One configured distance feeds both labels and seeks —
        // UI and behavior cannot diverge.
        val seekSec = pipSeekSeconds(PrefManager.getVal(PrefName.SeekTime))
        val rewindTitle = getString(R.string.pip_rewind, seekSec)
        val forwardTitle = getString(R.string.pip_forward, seekSec)

        val prevEpAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_skip_previous_24),
            "Previous Episode",
            "Previous Episode",
            pipActionIntent(ACTION_PIP_PREV_EP, 10)
        )
        val rewindAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_fast_rewind_24),
            rewindTitle,
            rewindTitle,
            pipActionIntent(ACTION_PIP_REWIND, 1)
        )
        val playPauseIcon =
            if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = RemoteAction(
            Icon.createWithResource(this, playPauseIcon),
            playPauseTitle,
            playPauseTitle,
            pipActionIntent(ACTION_PIP_PLAY_PAUSE, 2)
        )
        val skipAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_fast_forward_24),
            forwardTitle,
            forwardTitle,
            pipActionIntent(ACTION_PIP_SKIP, 3)
        )
        val nextEpAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_round_skip_next_24),
            "Next Episode",
            "Next Episode",
            pipActionIntent(ACTION_PIP_NEXT_EP, 20)
        )

        val maxActions = maxNumPictureInPictureActions
        return if (maxActions >= 5) {
            listOf(prevEpAction, rewindAction, playPauseAction, skipAction, nextEpAction)
        } else {
            listOf(rewindAction, playPauseAction, skipAction)
        }
    }

    private fun updatePipActions(isPlaying: Boolean = isPlayerPlaying) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(getClampedAspectRatio(aspectRatio))
                .setActions(buildPipActions(isPlaying))
                .build()
            setPictureInPictureParams(params)
        }
    }

    private fun registerPipReceiver() {
        if (pipReceiver != null) return
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!this@ExoplayerView::playerManager.isInitialized ||
                    !playerManager.isInitialized
                ) return
                val engine = playerManager.playbackEngine
                val coordinator = playerManager.playbackCoordinator
                when (intent?.action) {
                    ACTION_PIP_PREV_EP -> {
                        if (themeMode) playTheme(themeIndex - 1)
                        else if (currentEpisodeIndex > 0) changeEpisode(currentEpisodeIndex - 1)
                    }
                    ACTION_PIP_REWIND -> {
                        engine?.seekRelative(-pipSeekMs(PrefManager.getVal(PrefName.SeekTime)))
                    }
                    ACTION_PIP_PLAY_PAUSE -> {
                        // togglePlayPause posts async to the engine dispatcher,
                        // so isPlaying still reads pre-toggle here: the
                        // inversion predicts the post-toggle state and
                        // onIsPlayingChanged reconciles authoritatively.
                        coordinator?.togglePlayPause()
                        updatePipActions(!(engine?.isPlaying == true))
                    }
                    ACTION_PIP_SKIP -> {
                        engine?.seekRelative(pipSeekMs(PrefManager.getVal(PrefName.SeekTime)))
                    }
                    ACTION_PIP_NEXT_EP -> {
                        if (themeMode) playTheme(themeIndex + 1)
                        else if (this@ExoplayerView::progressManager.isInitialized) {
                            progressManager.nextEpisode { i ->
                                progressManager.updateAniProgress()
                                changeEpisode(currentEpisodeIndex + i)
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_PREV_EP)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_SKIP)
            addAction(ACTION_PIP_NEXT_EP)
        }
        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterPipReceiver() {
        pipReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            pipReceiver = null
        }
    }

    /** Before opening one of the player's own screens: no PiP for this leave. */
    private fun beginChildScreen() {
        launchingChildScreen = true
        updatePipParams()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val clampedRatio = getClampedAspectRatio(aspectRatio)
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(clampedRatio)
                    .setActions(buildPipActions(isPlayerPlaying))

                val surfaceView = playerView.surfaceView
                if (surfaceView != null && surfaceView.isLaidOut) {
                    val rect = android.graphics.Rect()
                    if (surfaceView.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
                        builder.setSourceRectHint(rect)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val liveIsPlaying = if (this::playerManager.isInitialized && playerManager.isInitialized) {
                        playerManager.playbackEngine?.isPlaying == true
                    } else false
                    builder.setAutoEnterEnabled(
                        liveIsPlaying && pipEnabled && !launchingChildScreen && PrefManager.getVal(PrefName.Pip)
                    )
                }

                setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    /**
     * While watching a downloaded
     * episode, queue the next one. OFF by default; duplicate-guarded
     * (downloaded check, in-progress check, queue check); enqueues through
     * [Helper.startAnimeDownloadService] so Wi-Fi gating, duplicate guards
     * and ownership semantics are inherited, never duplicated.
     */
    private fun checkSmartDownloadAnime() {
        if (!PrefManager.getVal<Boolean>(PrefName.SmartDownloadAnime)) return
        if (!this::episode.isInitialized || !this::episodeArr.isInitialized) return
        val downloadsManager = Injekt.get<DownloadsManager>()
        val isOffline = downloadsManager.queryDownload(media.mainName(), episode.number, MediaType.ANIME)
        if (!isOffline) return

        val currentIdx = episodeArr.indexOfFirst { key ->
            episodes[key]?.number == episode.number
        }
        if (currentIdx == -1 || currentIdx + 1 >= episodeArr.size) return
        val nextEpKey = episodeArr[currentIdx + 1]
        val nextEpNumber = episodes[nextEpKey]?.number ?: nextEpKey
        if (downloadsManager.queryDownload(media.mainName(), nextEpNumber, MediaType.ANIME)) return
        if (AnimeDownloader.isDownloading(media.id, nextEpNumber)) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nextEpisode = episodes[nextEpKey]
                    ?: media.anime?.episodes?.getEpisode(nextEpKey) ?: return@launch
                val sourceIndex = media.selected?.sourceIndex ?: return@launch
                val serverName = media.selected?.server ?: ""
                val success = if (serverName.isNotBlank()) {
                    model.loadEpisodeSingleVideo(nextEpisode, media.selected!!, false, serverName)
                } else false

                val extractorToUse = if (success) {
                    nextEpisode.extractors?.find { it.server.name == serverName }
                        ?: nextEpisode.extractors?.firstOrNull { it.isPreferred && it.videos.isNotEmpty() }
                } else {
                    model.loadEpisodeVideos(nextEpisode, sourceIndex, false)
                    preferredPlayableFallback(nextEpisode.extractors)
                } ?: return@launch

                val sourceName = model.watchSources?.get(sourceIndex)?.name
                val preferredResolutions = PrefManager.getPreferredDownloadResolutions(sourceName)
                val bestVideo = findBestVideoForDownload(extractorToUse.videos, preferredResolutions)
                    ?: return@launch

                withContext(Dispatchers.Main) {
                    Helper.startAnimeDownloadService(
                        this@ExoplayerView,
                        media.mainName(),
                        nextEpisode.number,
                        bestVideo,
                        sourceMedia = media,
                        episodeImage = nextEpisode.thumb?.url ?: media.banner ?: media.cover
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("SmartDownloadAnime error: ${e.message}")
            }
        }
    }

    fun onPlayerError(error: PlaybackError) {
        Logger.log("Playback error: category=${error.category}, message=${error.message}")
        if (error.retryable && playerErrorRetryCount < MAX_PLAYER_ERROR_RETRIES) {
            playerErrorRetryCount++
            val engine = playerManager.playbackEngine
            val req = engine?.currentRequest
            if (req != null) {
                val currentPos = engine.positionMs
                val retryPos = if (currentPos > 0L) currentPos else req.startPositionMs
                engine.loadMedia(req.copy(startPositionMs = retryPos))
            }
        } else {
            playerErrorRetryCount = 0
            toast("Playback Error: ${error.message}")
            // The picker is a dialog fragment; after onSaveInstanceState (player in the
            // background) showing it throws. Open it when the player is back instead.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                sourceClick()
            } else {
                sourcePickerPending = true
            }
        }
    }

    /** A playback error asked for the source picker while the player was not resumed. */
    private var sourcePickerPending = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        // Theme mode keeps playing in the background (as in onStop), so focus loss
        // (screen off, notification shade) must not pause it either.
        if (!themeMode && PrefManager.getVal(PrefName.FocusPause) && !epChanging && !inPip) {
            if (this::playerManager.isInitialized && playerManager.isInitialized) {
                playerManager.playbackCoordinator?.setLifecycleSuppressed(!hasFocus)
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    /**
     * Screenshot button (bottom right): mpv writes the current frame, subtitles included,
     * to a temp file, which is then copied into the gallery under Pictures/Editotsu.
     * Android 9 and older (no scoped storage) keep it in the app's own Pictures folder,
     * which needs no storage permission but doesn't show up in the gallery.
     * Names come from [ScreenshotName]: title, episode or theme, playback time.
     */
    private fun takePlayerScreenshot() {
        val engine = if (this::playerManager.isInitialized) playerManager.playbackEngine else null
        if (engine == null) {
            toast(getString(R.string.screenshot_not_ready))
            return
        }
        if (!engine.supportsScreenshot) {
            toast(getString(R.string.player_not_available_drm))
            return
        }
        val position = engine.positionMs
        val name = if (themeMode) {
            themeTracks.getOrNull(themeIndex).let { track ->
                // Radio mixes shows, so the show comes from the track when it has one.
                ani.dantotsu.media.anime.player.ScreenshotName.theme(
                    track?.showTitle?.ifBlank { null } ?: themeAnime,
                    track?.let { ThemeQueue.label(it) },
                    position,
                )
            }
        } else {
            ani.dantotsu.media.anime.player.ScreenshotName.episode(
                if (this::media.isInitialized) media.userPreferredName else null,
                if (this::episodeArr.isInitialized) episodeArr.getOrNull(currentEpisodeIndex) else null,
                position,
            )
        }
        val toGallery = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val dir = if (toGallery) java.io.File(cacheDir, "screenshots")
        else getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?.let { java.io.File(it, "Editotsu") }
        if (dir == null) {
            toast(getString(R.string.screenshot_failed))
            return
        }
        val file = java.io.File(dir, name)
        engine.captureScreenshot(file) { written ->
            if (isDestroyed) return@captureScreenshot
            if (!written) {
                toast(getString(R.string.screenshot_failed))
                return@captureScreenshot
            }
            if (!toGallery) {
                toast(getString(R.string.screenshot_saved_app, file.absolutePath))
                return@captureScreenshot
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val saved = copyScreenshotToGallery(file, name)
                file.delete()
                withContext(Dispatchers.Main) {
                    toast(getString(if (saved) R.string.screenshot_saved else R.string.screenshot_failed))
                }
            }
        }
    }

    /** Inserts [file] into MediaStore under Pictures/Editotsu; the gallery renames duplicates itself. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun copyScreenshotToGallery(file: java.io.File, name: String): Boolean {
        val resolver = contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Editotsu")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Logger.log("screenshot insert failed: ${e.message}")
            null
        } ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("no output stream")
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            Logger.log("screenshot copy failed: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            false
        }
    }

    /** Lists only the gestures that are switched on in player settings. */
    private fun showPlayerShortcuts() = ani.dantotsu.media.anime.player.PlayerShortcuts.show(this)

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                playerView.hideController()
                val clampedRatio = getClampedAspectRatio(aspectRatio)
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(clampedRatio)
                    .setActions(buildPipActions(isPlayerPlaying))
                val surfaceView = playerView.surfaceView
                if (surfaceView != null && surfaceView.isLaidOut) {
                    val rect = android.graphics.Rect()
                    if (surfaceView.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
                        builder.setSourceRectHint(rect)
                    }
                }
                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                Logger.log("enterPipMode failed: ${e.message}")
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            try {
                playerView.hideController()
                enterPictureInPictureMode()
            } catch (e: Exception) {
                Logger.log("enterPipMode legacy failed: ${e.message}")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (launchingChildScreen) return
        if (pipEnabled && PrefManager.getVal(PrefName.Pip)) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureUiStateChanged(pipState: android.app.PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35) { // Android 15+ (VanillaIceCream)
            if (pipState.isTransitioningToPip) {
                playerView.hideController()
                if (this::aniSkipManager.isInitialized) {
                    aniSkipManager.hideSkipButtons()
                }
            }
        }
    }

    // configChanges includes orientation, so rotation does not recreate the
    // activity: re-read the cutout and redo the notch margins here, otherwise
    // the portrait margins from start-up stay until the controls are toggled.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!this::gestureManager.isInitialized) return
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val rect = window.decorView.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()
                if (rect != null) {
                    gestureManager.updateNotchHeight(min(rect.width(), rect.height()))
                    return@post
                }
            }
            gestureManager.checkNotch()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            if (this::playerManager.isInitialized && playerManager.isInitialized) {
                playerManager.playbackCoordinator?.setLifecycleSuppressed(false)
            }
            playerView.hideController()
            registerPipReceiver()
            updatePipActions(isPlayerPlaying)
            // Auto-skip and progress tracking keep running in PiP; only the skip button
            // is kept off the small window.
            if (this::aniSkipManager.isInitialized) {
                aniSkipManager.hideSkipButtons()
                aniSkipManager.buttonsSuppressed = true
            }
        } else {
            if (this::aniSkipManager.isInitialized) aniSkipManager.buttonsSuppressed = false
            unregisterPipReceiver()
            hideSystemBars()
            if (!themeMode && this::aniSkipManager.isInitialized && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
                aniSkipManager.startTracking()
            }
            if (!themeMode && this::progressManager.isInitialized) {
                progressManager.startTracking()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (themeMode) return
        if (this::aniSkipManager.isInitialized) {
            aniSkipManager.startTracking()
        }
        if (this::progressManager.isInitialized) {
            progressManager.startTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        launchingChildScreen = false
        if (sourcePickerPending) {
            sourcePickerPending = false
            if (!themeMode) sourceClick()
        }
        hideSystemBars()
        if (this::playerManager.isInitialized && playerManager.isInitialized) {
            playerManager.playbackCoordinator?.setLifecycleSuppressed(false)
        }
        updatePipParams()
    }

    override fun onPause() {
        super.onPause()
        // Entering PiP pauses the activity, but playback goes on in the small window.
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (inPip) return
        if (this::aniSkipManager.isInitialized) {
            aniSkipManager.stopTracking()
        }
        if (this::progressManager.isInitialized) {
            progressManager.stopTracking()
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        // Closing the PiP window skips onPause; the trackers stop here (onStart restarts them).
        if (!inPip) {
            if (this::aniSkipManager.isInitialized) aniSkipManager.stopTracking()
            if (this::progressManager.isInitialized) progressManager.stopTracking()
        }
        // Theme mode keeps playing in the background (AnimePlayerService); episodes pause.
        if (!themeMode && this::playerManager.isInitialized && playerManager.isInitialized && !inPip) {
            playerManager.playbackCoordinator?.setLifecycleSuppressed(true)
        }
        if (!themeMode && this::playerManager.isInitialized && playerManager.isInitialized) {
            saveActivePosition()
            if (this::progressManager.isInitialized) {
                progressManager.updateAniProgress()
            }
        }
    }


    @SuppressLint("UnsafeIntentLaunch")
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishAndRemoveTask()
        startActivity(intent)
    }

    private fun markAutoplayInteraction() {
        interacted = true
        autoplayIdleHandler.removeCallbacks(autoplayIdleReset)
        autoplayIdleHandler.postDelayed(autoplayIdleReset, AutoplayPolicy.IDLE_TIMEOUT_MS)
    }

    private fun showSleepTimerDialog() {
        val choices = SleepTimer.CHOICES_MINUTES
        val labels = choices.map {
            when {
                it == 0 -> getString(R.string.sleep_timer_off)
                it < 0 -> getString(R.string.sleep_timer_end_of_episode)
                else -> getString(R.string.sleep_timer_minutes, it)
            }
        }.toTypedArray()
        val current = when (sleepTimer.mode) {
            SleepTimer.Mode.Off -> 0
            SleepTimer.Mode.EndOfEpisode -> choices.indexOf(-1)
            is SleepTimer.Mode.At -> -1
        }
        customAlertDialog().apply {
            setTitle(getString(R.string.sleep_timer))
            singleChoiceItems(labels, current) { i ->
                val minutes = choices[i]
                sleepTimerHandler.removeCallbacks(sleepTimerFire)
                when {
                    minutes == 0 -> {
                        sleepTimer.cancel()
                        toast(getString(R.string.sleep_timer_cancelled))
                    }
                    minutes < 0 -> {
                        sleepTimer.setEndOfEpisode()
                        toast(getString(R.string.sleep_timer_set_episode))
                    }
                    else -> {
                        sleepTimer.setMinutes(minutes, java.lang.System.currentTimeMillis())
                        sleepTimerHandler.postDelayed(sleepTimerFire, minutes * 60_000L)
                        toast(getString(R.string.sleep_timer_set_minutes, minutes))
                    }
                }
                exoSleepTimer.alpha = if (sleepTimer.mode == SleepTimer.Mode.Off) 1f else 0.6f
            }
            show()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            // Leaving the player: torrents nothing streams any more are removed after a
            // short grace (an immediate reopen keeps its torrent), instead of downloading
            // and uploading in the background service until the app is swiped away.
            val torrentManager = Injekt.get<ani.dantotsu.torrent.TorrentServerManager>()
            if (torrentManager.isRunning()) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(25_000L)
                    torrentManager.removeIdleTorrents()
                }
            }
        }
        sleepTimerHandler.removeCallbacks(sleepTimerFire)
        autoplayIdleHandler.removeCallbacks(autoplayIdleReset)
        unregisterPipReceiver()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        orientationListener?.disable()
        val stoppedExtractor = extractor
        val stoppedVideo = video
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                stoppedExtractor?.onVideoStopped(stoppedVideo)
            } catch (_: Exception) {}
        }
        themeJob?.cancel()
        themeRadioJob?.cancel()
        if (themeMode) {
            AnimePlayerService.stop(this)
            if (this::playerManager.isInitialized && playerManager.isInitialized) playerManager.releasePlayer()
        } else if (this::playerManager.isInitialized && playerManager.isInitialized) {
            if (this::progressManager.isInitialized) {
                progressManager.updateAniProgress()
            }
            val episodeId = "${if (this::media.isInitialized) media.id else ""}-${if (this::media.isInitialized) media.anime?.selectedEpisode ?: "" else ""}"
            if (this::subtitleManager.isInitialized) {
                subtitleManager.clearTransientSubtitleCache(episodeId)
            }
            releasePlayer()
        }
        if (this::castManager.isInitialized) {
            castManager.release()
        }
        super.onDestroy()
    }

    private fun setupThemeMode() {
        themeTracks = intent.getStringExtra(EXTRA_THEMES)?.let { json ->
            runCatching { Mapper.json.decodeFromString(ListSerializer(ThemeTrack.serializer()), json) }.getOrNull()
        }.orEmpty()
        if (themeTracks.isEmpty()) {
            finish()
            return
        }
        themeRadio = intent.getBooleanExtra(EXTRA_THEME_RADIO, false)
        themeAnime = intent.getStringExtra(EXTRA_THEME_ANIME).orEmpty()
        title = themeAnime
        animeTitle.text = themeAnime
        videoInfo = playerView.findViewById(R.id.exo_video_info)
        // Episode-only controls.
        listOf<View>(
            exoSource, exoSubtitle, exoAudioTrack, exoSettings,
            exoSkipOpEd, customCastButton, skipTimeButton, exoSkip
        ).forEach { it.visibility = View.GONE }
        // The sleep timer stays (wired before theme mode branches off): useful with
        // background theme audio; "end of episode" stops after the current theme.

        // Repeat: tap cycles off -> repeat the list -> repeat this theme. Dimmed when off.
        playerView.findViewById<ImageButton>(R.id.exo_theme_repeat).apply {
            visibility = View.VISIBLE
            fun show() {
                setImageResource(
                    if (themeRepeat == ThemeRepeat.ONE) R.drawable.ic_round_repeat_one_24
                    else R.drawable.ic_round_repeat_24
                )
                alpha = if (themeRepeat == ThemeRepeat.OFF) 0.6f else 1f
                contentDescription = getString(
                    when (themeRepeat) {
                        ThemeRepeat.OFF -> R.string.theme_repeat_off
                        ThemeRepeat.ALL -> R.string.theme_repeat_all
                        ThemeRepeat.ONE -> R.string.theme_repeat_one
                    }
                )
            }
            show()
            setOnClickListener {
                themeRepeat = ThemeRepeat.entries[(themeRepeat.ordinal + 1) % ThemeRepeat.entries.size]
                show()
                snackString(contentDescription.toString())
            }
        }

        // Playback speed works for themes too. The episode setup that normally wires it
        // is skipped in theme mode, so it gets its own handler; the speed holds for the queue.
        val speeds = PlaybackSpeedSelection.values(PrefManager.getVal(PrefName.CursedSpeeds))
        // Themes start at 1x (the episode default speed is not applied to them).
        var themeSpeed = speeds.indexOf(1f).coerceAtLeast(0)
        exoSpeed.visibility = View.VISIBLE
        exoSpeed.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.speed)
                singleChoiceItems(speeds.map { "${it}x" }.toTypedArray(), themeSpeed) { i ->
                    themeSpeed = i
                    playerManager.playbackEngine?.setPlaybackSpeed(speeds.getOrNull(i) ?: 1f)
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        themeFailedPanel = playerView.findViewById(R.id.exo_theme_failed)
        themeCenter = playerView.findViewById(R.id.exo_center_cont)
        playerView.findViewById<View>(R.id.exo_theme_retry).setOnClickListener { playTheme(themeIndex) }
        playerView.findViewById<View>(R.id.exo_theme_skip).setOnClickListener { playTheme(themeIndex + 1) }

        themeFavourite = playerView.findViewById(R.id.exo_theme_favourite)
        themeFavourite.visibility = View.VISIBLE
        themeFavourite.setOnClickListener {
            val track = themeTracks.getOrNull(themeIndex) ?: return@setOnClickListener
            ThemeFavourites.toggle(track.mediaId, track.showTitle.ifBlank { themeAnime }, track)
            bindThemeButtons()
        }

        themeAdapter = NoPaddingArrayAdapter(this, R.layout.item_dropdown, ArrayList(themeTracks.map { ThemeQueue.label(it) }))
        episodeTitle.adapter = themeAdapter
        episodeTitle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position != themeIndex) playTheme(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        exoNext = playerView.findViewById(R.id.exo_next_ep)
        exoNext.setOnClickListener { playTheme(themeIndex + 1) }
        exoPrev = playerView.findViewById(R.id.exo_prev_ep)
        exoPrev.setOnClickListener { playTheme(themeIndex - 1) }

        isFullscreen = PrefManager.getVal(PrefName.Resize)
        exoScreen.setOnClickListener {
            isFullscreen = (isFullscreen + 1) % 3
            applyResizeMode(isFullscreen)
        }
        // The session lists the themes, so the notification and lock screen get previous/next.
        playerManager.mediaQueue = object : ani.dantotsu.media.anime.player.MediaQueue {
            override val titles get() = themeTracks.map { ThemeQueue.label(it) }
            override val currentIndex get() = themeIndex
            override fun skipTo(index: Int) = playTheme(index)
        }
        playTheme(intent.getIntExtra(EXTRA_THEME_START, 0).coerceIn(0, themeTracks.lastIndex))
    }

    /**
     * Theme mode keeps playing with the screen off or the app in the background: the
     * session goes to [AnimePlayerService], which shows the media notification. Tapping
     * it brings the app's task back without a new intent (onNewIntent restarts the player).
     */
    private fun startThemeService() {
        val session = playerManager.mediaSession ?: return
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            session.setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this, 0, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        AnimePlayerService.start(this, session)
    }

    /** Where the queue goes when a theme ends on its own; past the end means stop. */
    private fun nextThemeIndex(): Int = when (themeRepeat) {
        ThemeRepeat.ONE -> themeIndex
        // Radio keeps adding themes past the end, so "repeat all" only wraps a fixed list.
        ThemeRepeat.ALL -> if (themeIndex >= themeTracks.lastIndex && !themeRadio) 0 else themeIndex + 1
        ThemeRepeat.OFF -> themeIndex + 1
    }

    private fun bindThemeButtons() {
        val track = themeTracks.getOrNull(themeIndex) ?: return
        val fav = ThemeFavourites.isFavourite(track.themeId)
        themeFavourite.setImageResource(if (fav) R.drawable.ic_round_favorite_24 else R.drawable.ic_round_favorite_border_24)
        getString(if (fav) R.string.theme_favourite_remove else R.string.theme_favourite_add).let {
            themeFavourite.contentDescription = it
            themeFavourite.tooltipText = it
        }
    }

    private fun showThemeFailed(show: Boolean) {
        themeFailedPanel.visibility = if (show) View.VISIBLE else View.GONE
        themeCenter.visibility = if (show) View.INVISIBLE else View.VISIBLE
        if (show) playerView.showController()
    }

    /**
     * Starts theme [index] at its first attempt, or at [attempt] when retrying it.
     * Network attempts are checked with a one-byte request first, so a dead or
     * rate-limiting CDN shows up in seconds instead of after mpv's 20 s timeout.
     */
    private fun playTheme(index: Int, attempt: Int = 0, startMs: Long = 0L) {
        if (index == themeTracks.size && themeRadio) return radioNext(play = true)
        if (index !in themeTracks.indices) return
        themeJob?.cancel()
        showThemeFailed(false)
        val newTheme = attempt == 0 && (index != themeIndex || themePlan.isEmpty() || startMs == 0L)
        themeIndex = index
        themeAttempt = attempt
        if (attempt == 0) themeLastPositionMs = startMs
        val track = themeTracks[index]
        if (episodeTitle.selectedItemPosition != index) episodeTitle.setSelection(index)
        animeTitle.text = track.showTitle.ifBlank { themeAnime }
        bindThemeButtons()
        if (themeRadio && index >= themeTracks.lastIndex) radioNext(play = false)
        themeJob = lifecycleScope.launch {
            if (attempt == 0) {
                // Audio only is chosen in the theme lists (Themes tab, Themes screen).
                val audioOnly = PrefManager.getVal<Boolean>(PrefName.AnimeThemesAudioOnly)
                val (savedAudio, savedVideo) = withContext(Dispatchers.IO) {
                    runCatching {
                        val downloads = ThemeDownloads(this@ExoplayerView)
                        downloads.savedUri(track, ThemeFileKind.AUDIO)?.toString() to
                            downloads.savedUri(track, ThemeFileKind.VIDEO)?.toString()
                    }.getOrDefault(null to null)
                }
                val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) || !isOnline(this@ExoplayerView)
                val sources = ThemeQueue.sources(track, audioOnly, savedAudio, savedVideo, offline)
                if (newTheme && ThemeQueue.audioMissing(audioOnly, sources, savedAudio)) {
                    toast(getString(R.string.theme_no_audio, track.tag))
                }
                Logger.log("AnimeThemes: ${track.tag} audioOnly=$audioOnly first=${sources.firstOrNull()?.substringAfterLast('/')} of ${sources.size}")
                themePlan = ThemeQueue.attempts(sources)
            }
            val next = themePlan.getOrNull(attempt) ?: return@launch showThemeFailed(true)
            setThemeStatus(next, null)
            if (ThemeReach.needsCheck(next.url)) {
                val reach = ThemeReach.check(next.url, defaultHeaders)
                if (reach != ThemeReachResult.Ok) return@launch onThemeFailed("check: $reach", reach)
            }
            loadTheme(track, next.url, themeLastPositionMs)
        }
    }

    private fun setThemeStatus(attempt: ThemeQueue.Attempt, reach: ThemeReachResult?) {
        val n = themePlan.indexOf(attempt).coerceAtLeast(0) + 1
        val total = themePlan.size
        videoInfo.text = when {
            reach is ThemeReachResult.RateLimited -> getString(R.string.theme_status_rate_limited, reach.retryAfterS)
            reach is ThemeReachResult.HttpError && reach.code > 0 -> getString(R.string.theme_status_http, reach.code, n, total)
            reach == ThemeReachResult.Unreachable -> getString(R.string.theme_status_unreachable, n, total)
            !ThemeReach.needsCheck(attempt.url) -> getString(R.string.theme_status_saved)
            else -> getString(R.string.theme_status_connecting, n, total)
        }
    }

    private fun loadTheme(track: ThemeTrack, url: String, startMs: Long) {
        // One player for the whole queue: the next theme loads into the running engine,
        // so the media session (and its notification) stays the same.
        val running = playerManager.playbackEngine.takeIf { playerManager.isInitialized }
        val engine = running ?: playerManager.initPlayer(startMs, 1f, themeListener)
        if (running == null) startThemeService()
        val audio = ThemeQueue.isAudio(url)
        videoInfo.text = when {
            !ThemeReach.needsCheck(url) -> getString(R.string.theme_status_saved)
            audio -> getString(R.string.theme_status_audio)
            else -> ""
        }
        engine.loadMedia(
            PlaybackRequest(
                uri = url,
                startPositionMs = startMs,
                headers = defaultHeaders,
                title = ThemeQueue.label(track),
                seriesTitle = track.showTitle.ifBlank { themeAnime },
                mimeType = if (audio) "audio/ogg" else "video/webm",
                sourceClass = if (url.startsWith("content://")) PlaybackSourceClass.CONTENT_FD else PlaybackSourceClass.DIRECT_HTTP,
            )
        )
        playerManager.playbackCoordinator?.play() ?: engine.play()
        playerManager.media3PlayerAdapter?.refresh()
        applyResizeMode(isFullscreen)
    }

    /** Next attempt for the current theme, or the failure panel when none is left. */
    private fun onThemeFailed(reason: String?, reach: ThemeReachResult? = null) {
        val track = themeTracks.getOrNull(themeIndex) ?: return
        val index = themeIndex
        val nextAttempt = themeAttempt + 1
        val next = themePlan.getOrNull(nextAttempt)
        Logger.log("AnimeThemes: ${track.tag} failed (${reason ?: "unknown"}), attempt $nextAttempt of ${themePlan.size}")
        themeJob?.cancel()
        if (next == null) {
            videoInfo.text = ""
            showThemeFailed(true)
            // In a queue, move on after a moment; Retry stays on screen until then.
            if (index < themeTracks.lastIndex || themeRadio) {
                themeJob = lifecycleScope.launch {
                    delay(5_000L)
                    if (themeIndex == index && !isDestroyed) playTheme(index + 1)
                }
            }
            return
        }
        setThemeStatus(next, reach)
        val resumeAt = themeLastPositionMs
        themeJob = lifecycleScope.launch {
            delay(ThemeQueue.delayFor(next, reach))
            if (themeIndex == index && !isDestroyed) playTheme(index, nextAttempt, resumeAt)
        }
    }

    /** Shuffle: looks up the next theme; plays it when [play] (the queue ran out). */
    private fun radioNext(play: Boolean) {
        if (themeRadioJob?.isActive == true && !play) return
        if (!ThemeRadioSession.active) {
            if (play) showThemeFailed(true)
            return
        }
        if (play) videoInfo.text = getString(R.string.themes_finding)
        themeRadioJob?.cancel()
        themeRadioJob = lifecycleScope.launch {
            val track = runCatching { ThemeRadioSession.next() }.getOrNull()
            if (track == null) {
                if (play) {
                    videoInfo.text = ""
                    toast(getString(R.string.themes_none_found))
                }
                return@launch
            }
            themeTracks = themeTracks + track
            themeAdapter.add(ThemeQueue.label(track))
            playerManager.media3PlayerAdapter?.refresh()
            if (play) playTheme(themeTracks.lastIndex)
        }
    }

    private val themeListener = object : PlaybackListener {
        override fun onPositionChanged(positionMs: Long, durationMs: Long) {
            if (positionMs > 0L) themeLastPositionMs = positionMs
            this@ExoplayerView.onPositionChanged(positionMs, durationMs)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = this@ExoplayerView.onIsPlayingChanged(isPlaying)

        override fun onVideoSizeChanged(width: Int, height: Int) = this@ExoplayerView.onVideoSizeChanged(width, height)

        override fun onPlaybackStateChanged(state: PlaybackState) {
            exoBufferingIndicator.visibility = if (state is PlaybackState.Buffering) View.VISIBLE else View.GONE
            isBuffering = state is PlaybackState.Buffering
            when (state) {
                is PlaybackState.Ended -> {
                    val dur = state.durationMs.takeIf { it > 0L } ?: (playerManager.playbackEngine?.durationMs ?: 0L)
                    val pos = maxOf(state.positionMs, themeLastPositionMs)
                    // mpv reports a dropped connection as an early end of file.
                    if (ThemeQueue.endedEarly(pos, dur)) onThemeFailed("ended at $pos of $dur ms")
                    // Sleep timer "end of episode" means end of the current theme here.
                    else if (sleepTimer.onEpisodeEnded()) {
                        exoSleepTimer.alpha = 1f
                        toast(getString(R.string.sleep_timer_stopped))
                    }
                    else playTheme(nextThemeIndex())
                }
                is PlaybackState.Error -> onThemeFailed(state.error.message)
                else -> Unit
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}

/**
 * Clamps a user-typed skip time into the dialog
 * range and snaps it to the slider step grid, so assigning
 * `Slider.value` can never throw IllegalArgumentException. Step/max mirror
 * `item_seekbar_dialog.xml` (stepSize 1 since upstream 55501e6e, 0..120); player
 * behavior otherwise unchanged (long-press still writes the snapped value back to prefs).
 */
fun snapSkipSliderValue(skipTime: Int, step: Float = 1f, max: Float = 120f): Float {
    val clamped = skipTime.toFloat().coerceIn(0f, max)
    return kotlin.math.round(clamped / step) * step
}
