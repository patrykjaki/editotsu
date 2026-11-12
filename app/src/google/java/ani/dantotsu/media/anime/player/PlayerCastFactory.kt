package ani.dantotsu.media.anime.player

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi

@UnstableApi
object PlayerCastFactory {
    fun create(
        activity: AppCompatActivity,
        playerView: View,
        onCastStateChanged: (isPlaying: Boolean) -> Unit,
    ): PlayerCastController = PlayerCastManager(activity, playerView, onCastStateChanged)
}
