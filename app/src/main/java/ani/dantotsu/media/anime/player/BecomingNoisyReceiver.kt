package ani.dantotsu.media.anime.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

class BecomingNoisyReceiver(
    private val onBecomingNoisy: () -> Unit
) : BroadcastReceiver() {

    private var isRegistered = false

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            onBecomingNoisy()
        }
    }

    fun register(context: Context) {
        if (!isRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(
                context,
                this,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isRegistered = true
        }
    }

    fun unregister(context: Context) {
        if (isRegistered) {
            try {
                context.unregisterReceiver(this)
            } catch (ignored: Exception) {
            }
            isRegistered = false
        }
    }
}
