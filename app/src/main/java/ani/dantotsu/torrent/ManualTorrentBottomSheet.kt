package ani.dantotsu.torrent

import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.BottomSheetManualTorrentBinding
import ani.dantotsu.databinding.ItemManualTorrentFileBinding
import ani.dantotsu.download.video.Helper
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.kanade.tachiyomi.data.torrentServer.model.Torrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * MAX MERGE M7A: backend-agnostic manual torrent/magnet entry, anchored to
 * an explicit (anime, episode) chosen by the caller (the episode selector).
 *
 * Flow: magnet text / clipboard paste / `.torrent` file pick / pending
 * system-intent receipt → engine resolve (`addTorrent`, `Dispatchers.IO`)
 * → file list → explicit file choice → Play (existing
 * `TorrentBufferingDialogFragment`) or Download (existing
 * `Helper.startAnimeDownloadService` path, same rewrite + broadcast). No
 * new engine path, no new ownership: queue/purge/remembered-URL contracts
 * are exactly the ones extension-provided torrents already use.
 *
 * Nothing here guesses anime identity: without an anchored episode the
 * sheet refuses to open (caller toast). Pending receipts are memory-only
 * and TTL-bound ([ManualTorrentInbox]); linking happens only here, by
 * explicit tap.
 */
class ManualTorrentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManualTorrentBinding? = null
    private val binding get() = _binding!!

    private val model: MediaDetailsViewModel by activityViewModels()

    private var media: Media? = null
    private var episode: Episode? = null

    private var resolveJob: Job? = null
    private var handoffJob: Job? = null
    private var cancelled = false

    private var files: List<ManualTorrentFileEntry> = emptyList()
    private var selectedPosition = -1
    private var resolvedTorrent: Torrent? = null
    private var resolvedRawUrl: String? = null

    /** Sheet-picked temp `.torrent`, deleted after resolve or on dismiss. */
    private var ownedTempPath: String? = null

    /**
     * Staged temp backing the current resolution (sheet-picked or
     * inbox-consumed). Deleted once a handoff starts — the session holds
     * the parsed metadata and payload directory in memory by then — or on
     * dismiss when no handoff happens.
     */
    private var stagedTempPath: String? = null

    private fun deleteOwnedTemp() {
        ownedTempPath?.let { runCatching { File(it).delete() } }
        ownedTempPath = null
        stagedTempPath?.let { runCatching { File(it).delete() } }
        stagedTempPath = null
    }

    private var fileAdapter: FileAdapter? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult // canceled picker: stay on input
            onPickedDocument(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetManualTorrentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val targetMedia = media
        val targetEp = episode
        if (targetMedia == null || targetEp == null) {
            dismissAllowingStateLoss()
            return
        }
        binding.manualTorrentTitle.text =
            getString(R.string.manual_torrent_title) + " • Ep. ${targetEp.number}"

        binding.manualTorrentFileList.layoutManager = LinearLayoutManager(requireContext())
        fileAdapter = FileAdapter { position ->
            selectedPosition = position
            fileAdapter?.setSelected(position)
            updateActions()
        }
        binding.manualTorrentFileList.adapter = fileAdapter

        binding.manualTorrentPaste.setOnClickListener { pasteMagnet() }
        binding.manualTorrentAdd.setOnClickListener {
            val text = binding.manualTorrentMagnetInput.text?.toString().orEmpty()
            if (!MagnetValidation.isMagnetLink(text)) {
                snackString(getString(R.string.manual_torrent_invalid_magnet))
                return@setOnClickListener
            }
            resolvePayload(ManualTorrentPayload.Magnet(text.trim()), pendingGeneration = null)
        }
        binding.manualTorrentPickFile.setOnClickListener {
            pickFile.launch(arrayOf(ManualTorrentEntry.TORRENT_MIME, "application/octet-stream"))
        }
        binding.manualTorrentUsePending.setOnClickListener { usePending() }
        binding.manualTorrentPlay.setOnClickListener { handOffPlay() }
        binding.manualTorrentDownload.setOnClickListener { handOffDownload() }
        binding.manualTorrentCancel.setOnClickListener { dismissAllowingStateLoss() }

        refreshPendingRow()
        showInput(null)
    }

    override fun onResume() {
        super.onResume()
        refreshPendingRow()
    }

    // ---- input ----

    private fun pasteMagnet() {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (MagnetValidation.isMagnetLink(text)) {
            binding.manualTorrentMagnetInput.setText(text!!.trim())
        } else {
            snackString(getString(R.string.manual_torrent_clipboard_empty))
        }
    }

    private fun onPickedDocument(uri: Uri) {
        val ctx = requireContext()
        val mime = ctx.contentResolver.getType(uri)
        val name = ManualTorrentInbox.displayNameOf(ctx, uri)
        if (!ManualTorrentIntentDispatch.isPickableTorrentFile(mime, name)) {
            snackString(getString(R.string.manual_torrent_invalid_file))
            return
        }
        showResolving()
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch(Dispatchers.IO) {
            // HOLD1-C: exactly-once ownership of the staged copy. If this
            // task is cancelled (dismiss) before Main delivery takes over,
            // the finally block deletes the orphan; delivery sets
            // [ownedTempPath] first so the normal lifecycle owns it.
            var staged: String? = null
            try {
                staged = ManualTorrentInbox.stageContentUri(ctx, uri)
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    if (staged == null) {
                        showInput(getString(R.string.manual_torrent_file_too_large))
                        return@withContext
                    }
                    ownedTempPath?.let { runCatching { File(it).delete() } }
                    ownedTempPath = staged
                    staged = null // ownership transferred to the sheet lifecycle
                    resolvePayload(
                        ManualTorrentPayload.StagedFile(ownedTempPath ?: return@withContext),
                        pendingGeneration = null
                    )
                }
            } finally {
                staged?.let { runCatching { File(it).delete() } }
            }
        }
    }

    private fun usePending() {
        val generation = ManualTorrentInbox.currentGeneration()
        val payload = ManualTorrentInbox.peekLive()
        if (payload == null) {
            refreshPendingRow()
            return
        }
        resolvePayload(payload, pendingGeneration = generation)
    }

    // ---- resolve ----

    private fun resolvePayload(payload: ManualTorrentPayload, pendingGeneration: Long?) {
        val targetMedia = media ?: return
        val targetEp = episode ?: return
        showResolving()
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manager = Injekt.get<TorrentServerManager>()
                if (!manager.isAvailable()) {
                    withContext(Dispatchers.Main) {
                        toast(R.string.torrent_addon_not_available)
                        dismissAllowingStateLoss()
                    }
                    return@launch
                }
                val url = when (payload) {
                    is ManualTorrentPayload.Magnet -> payload.uri
                    is ManualTorrentPayload.ReceivedFile -> {
                        // Defensive: receipts are staged before offer, so a
                        // borrowed URI here means a dispatch change. Stage it
                        // like a picked document — never resolve in place.
                        val staged = try {
                            ManualTorrentInbox.stageContentUri(
                                requireContext().applicationContext, Uri.parse(payload.uriString)
                            )
                        } catch (_: Exception) {
                            null
                        }
                        if (staged == null) throw IllegalStateException("unreadable torrent file")
                        ownedTempPath?.let { runCatching { File(it).delete() } }
                        ownedTempPath = staged
                        staged
                    }
                    is ManualTorrentPayload.StagedFile -> {
                        if (!File(payload.locator).isFile) {
                            throw IllegalStateException("staged torrent file missing")
                        }
                        payload.locator
                    }
                }
                val title = "${targetMedia.mainName()} Ep. ${targetEp.number}"
                val torrent = manager.addTorrent(url, title, "", "", false)
                val entries = (torrent.file_stats ?: emptyList()).mapIndexed { idx, stat ->
                    ManualTorrentFileEntry(stat.id ?: idx, stat.path, stat.length)
                }
                if (entries.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (!cancelled) showInput(getString(R.string.manual_torrent_no_files))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    // HOLD1-D: a pending offer replaced mid-resolve must not
                    // be silently adopted. A null consume means the user is
                    // looking at files from their explicit earlier choice
                    // while a NEWER receipt stays pending (row reappears via
                    // refreshPendingRow) — nothing is lost, nothing is
                    // misattributed.
                    if (pendingGeneration != null) {
                        val adopted = ManualTorrentInbox.consume(pendingGeneration)
                        if (adopted == null) {
                            Logger.log("ManualTorrentBottomSheet: pending replaced during resolve")
                        }
                    }
                    // HOLD2-B: a successful re-resolve retires whatever staged
                    // file the previous resolution owned — including
                    // magnet-after-file (current null → previous deleted).
                    // Same-path re-resolve keeps the file.
                    val currentStaged = (payload as? ManualTorrentPayload.StagedFile)?.locator
                    stagedTempPath = retireSupersededStaged(stagedTempPath, currentStaged) {
                        runCatching { File(it).delete() }
                    }
                    ownedTempPath = null
                    resolvedTorrent = torrent
                    resolvedRawUrl = url
                    files = entries
                    selectedPosition = entries
                        .indexOfFirst { it == pickDefaultManualFile(entries) }
                        .coerceAtLeast(0)
                    fileAdapter?.submit(files, selectedPosition)
                    showFiles()
                }
            } catch (e: Exception) {
                // Fixed string only: engine messages can carry raw magnet /
                // file details (HOLD1-D), which must never reach logs.
                Logger.log("ManualTorrentBottomSheet: resolve failed")
                withContext(Dispatchers.Main) {
                    if (!cancelled) showInput(getString(R.string.manual_torrent_resolve_failed))
                }
            }
        }
    }

    // ---- handoff (existing flows only) ----

    private fun selectedEntry(): ManualTorrentFileEntry? =
        files.getOrNull(selectedPosition)

    private fun handOffPlay() {
        val targetMedia = media
        val targetEp = episode
        val url = resolvedRawUrl
        val entry = selectedEntry()
        val torrent = resolvedTorrent
        if (targetMedia == null || targetEp == null || url == null || entry == null || torrent == null) return
        // HOLD1-B: the player consumes episode.extractors[selectedExtractor]
        // [selectedVideo] (ExoplayerView), so the chosen file is ATTACHED to
        // the episode via the pure attachManualVideo seam (JVM-asserted) and
        // published through the model exactly like the selector fast path.
        // Warmup mirrors the selector (fire-and-forget prebuffer); no
        // re-resolve from the staged temp, so temp cleanup stays race-free.
        showHandoff()
        handoffJob?.cancel()
        val serverName = getString(R.string.manual_torrent_server_name)
        handoffJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manager = Injekt.get<TorrentServerManager>()
                if (!manager.isAvailable()) throw IllegalStateException("torrent unavailable")
                manager.prebufferTorrent(url, entry.index, targetMedia.mainName())
                val hash = torrent.hash ?: throw IllegalStateException("missing info-hash")
                val link = manager.getLink(hash, entry.index)
                attachManualVideo(targetEp, serverName, url, link)
                deleteOwnedTemp()
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    val act = activity ?: currActivity()
                    if (act == null || act.isFinishing || act.isDestroyed) {
                        showFiles()
                        return@withContext
                    }
                    model.setEpisode(targetEp, "manual torrent play")
                    Intent(act, ExoplayerView::class.java).apply {
                        ExoplayerView.media = targetMedia
                        ExoplayerView.initialized = true
                        act.startActivity(this)
                    }
                    dismissAllowingStateLoss()
                }
            } catch (e: Exception) {
                Logger.log("ManualTorrentBottomSheet: play handoff failed")
                withContext(Dispatchers.Main) {
                    if (!cancelled) {
                        snackString(getString(R.string.manual_torrent_resolve_failed))
                        showFiles()
                    }
                }
            }
        }
    }

    private fun handOffDownload() {
        val targetMedia = media
        val targetEp = episode
        val url = resolvedRawUrl
        val entry = selectedEntry()
        val torrent = resolvedTorrent
        if (targetMedia == null || targetEp == null || url == null || entry == null || torrent == null) return
        showHandoff()
        handoffJob?.cancel()
        handoffJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manager = Injekt.get<TorrentServerManager>()
                if (!manager.isAvailable()) throw IllegalStateException("torrent unavailable")
                // Mirror the selector download path: retire any foreign
                // active torrent, adopt this one, prebuffer, rewrite to the
                // localhost relay link, then enqueue through the standard
                // download service (queue/purge/remembered-URL included).
                manager.activeTorrentHash?.let { active ->
                    if (!active.equals(torrent.hash, ignoreCase = true)) {
                        manager.removeTorrent(active)
                    }
                }
                manager.activeTorrentHash = torrent.hash
                val result = manager.prebufferWithResult(torrent.hash!!, entry.index)
                result.lease?.close()
                val video = Video(null, VideoType.CONTAINER, url)
                video.file.url = manager.getLink(torrent, entry.index)
                deleteOwnedTemp()
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    val act = activity ?: currActivity()
                    if (act == null) {
                        showFiles()
                        return@withContext
                    }
                    Helper.startAnimeDownloadService(
                        act,
                        targetMedia.mainName(),
                        targetEp.number,
                        video,
                        mutableListOf(),
                        mutableListOf(),
                        targetMedia,
                        targetEp.thumb?.url ?: targetMedia.banner ?: targetMedia.cover
                    )
                    act.sendBroadcast(
                        Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
                            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, targetEp.number)
                            putExtra("mediaId", targetMedia.id)
                        }
                    )
                    dismissAllowingStateLoss()
                }
            } catch (e: Exception) {
                // Fixed string only (HOLD1-D): no provider/engine detail.
                Logger.log("ManualTorrentBottomSheet: download handoff failed")
                withContext(Dispatchers.Main) {
                    if (!cancelled) {
                        snackString(getString(R.string.manual_torrent_resolve_failed))
                        showFiles()
                    }
                }
            }
        }
    }

    // ---- UI states ----

    private fun refreshPendingRow() {
        // HOLD1-D: visible whenever a live receipt exists, even with
        // resolved files showing — a newer offer must never be hidden.
        val live = _binding?.let { ManualTorrentInbox.peekLive() }
        binding.manualTorrentPendingRow.visibility =
            if (live != null) View.VISIBLE else View.GONE
    }

    private fun showInput(message: String?) {
        binding.manualTorrentStatus.visibility = if (message != null) View.VISIBLE else View.GONE
        if (message != null) binding.manualTorrentStatus.text = message
        binding.manualTorrentProgress.visibility = View.GONE
        binding.manualTorrentFileList.visibility = View.GONE
        binding.manualTorrentActionRow.visibility = View.GONE
        setInputsEnabled(true)
    }

    private fun showResolving() {
        binding.manualTorrentStatus.visibility = View.GONE
        binding.manualTorrentProgress.visibility = View.VISIBLE
        binding.manualTorrentFileList.visibility = View.GONE
        binding.manualTorrentActionRow.visibility = View.GONE
        setInputsEnabled(false)
    }

    private fun showFiles() {
        binding.manualTorrentStatus.visibility = View.GONE
        binding.manualTorrentProgress.visibility = View.GONE
        binding.manualTorrentFileList.visibility = View.VISIBLE
        binding.manualTorrentActionRow.visibility = View.VISIBLE
        setInputsEnabled(true)
        refreshPendingRow()
        updateActions()
    }

    private fun showHandoff() {
        binding.manualTorrentProgress.visibility = View.VISIBLE
        setInputsEnabled(false)
        binding.manualTorrentPlay.isEnabled = false
        binding.manualTorrentDownload.isEnabled = false
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.manualTorrentMagnetInput.isEnabled = enabled
        binding.manualTorrentPaste.isEnabled = enabled
        binding.manualTorrentAdd.isEnabled = enabled
        binding.manualTorrentPickFile.isEnabled = enabled
        binding.manualTorrentUsePending.isEnabled = enabled
    }

    private fun updateActions() {
        val ready = selectedEntry() != null && handoffJob?.isActive != true
        binding.manualTorrentPlay.isEnabled = ready
        binding.manualTorrentDownload.isEnabled = ready
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        cancelled = true
        resolveJob?.cancel()
        handoffJob?.cancel()
        deleteOwnedTemp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class FileAdapter(
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {

        private var items: List<ManualTorrentFileEntry> = emptyList()
        private var selected = -1

        fun submit(newItems: List<ManualTorrentFileEntry>, selectedPosition: Int) {
            items = newItems
            selected = selectedPosition
            notifyDataSetChanged()
        }

        fun setSelected(position: Int) {
            val prev = selected
            selected = position
            if (prev != -1) notifyItemChanged(prev)
            notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemManualTorrentFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.binding.manualFileName.text = entry.displayName
            holder.binding.manualFileSize.text = formatBytes(entry.length)
            val isSelected = position == selected
            holder.binding.manualFileRoot.isActivated = isSelected
            holder.binding.manualFileSelected.visibility =
                if (isSelected) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener { onPick(position) }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val binding: ItemManualTorrentFileBinding) :
            RecyclerView.ViewHolder(binding.root)

        companion object {
            fun formatBytes(bytes: Long): String {
                if (bytes <= 0) return "0 B"
                val kb = bytes / 1024.0
                if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
                val mb = kb / 1024.0
                if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
                return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            }
        }
    }

    companion object {
        fun newInstance(media: Media, episode: Episode): ManualTorrentBottomSheet {
            return ManualTorrentBottomSheet().apply {
                this.media = media
                this.episode = episode
            }
        }
    }
}
