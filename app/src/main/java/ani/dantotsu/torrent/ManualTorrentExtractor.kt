package ani.dantotsu.torrent

import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoContainer
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer

/**
 * MAX MERGE M7A: synthetic extractor carrying a manually entered torrent's
 * chosen file into the existing play/download flows.
 *
 * No new engine path: the contained [Video] carries the raw magnet URI or
 * staged `.torrent` path exactly like extension-provided torrent URLs, so
 * `TorrentBufferingDialogFragment` and the selector download mirror resolve
 * it through the same `addTorrent → prebuffer → localhost link` sequence.
 * Subtitles/audio stay empty — manual torrents carry no sidecar tracks, and
 * M5 subtitle fetching still applies at playback.
 */
class ManualTorrentExtractor(
    serverName: String,
    videos: List<Video>,
) : VideoExtractor() {
    override val server: VideoServer = VideoServer(serverName, "")

    init {
        this.videos = videos
    }

    override suspend fun extract(): VideoContainer {
        return VideoContainer(videos, subtitles, audioTracks)
    }
}
