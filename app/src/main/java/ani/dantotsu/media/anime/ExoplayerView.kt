package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Animatable
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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import ani.dantotsu.NoPaddingArrayAdapter
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.RPCManager
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
import ani.dantotsu.media.anime.player.CastScreenView
import ani.dantotsu.media.anime.player.DantotsuPlayerManager
import ani.dantotsu.media.anime.player.DantotsuPlayerView
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
import ani.dantotsu.media.anime.player.TrackType
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.getSerialized
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.HAnimeSources
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Timer
import java.util.TimerTask
import kotlin.math.max
import kotlin.math.min

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
    private var isFullscreen: Int = 0
    private var isPlayerPlaying = true
    private var changingServer = false
    private var interacted = false
    private var pipEnabled = false
    private var aspectRatio = Rational(16, 9)
    private var isBuffering = true
    private var playerErrorRetryCount = 0
    private var rotation = 0
    private var wasPlaying = false

    private val handler = Handler(Looper.getMainLooper())
    val model: MediaDetailsViewModel by viewModels()
    private val client = OkHttpClient()

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { applyLocalSubtitle(it) }
    }

    private val onChangeSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _: ActivityResult ->
        if (this::subtitleManager.isInitialized) {
            subtitleManager.applySubtitlePreferences()
        }
        if (this::playerManager.isInitialized && playerManager.isInitialized) {
            playerManager.playbackEngine?.play()
        }
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

        if (!initialized) {
            startMainActivity(this)
            finish()
            return
        }

        ThemeManager(this).applyTheme()
        binding = ActivityExoplayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        exoSkipOpEd = playerView.findViewById(R.id.exo_skip_op_ed)
        exoSkip = playerView.findViewById(R.id.exo_skip)
        skipTimeButton = playerView.findViewById(R.id.exo_skip_timestamp)
        skipTimeText = skipTimeButton.findViewById(R.id.exo_skip_timestamp_text)
        timeStampText = playerView.findViewById(R.id.exo_time_stamp_text)
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
        aniSkipManager = PlayerAniSkipManager(
            this, timeline, model, exoSkipOpEd, exoSkip, skipTimeButton, skipTimeText, timeStampText,
            { playerManager.playbackEngine }
        )
        discordManager = PlayerDiscordManager(this)
        progressManager = PlayerProgressManager(
            this, model, { playerManager.playbackEngine }, { playerManager.isInitialized }
        )

        castManager = PlayerCastManager(this, playerView) { isPlaying ->
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (initialized && this::episode.isInitialized) {
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
                    progressManager.updateAniProgress()
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

        castManager.onSessionStartedListener = { deviceName ->
            playerManager.playbackEngine?.pause()
            if (initialized) {
                castScreenView.updateMediaInfo(media, if (this::episode.isInitialized) episode else null)
            }
            castScreenView.updateDeviceName(deviceName)
            castScreenView.show(true)
        }

        castManager.onSessionEndedListener = { resumePositionMs, shouldPlay ->
            castScreenView.hide(true)
            playerManager.playbackEngine?.let { engine ->
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
                            in 315..360, in 0..45 -> {
                                if (rotation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                                    exoRotate.visibility = View.VISIBLE
                                }
                                rotation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        }
                    }
                }
                orientationListener?.enable()
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            exoRotate.setOnClickListener {
                requestedOrientation = rotation
                it.visibility = View.GONE
            }
        }

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

        aniSkipManager.init()

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
                slider.value = if (skipTime <= 120) skipTime.toFloat() else 120f
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

        // Handle Media
        if (!initialized) return startMainActivity(this)
        model.setMedia(media)
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
                "Episode ${ep.number}${if (ep.filler) " [Filler]" else ""}${if (cleanedTitle.isNotBlank() && cleanedTitle != "null") ": $cleanedTitle" else ""}"
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
                        progressManager.updateAniProgress()
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
                val currentPos = playerManager.playbackEngine?.positionMs
                episode = ep
                media.selected = model.loadSelected(media)
                model.setMedia(media)
                val epKey = episodes.getEpisodeKey(ep.number)
                    ?: episodeArr.find { episodes[it] == ep || episodes[it]?.number == ep.number }
                    ?: episodeArr.firstOrNull()
                currentEpisodeIndex = if (epKey != null) max(0, episodeArr.indexOf(epKey)) else 0
                progressManager.currentEpisodeIndex = currentEpisodeIndex
                if (currentEpisodeIndex in 0 until episodeTitleArr.size) {
                    episodeTitle.setSelection(currentEpisodeIndex)
                }
                if (this::playerManager.isInitialized && playerManager.isInitialized) {
                    releasePlayer()
                    playbackPosition = currentPos ?: PrefManager.getCustomVal("${media.id}_${epKey ?: ep.number}", 0L)
                } else {
                    playbackPosition = PrefManager.getCustomVal("${media.id}_${epKey ?: ep.number}", 0L)
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

        exoScreen.setOnClickListener {
            isFullscreen = if (isFullscreen < 2) isFullscreen + 1 else 0
            applyResizeMode(isFullscreen)
            snackString(when (isFullscreen) {
                0 -> "Original"
                1 -> "Zoom"
                2 -> "Stretch"
                else -> "Original"
            }, this)
            PrefManager.setCustomVal("${media.id}_fullscreenInt", isFullscreen)
        }

        // Settings Button
        exoSettings.setOnClickListener {
            playerManager.playbackEngine?.let { engine ->
                val selEp = media.anime?.selectedEpisode
                if (selEp != null) {
                    PrefManager.setCustomVal("${media.id}_${selEp}", engine.positionMs)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != selEp) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", engine.positionMs)
                    }
                }
                engine.pause()
            }
            val intent = Intent(this, PlayerSettingsActivity::class.java).apply {
                putExtra("subtitle", subtitle)
            }
            onChangeSettings.launch(intent)
        }

        // Speed Dialog
        val speeds = if (PrefManager.getVal(PrefName.CursedSpeeds)) {
            arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        } else {
            arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        }
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
                    hideSystemBars()
                }
                setOnCancelListener { hideSystemBars() }
                show()
            }
        }

        // AutoPlay Interacted Tracking
        if (PrefManager.getVal(PrefName.AutoPlay)) {
            var touchTimer = Timer()
            fun touched() {
                interacted = true
                touchTimer.cancel()
                touchTimer.purge()
                touchTimer = Timer()
                touchTimer.schedule(object : TimerTask() {
                    override fun run() { interacted = false }
                }, 1000 * 60 * 60)
            }
            playerView.findViewById<View>(R.id.exo_touch_view).setOnTouchListener { _, _ ->
                touched()
                false
            }
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

    private fun applyResizeMode(mode: Int) {
        val resizeMode = when (mode) {
            0 -> ResizeMode.FIT
            1 -> ResizeMode.ZOOM
            2 -> ResizeMode.STRETCH
            else -> ResizeMode.FIT
        }
        playerManager.playbackEngine?.setResizeMode(resizeMode)
    }

    private fun changeEpisode(index: Int) {
        if (this::playerManager.isInitialized && playerManager.isInitialized && this::episodeArr.isInitialized && index in episodeArr.indices) {
            changingServer = false
            val prevEpKey = episodeArr.getOrNull(currentEpisodeIndex)
            if (prevEpKey != null) {
                playerManager.playbackEngine?.let { engine ->
                    PrefManager.setCustomVal("${media.id}_$prevEpKey", engine.positionMs)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(prevEpKey)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != prevEpKey) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", engine.positionMs)
                    }
                }
                if (this::subtitleManager.isInitialized) {
                    subtitleManager.clearTransientSubtitleCache("${media.id}-$prevEpKey")
                }
            }
            if (this::aniSkipManager.isInitialized) {
                aniSkipManager.resetForNewEpisode()
            }
            if (this::progressManager.isInitialized) {
                progressManager.episodeLength = 0f
            }
            val newEpKey = episodeArr[index]
            media.anime?.selectedEpisode = newEpKey
            val targetEpisode = episodes[newEpKey] ?: return
            model.setMedia(media)
            model.epChanged.postValue(false)
            model.setEpisode(targetEpisode, "change")
            model.onEpisodeClick(media, newEpKey, supportFragmentManager, false, prevEpKey ?: "", false)
        }
    }

    private fun initPlayer() {
        gestureManager.checkNotch()
        aniSkipManager.resetForNewEpisode()
        val selEp = media.anime?.selectedEpisode ?: episodeArr.firstOrNull() ?: return
        media.anime!!.selectedEpisode = selEp
        PrefManager.setCustomVal("${media.id}_current_ep", selEp)

        val list = (PrefManager.getNullableCustomVal("continueAnimeList", listOf<Int>(), List::class.java) as List<Int>).toMutableList()
        if (list.contains(media.id)) list.remove(media.id)
        list.add(media.id)
        PrefManager.setCustomVal("continueAnimeList", list)

        lifecycleScope.launch(Dispatchers.IO) { extractor?.onVideoStopped(video) }

        val ext = episode.extractors?.find { it.server.name == episode.selectedExtractor }
            ?: episode.extractors?.firstOrNull() ?: return
        extractor = ext
        video = ext.videos.getOrNull(episode.selectedVideo) ?: ext.videos.firstOrNull() ?: return

        val subLanguages = arrayOf(
            "Albanian", "Arabic", "Bosnian", "Bulgarian", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "English",
            "Estonian", "Finnish", "French", "Georgian", "German", "Greek", "Hebrew", "Hindi", "Indonesian", "Irish",
            "Italian", "Japanese", "Korean", "Lithuanian", "Luxembourgish", "Macedonian", "Mongolian", "Norwegian",
            "Polish", "Portuguese", "Punjabi", "Romanian", "Russian", "Serbian", "Slovak", "Slovenian", "Spanish",
            "Turkish", "Ukrainian", "Urdu", "Vietnamese"
        )
        val lang = subLanguages.getOrNull(PrefManager.getVal<Int>(PrefName.SubLanguage)) ?: "English"
        val savedSubLang: String? = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        subtitle = intent.getSerialized("subtitle")
            ?: when {
                savedSubLang == null -> when (episode.selectedSubtitle) {
                    null, -1 -> ext.subtitles.find {
                        it.language.contains(lang, true) ||
                        it.language.contains("English", true) ||
                        it.language.contains("en", true)
                    } ?: ext.subtitles.firstOrNull()
                    else -> ext.subtitles.getOrNull(episode.selectedSubtitle!!)
                }
                savedSubLang == "None" -> null
                savedSubLang.startsWith("Online:") -> null
                savedSubLang.startsWith("[Local]") -> null
                savedSubLang.startsWith("Embedded:") -> null
                else -> ext.subtitles.find { it.language == savedSubLang }
            }

        hasExtSubtitles = ext.subtitles.isNotEmpty()
        if (subtitle == null && hasExtSubtitles && savedSubLang != "None" &&
            savedSubLang?.startsWith("Online:") != true &&
            savedSubLang?.startsWith("[Local]") != true &&
            savedSubLang?.startsWith("Embedded:") != true
        ) {
            subtitle = ext.subtitles.find {
                it.language.contains(lang, true) ||
                it.language.contains("English", true) ||
                it.language.contains("en", true)
            } ?: ext.subtitles.firstOrNull()
        }
        subtitleManager.initialSubtitleLabel = subtitle?.language ?: lang
        if (subtitle != null) {
            PrefManager.setCustomVal("subLang_${media.id}", subtitle!!.language)
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

        lifecycleScope.launch(Dispatchers.IO) { ext.onVideoPlayed(video) }

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
        if (currentVideo != null && (currentVideo.file.url.startsWith("magnet:") || currentVideo.file.url.endsWith(".torrent"))) {
            val torrentManager = Injekt.get<ani.dantotsu.torrent.TorrentServerManager>()
            if (torrentManager.isAvailable()) {
                val url = currentVideo.file.url
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        torrentManager.activeTorrentHash?.let {
                            torrentManager.removeTorrent(it)
                        }
                        val index = if (url.contains("index=")) {
                            url.substringAfter("index=").toIntOrNull() ?: 0
                        } else 0
                        val currentTorrent = torrentManager.addTorrent(
                            url, currentVideo.quality.toString(), "", "", false
                        )
                        torrentManager.activeTorrentHash = currentTorrent.hash
                        torrentManager.prebuffer(currentTorrent.hash!!, index)
                        currentVideo.file.url = torrentManager.getLink(currentTorrent, index)
                        withContext(Dispatchers.Main) {
                            buildMpvPlayer()
                        }
                    } catch (e: Exception) {
                        Injekt.get<CrashlyticsInterface>().logException(e)
                        withContext(Dispatchers.Main) {
                            toast("Error starting torrent: ${e.message}")
                            sourceClick()
                        }
                    }
                }
                return
            }
        }

        buildMpvPlayer()
    }

    private fun buildMpvPlayer() {
        playerErrorRetryCount = 0
        hideSystemBars()

        val selEp = media.anime?.selectedEpisode
        val cleanEp = selEp?.let { MediaNameAdapter.findEpisodeNumber(it) }?.let {
            if (it % 1 == 0f) it.toInt().toString() else it.toString()
        }
        val savedMax = selEp?.let { PrefManager.getCustomVal("${media.id}_${it}_max", Long.MAX_VALUE) }
            ?.takeIf { it != Long.MAX_VALUE }
            ?: (cleanEp?.let { PrefManager.getCustomVal("${media.id}_${cleanEp}_max", Long.MAX_VALUE) }?.takeIf { it != Long.MAX_VALUE })

        val savedPosition = savedMax?.let { if (it <= playbackPosition) max(0, it - 5000L) else playbackPosition }
            ?: playbackPosition

        val speeds = if (PrefManager.getVal(PrefName.CursedSpeeds)) {
            arrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)
        } else {
            arrayOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
        }
        val savedSpeedIndex = PrefManager.getCustomVal("${media.id}_speed", PrefManager.getVal<Int>(PrefName.DefaultSpeed))
            .coerceIn(0, speeds.size - 1)
        val currentSpeed = speeds[savedSpeedIndex]

        val engine = playerManager.initPlayer(
            savedPosition,
            currentSpeed,
            this
        )

        val episodeDisplayName = episodeTitleArr.getOrNull(currentEpisodeIndex) ?: episode.number
        val savedSubLang = PrefManager.getNullableCustomVal("subLang_${media.id}", null, String::class.java)
        val request = playerManager.buildPlaybackRequest(
            video = video!!,
            subtitles = extractor?.subtitles,
            startPositionMs = savedPosition,
            title = episodeDisplayName,
            seriesTitle = media.userPreferredName,
            episodeNumber = episode.number,
            coverUrl = media.cover,
            preferredSubLang = savedSubLang,
            embedUrl = extractor?.server?.embed?.url,
            audioTracks = extractor?.audioTracks ?: emptyList()
        )
        engine.loadMedia(request)

        // Request audio focus and start playback through the coordinator.
        // Without this, MPV auto-plays (pause=no default) but the coordinator
        // never requests audio focus, leaving the session unprotected and
        // potentially killed by Android's audio system.
        playerManager.playbackCoordinator?.play() ?: engine.play()

        applyResizeMode(isFullscreen)
        val castItem = androidx.media3.common.MediaItem.Builder()
            .setUri(video!!.file.url)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("${media.userPreferredName} : $episodeDisplayName")
                    .build()
            )
            .build()
        castManager.updateCurrentMedia(castItem, engine, video, playerManager.playbackCoordinator)
        progressManager.updateWidgetState(isExiting = false)
    }

    private fun releasePlayer() {
        playerManager.playbackEngine?.let { engine ->
            isPlayerPlaying = engine.isPlaying
            playbackPosition = engine.positionMs
        }
        progressManager.stopTracking()
        progressManager.updateWidgetState(isExiting = true)
        discordManager.clear()
        playerManager.releasePlayer()
    }

    private fun sourceClick() {
        changingServer = true

        media.selected?.server = null
        playerManager.playbackEngine?.let { engine ->
            PrefManager.setCustomVal(
                "${media.id}_${media.anime?.selectedEpisode}",
                engine.positionMs
            )
            engine.pause()
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val epNum = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
        model.onEpisodeClick(
            media,
            epNum,
            this.supportFragmentManager,
            launch = false
        )
    }

    private fun subClick() {
        playerManager.playbackEngine?.let { engine ->
            PrefManager.setCustomVal(
                "${media.id}_${media.anime?.selectedEpisode}",
                engine.positionMs
            )
        }
        media.selected?.let { model.saveSelected(media.id, it) }
        val dialog = SubtitleDialogFragment()
        dialog.show(supportFragmentManager, "dialog")
    }

    // Public contract methods
    fun requestLocalSubtitle() {
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
    }

    override fun onTracksChanged(tracks: List<PlayerTrack>) {
        val audioTracks = tracks.filter { it.type == TrackType.AUDIO }
        val subTracks = tracks.filter { it.type == TrackType.SUBTITLE }

        exoAudioTrack.isVisible = audioTracks.size > 1
        exoAudioTrack.setOnClickListener {
            TrackGroupDialogFragment(this, audioTracks, TrackType.AUDIO)
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
            }
            is PlaybackState.Buffering -> {
                exoBufferingIndicator.visibility = View.VISIBLE
                isBuffering = true
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
                if (nearPhysicalEnd && PrefManager.getVal<Boolean>(PrefName.AutoPlay) && !interacted) {
                    progressManager.nextEpisode { i ->
                        changeEpisode(currentEpisodeIndex + i)
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
            if (extTimestamps.isNotEmpty() || dur > 0L) {
                val epString = if (this::episode.isInitialized) episode.number else (media.anime?.selectedEpisode ?: "1")
                val epNum = Regex("""\d+""").find(epString)?.value?.toIntOrNull()
                    ?: epString.trim().toIntOrNull()
                    ?: 1
                lifecycleScope.launch(Dispatchers.IO) {
                    model.loadTimeStamps(
                        media.idMAL,
                        epNum,
                        if (dur > 0L) dur / 1000L else 0L,
                        PrefManager.getVal(PrefName.UseProxyForTimeStamps),
                        extTimestamps
                    )
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isBuffering) {
            isPlayerPlaying = isPlaying
            playerView.keepScreenOn = isPlaying
            (exoPlay.drawable as? Animatable)?.start()
            if (!isDestroyed) {
                Glide.with(this)
                    .load(if (isPlaying) R.drawable.anim_play_to_pause else R.drawable.anim_pause_to_play)
                    .into(exoPlay)
            }
            if (this::episode.isInitialized) {
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

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val clampedRatio = getClampedAspectRatio(aspectRatio)
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(clampedRatio)

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
                    builder.setAutoEnterEnabled(liveIsPlaying && pipEnabled && PrefManager.getVal(PrefName.Pip))
                }

                setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
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
            sourceClick()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (PrefManager.getVal(PrefName.FocusPause) && !epChanging) {
            if (this::playerManager.isInitialized && playerManager.isInitialized) {
                playerManager.playbackCoordinator?.setLifecycleSuppressed(!hasFocus)
            }
        }
        super.onWindowFocusChanged(hasFocus)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val clampedRatio = getClampedAspectRatio(aspectRatio)
            val builder = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(clampedRatio)
            val surfaceView = playerView.surfaceView
            if (surfaceView != null && surfaceView.isLaidOut) {
                val rect = android.graphics.Rect()
                if (surfaceView.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
                    builder.setSourceRectHint(rect)
                }
            }
            enterPictureInPictureMode(builder.build())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEnabled && PrefManager.getVal(PrefName.Pip)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                enterPipMode()
            }
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

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.hideController()
            if (this::aniSkipManager.isInitialized) {
                aniSkipManager.hideSkipButtons()
                aniSkipManager.stopTracking()
            }
            if (this::progressManager.isInitialized) {
                progressManager.stopTracking()
            }
        } else {
            hideSystemBars()
            if (this::aniSkipManager.isInitialized && PrefManager.getVal(PrefName.TimeStampsEnabled)) {
                aniSkipManager.startTracking()
            }
            if (this::progressManager.isInitialized) {
                progressManager.startTracking()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (this::aniSkipManager.isInitialized) {
            aniSkipManager.startTracking()
        }
        if (this::progressManager.isInitialized) {
            progressManager.startTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (this::playerManager.isInitialized && playerManager.isInitialized) {
            playerManager.playbackCoordinator?.setLifecycleSuppressed(false)
        }
        updatePipParams()
    }

    override fun onPause() {
        super.onPause()
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
        if (this::playerManager.isInitialized && playerManager.isInitialized && !inPip) {
            playerManager.playbackCoordinator?.setLifecycleSuppressed(true)
        }
        if (this::playerManager.isInitialized && playerManager.isInitialized) {
            playerManager.playbackEngine?.let { engine ->
                val selEp = if (initialized) media.anime?.selectedEpisode else null
                if (selEp != null) {
                    PrefManager.setCustomVal("${media.id}_${selEp}", engine.positionMs)
                    val cleanEp = MediaNameAdapter.findEpisodeNumber(selEp)?.let {
                        if (it % 1 == 0f) it.toInt().toString() else it.toString()
                    }
                    if (cleanEp != null && cleanEp != selEp) {
                        PrefManager.setCustomVal("${media.id}_${cleanEp}", engine.positionMs)
                    }
                }
            }
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

    override fun onDestroy() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        orientationListener?.disable()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                extractor?.onVideoStopped(video)
            } catch (_: Exception) {}
        }
        if (this::playerManager.isInitialized && playerManager.isInitialized) {
            if (this::progressManager.isInitialized) {
                progressManager.updateAniProgress()
            }
            val episodeId = "${if (initialized) media.id else ""}-${if (initialized) media.anime?.selectedEpisode ?: "" else ""}"
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
