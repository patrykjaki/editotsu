package ani.dantotsu.animethemes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityThemePlayerBinding
import ani.dantotsu.hideSystemBars
import ani.dantotsu.themes.ThemeManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.serialization.builtins.ListSerializer

/** Full-screen AnimeThemes player. Playback lives in [ThemePlaybackService]; this screen is a controller for it. */
@OptIn(UnstableApi::class)
class ThemePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemePlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = showMetadata(mediaMetadata)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityThemePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        binding.themePlayerView.setErrorMessageProvider(ErrorMessages(this))
        binding.themePlayerView.findViewById<View>(R.id.themePlayerBack)?.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { loadQueueFromIntent(it) }
    }

    override fun onStart() {
        super.onStart()
        hideSystemBars()
        val token = SessionToken(this, ComponentName(this, ThemePlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || isDestroyed) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            binding.themePlayerView.player = c
            c.addListener(listener)
            loadQueueFromIntent(c)
            showMetadata(c.mediaMetadata)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        binding.themePlayerView.player = null
        controller?.let {
            it.removeListener(listener)
            // Leaving with back ends playback; leaving with home keeps it playing in the background.
            if (isFinishing) {
                it.stop()
                it.clearMediaItems()
            }
        }
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun loadQueueFromIntent(c: MediaController) {
        val json = intent.getStringExtra(EXTRA_TRACKS) ?: return
        intent.removeExtra(EXTRA_TRACKS)
        val tracks = runCatching { Mapper.json.decodeFromString(ListSerializer(ThemeTrack.serializer()), json) }
            .getOrNull().orEmpty()
        if (tracks.isEmpty()) return
        val anime = intent.getStringExtra(EXTRA_ANIME).orEmpty()
        val cover = intent.getStringExtra(EXTRA_COVER)
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, tracks.lastIndex)
        c.setMediaItems(tracks.map { mediaItem(it, anime, cover) }, start, 0L)
        c.prepare()
        c.play()
    }

    private fun showMetadata(m: MediaMetadata) {
        val root = binding.themePlayerView
        root.findViewById<android.widget.TextView>(R.id.themePlayerTag)?.text = m.subtitle
        root.findViewById<android.widget.TextView>(R.id.themePlayerSong)?.text = m.title
        root.findViewById<android.widget.TextView>(R.id.themePlayerArtist)?.apply {
            text = m.artist
            visibility = if (m.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private class ErrorMessages(private val context: Context) : ErrorMessageProvider<PlaybackException> {
        override fun getErrorMessage(e: PlaybackException): Pair<Int, String> {
            val http = generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            val text = if (http != null) context.getString(R.string.theme_error_http, http.responseCode)
            else context.getString(R.string.theme_error)
            return Pair.create(0, text)
        }
    }

    companion object {
        private const val EXTRA_TRACKS = "tracks"
        private const val EXTRA_START = "start"
        private const val EXTRA_ANIME = "anime"
        private const val EXTRA_COVER = "cover"

        fun open(context: Context, tracks: List<ThemeTrack>, start: Int, animeTitle: String, cover: String?) {
            if (tracks.isEmpty()) return
            context.startActivity(
                Intent(context, ThemePlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_TRACKS, Mapper.json.encodeToString(ListSerializer(ThemeTrack.serializer()), tracks))
                    .putExtra(EXTRA_START, start)
                    .putExtra(EXTRA_ANIME, animeTitle)
                    .putExtra(EXTRA_COVER, cover)
            )
        }

        fun mediaItem(track: ThemeTrack, animeTitle: String, cover: String?): MediaItem {
            val uri = Uri.parse(track.url)
            val tagLine = listOfNotNull(track.tag, ThemeQueue.episodesLabel(track.episodes)).joinToString(" · ")
            return MediaItem.Builder()
                .setMediaId("theme-${track.themeId}")
                .setUri(uri)
                .setMimeType("video/webm")
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.songTitle)
                        .setArtist(track.artist.takeIf { it.isNotBlank() })
                        .setSubtitle(tagLine)
                        .setAlbumTitle(animeTitle.takeIf { it.isNotBlank() })
                        .setArtworkUri(cover?.let(Uri::parse))
                        .setExtras(
                            bundleOf(
                                ThemePlaybackService.EXTRA_FALLBACKS to ArrayList(track.fallbackUrls),
                                ThemePlaybackService.EXTRA_ATTEMPT to 0,
                            )
                        )
                        .build()
                )
                .build()
        }
    }
}
