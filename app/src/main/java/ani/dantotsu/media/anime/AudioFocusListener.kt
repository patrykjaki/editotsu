package ani.dantotsu.media.anime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import ani.dantotsu.media.anime.player.PlaybackEngine

class AudioFocusListener(
    val context: Context,
    val engine: PlaybackEngine
) : AudioManager.OnAudioFocusChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    var wasPlayingBeforeTransientLoss = false
        private set

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPlayingBeforeTransientLoss) {
                    engine.play()
                    wasPlayingBeforeTransientLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                wasPlayingBeforeTransientLoss = engine.playWhenReady
                engine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeTransientLoss = false
                engine.pause()
                abandonRequest()
            }
        }
    }

    fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    setAcceptsDelayedFocusGain(true)
                    setOnAudioFocusChangeListener(this@AudioFocusListener, handler)
                    build()
                }
            }
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        wasPlayingBeforeTransientLoss = false
    }
}
