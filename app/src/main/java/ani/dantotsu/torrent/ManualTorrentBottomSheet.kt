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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.BottomSheetManualTorrentBinding
import ani.dantotsu.databinding.ItemManualTorrentFileBinding
import ani.dantotsu.download.video.Helper
import ani.dantotsu.media.Media
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
            val staged = ManualTorrentInbox.stageContentUri(ctx, uri)
            withContext(Dispatchers.Main) {
                if (cancelled) {
                    staged?.let { runCatching { File(it).delete() } }
                    return@withContext
                }
                if (staged == null) {
                    showInput(getString(R.string.manual_torrent_file_too_large))
                    return@withContext
                }
                ownedTempPath?.let { runCatching { File(it).delete() } }
                ownedTempPath = staged
                resolvePayload(ManualTorrentPayload.StagedFile(staged), pendingGeneration = null)
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
                    is ManualTorrentPayload.StagedFile -> {
                        val file = File(payload.locator)
                        if (file.exists()) {
                            payload.locator
                        } else {
                            // Defensive: an unstaged URI that bypassed the inbox
                            // (normally staged at receipt). Never trust grant lifetime.
                            val staged = try {
                                ManualTorrentInbox.stageContentUri(
                                    requireContext().applicationContext, Uri.parse(payload.locator)
                                )
                            } catch (_: Exception) {
                                null
                            }
                            if (staged == null) throw IllegalStateException("unreadable torrent file")
                            ownedTempPath?.let { runCatching { File(it).delete() } }
                            ownedTempPath = staged
                            staged
                        }
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
                    // Resolution fulfilled the pending offer: consume it. Staged
                    // temps are deleted after handoff (the session holds
                    // everything in memory by then) or on dismiss.
                    if (pendingGeneration != null) {
                        ManualTorrentInbox.consume(pendingGeneration)
                    }
                    if (payload is ManualTorrentPayload.StagedFile) {
                        stagedTempPath = payload.locator
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
                Logger.log("ManualTorrentBottomSheet: resolve failed: ${e.message}")
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
        // Mirror the selector fast path: background prebuffer warmup plus an
        // immediate localhost-link rewrite, then launch the player. The
        // player adopts the prebuffer lease via the localhost URI (existing
        // DantotsuPlayerManager contract). No re-resolve from the staged
        // temp file, so temp cleanup below is race-free.
        showHandoff()
        handoffJob?.cancel()
        handoffJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manager = Injekt.get<TorrentServerManager>()
                if (!manager.isAvailable()) throw IllegalStateException("torrent unavailable")
                manager.prebufferTorrent(url, entry.index, targetMedia.mainName())
                val hash = torrent.hash ?: throw IllegalStateException("missing info-hash")
                val video = Video(null, VideoType.CONTAINER, url)
                video.file.url = manager.getLink(hash, entry.index)
                deleteOwnedTemp()
                withContext(Dispatchers.Main) {
                    if (cancelled) return@withContext
                    val act = activity ?: currActivity()
                    if (act == null || act.isFinishing || act.isDestroyed) {
                        showFiles()
                        return@withContext
                    }
                    Intent(act, ExoplayerView::class.java).apply {
                        ExoplayerView.media = targetMedia
                        ExoplayerView.initialized = true
                        act.startActivity(this)
                    }
                    dismissAllowingStateLoss()
                }
            } catch (e: Exception) {
                Logger.log("ManualTorrentBottomSheet: play handoff failed: ${e.message}")
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
                Logger.log("ManualTorrentBottomSheet: download handoff failed: ${e.message}")
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
        val live = _binding?.let { ManualTorrentInbox.peekLive() }
        binding.manualTorrentPendingRow.visibility =
            if (live != null && resolvedTorrent == null) View.VISIBLE else View.GONE
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
