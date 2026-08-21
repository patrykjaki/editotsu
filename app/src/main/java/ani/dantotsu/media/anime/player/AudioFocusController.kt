package ani.dantotsu.media.anime.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

interface AudioFocusController {
    fun requestFocus(): FocusResult
    fun abandonFocus()
    fun setListener(listener: AudioFocusChangeListener?)
}

sealed interface FocusResult {
    data object Granted : FocusResult
    data object Delayed : FocusResult
    data object Failed : FocusResult
}

enum class FocusChangeType {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK
}

fun interface AudioFocusChangeListener {
    fun onAudioFocusChange(change: FocusChangeType)
}

class AndroidAudioFocusController(
    private val context: Context
) : AudioFocusController, AudioManager.OnAudioFocusChangeListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var listener: AudioFocusChangeListener? = null

    override fun setListener(listener: AudioFocusChangeListener?) {
        this.listener = listener
    }

    override fun requestFocus(): FocusResult {
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
                    setWillPauseWhenDucked(true)
                    setOnAudioFocusChangeListener(this@AndroidAudioFocusController, handler)
                    build()
                }
            }
            when (audioManager.requestAudioFocus(focusRequest!!)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusResult.Granted
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusResult.Delayed
                else -> FocusResult.Failed
            }
        } else {
            @Suppress("DEPRECATION")
            when (audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusResult.Granted
                else -> FocusResult.Failed
            }
        }
    }

    override fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        val changeType = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChangeType.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusChangeType.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChangeType.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChangeType.LOSS_TRANSIENT_CAN_DUCK
            else -> return
        }
        listener?.onAudioFocusChange(changeType)
    }
}
