package ani.dantotsu.media.anime.cast

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack

/**
 * Media3's default converter ignores [MediaItem.LocalConfiguration.subtitleConfigurations],
 * so the receiver never got a subtitle. This adds each one as a Cast text track and marks
 * the default one active.
 */
@UnstableApi
class CastSubtitleConverter(
    private val base: MediaItemConverter = DefaultMediaItemConverter(),
) : MediaItemConverter {

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = base.toMediaItem(mediaQueueItem)

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val queueItem = base.toMediaQueueItem(mediaItem)
        val subs = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        val info = queueItem.media ?: return queueItem
        if (subs.isEmpty()) return queueItem
        val tracks = subs.mapIndexed { i, sub ->
            MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(sub.uri.toString())
                .setContentType(sub.mimeType ?: CastSubtitles.VTT)
                .apply {
                    sub.language?.let { setLanguage(it) }
                    sub.label?.let { setName(it) }
                }
                .build()
        }
        info.writer.setMediaTracks(tracks)
        val active = subs.indexOfFirst { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
        val builder = MediaQueueItem.Builder(queueItem)
        if (active >= 0) builder.setActiveTrackIds(longArrayOf(active + 1L))
        return builder.build()
    }
}
