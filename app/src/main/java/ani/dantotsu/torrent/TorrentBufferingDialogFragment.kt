package ani.dantotsu.torrent

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.BottomSheetTorrentBufferingBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.parsers.Video
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class TorrentBufferingDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTorrentBufferingBinding? = null
    private val binding get() = _binding!!

    private var media: Media? = null
    private var episode: Episode? = null
    private var video: Video? = null
    private var torrentUrl: String? = null

    private var bufferJob: Job? = null
    private var isCancelled = false
    private var isSuccess = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTorrentBufferingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val targetMedia = media
        val targetEp = episode
        val targetVideo = video
        val url = torrentUrl

        if (targetMedia == null || targetVideo == null || url.isNullOrBlank()) {
            dismissAllowingStateLoss()
            return
        }

        val epTitle = targetEp?.number?.let { "Ep. $it" } ?: ""
        val titleText = listOfNotNull(targetMedia.userPreferredName, epTitle.takeIf { it.isNotBlank() })
            .joinToString(" • ")
        binding.torrentBufferingSubtitle.text = titleText

        binding.torrentBufferingCancelButton.setOnClickListener {
            cancelBuffering(userInitiated = true)
        }

        startBuffering(targetMedia, targetEp, targetVideo, url)
    }

    private fun startBuffering(targetMedia: Media, targetEp: Episode?, targetVideo: Video, url: String) {
        val torrentManager = Injekt.get<TorrentServerManager>()
        if (!torrentManager.isAvailable()) {
            toast(R.string.torrent_addon_not_available)
            dismissAllowingStateLoss()
            return
        }

        bufferJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val index = if (url.contains("index=")) {
                    url.substringAfter("index=").toIntOrNull() ?: 0
                } else 0

                val currentTorrent = torrentManager.addTorrent(
                    url, targetVideo.quality.toString(), "", "", false
                )
                torrentManager.activeTorrentHash = currentTorrent.hash

                val result = torrentManager.prebufferWithResult(currentTorrent.hash!!, index) { progress ->
                    if (!isActive || isCancelled) {
                        return@prebufferWithResult false
                    }
                    lifecycleScope.launch(Dispatchers.Main) {
                        updateProgressUi(progress)
                    }
                    true
                }

                withContext(Dispatchers.Main) {
                    if (isCancelled || !isActive) return@withContext

                    if (result.ready) {
                        isSuccess = true
                        targetVideo.file.url = torrentManager.getLink(currentTorrent, index)
                        val currentAct = activity ?: currActivity()
                        if (currentAct != null && !currentAct.isFinishing && !currentAct.isDestroyed) {
                            Intent(currentAct, ExoplayerView::class.java).apply {
                                ExoplayerView.media = targetMedia
                                ExoplayerView.initialized = true
                                currentAct.startActivity(this)
                            }
                        }
                        dismissAllowingStateLoss()
                    } else {
                        toast(R.string.torrent_buffering_failed)
                        dismissAllowingStateLoss()
                    }
                }
            } catch (e: Exception) {
                Logger.log("TorrentBufferingDialogFragment: prebuffer failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!isCancelled) {
                        toast("Failed to buffer torrent: ${e.message}")
                        dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    private fun updateProgressUi(progress: PrebufferProgress) {
        if (_binding == null || isCancelled) return

        if (progress.totalPieces > 0 && progress.piecesReady > 0) {
            binding.torrentBufferingProgressBar.isIndeterminate = false
            binding.torrentBufferingProgressBar.max = 100
            binding.torrentBufferingProgressBar.progress = (progress.progressPercent * 100).toInt().coerceIn(0, 100)
        } else {
            binding.torrentBufferingProgressBar.isIndeterminate = true
        }

        binding.torrentBufferingStatus.text = progress.stateDescription
        binding.torrentBufferingSpeed.text = "⬇ ${formatBytesPerSecond(progress.downloadRateBytes)}"
        binding.torrentBufferingPeers.text = "👥 ${progress.numPeers} (${progress.numSeeds}s)"
        binding.torrentBufferingPieces.text = "Pieces: ${progress.piecesReady}/${progress.totalPieces}"
    }

    private fun formatBytesPerSecond(bytes: Long): String {
        if (bytes <= 0) return "0 KB/s"
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB/s", kb)
        }
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB/s", mb)
    }

    private fun cancelBuffering(userInitiated: Boolean) {
        if (isCancelled || isSuccess) return
        isCancelled = true
        bufferJob?.cancel()
        if (userInitiated) {
            snackString(getString(R.string.torrent_buffering_cancelled))
        }
        dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!isSuccess && !isCancelled) {
            cancelBuffering(userInitiated = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            media: Media,
            episode: Episode?,
            video: Video,
            torrentUrl: String
        ): TorrentBufferingDialogFragment {
            return TorrentBufferingDialogFragment().apply {
                this.media = media
                this.episode = episode
                this.video = video
                this.torrentUrl = torrentUrl
            }
        }
    }
}
