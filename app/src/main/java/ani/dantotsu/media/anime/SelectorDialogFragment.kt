package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
//import androidx.compose.ui.test.performClick
//import androidx.compose.ui.geometry.isEmpty
//import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
//import androidx.glance.visibility
//import androidx.glance.visibility
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.torrent.TorrentBufferingDialogFragment
import ani.dantotsu.torrent.TorrentServerManager
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.copyToClipboard
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.databinding.ItemSourceReleaseBinding
import ani.dantotsu.databinding.ItemSourceStreamBinding
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.video.Helper
import ani.dantotsu.download.video.findBestVideoForDownload
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBars
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.SubtitleDownloader
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Download.download
import ani.dantotsu.parsers.Subtitle
import ani.dantotsu.parsers.Video
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoType
import ani.dantotsu.parsers.hasExplicitServer
import ani.dantotsu.parsers.preferredPlayableFallback
import ani.dantotsu.media.anime.selector.SourceMetaIcon
import ani.dantotsu.media.anime.selector.SourcePillKind
import ani.dantotsu.media.anime.selector.SourceRowPill
import ani.dantotsu.media.anime.selector.SourceRowSummarizer
import ani.dantotsu.media.anime.selector.SourceRowViewModel
import ani.dantotsu.media.anime.selector.AudioKind
import ani.dantotsu.media.anime.selector.AudioKindDetector
import ani.dantotsu.media.anime.selector.AudioKindFilter
import ani.dantotsu.media.anime.selector.BestReason
import ani.dantotsu.media.anime.selector.CompactDetails
import ani.dantotsu.media.anime.selector.EditotsuInternalData
import ani.dantotsu.media.anime.selector.PickerEntry
import ani.dantotsu.media.anime.selector.PickerList
import ani.dantotsu.media.anime.selector.PickerListBuilder
import ani.dantotsu.media.anime.selector.PickerRow
import ani.dantotsu.media.anime.selector.QualityVariant
import ani.dantotsu.media.anime.selector.ResolutionLabel
import ani.dantotsu.media.anime.selector.RowRef
import ani.dantotsu.media.anime.selector.SeederHealth
import ani.dantotsu.media.anime.selector.SourceRowClassifier
import ani.dantotsu.media.anime.selector.TransportLabel
import ani.dantotsu.media.anime.selector.FamilyAttemptSuppression
import ani.dantotsu.media.anime.selector.FamilyPayload
import ani.dantotsu.media.anime.selector.RecoveryCoordinator
import ani.dantotsu.media.anime.selector.RecoveryResult
import ani.dantotsu.media.anime.selector.RememberedSelectionPolicy
import ani.dantotsu.media.anime.selector.SelectedFamilyStore
import ani.dantotsu.media.anime.selector.SelectedKeyStore
import ani.dantotsu.media.anime.selector.StableCandidateBuilder
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsAddonActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat


class SelectorDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!
    val model: MediaDetailsViewModel by activityViewModels()
    private var scope: CoroutineScope = lifecycleScope
    private var media: Media? = null
    private var episode: Episode? = null
    private var prevEpisode: String? = null
    private var makeDefault = false
    private var selected: String? = null
    /**
     * Attempt-local family auto-resolution suppression. After the
     * family resolution fires once for an episode and falls through
     * to the manual picker, subsequent `failToList` calls in the
     * same attempt will NOT re-attempt the family resolution. The
     * persisted family preference itself is left intact.
     */
    private val familyAttemptedForEpisode = FamilyAttemptSuppression()
    private var launch: Boolean? = null
    private var isDownloadMenu: Boolean? = null
    private var episodes: ArrayList<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            selected = it.getString("server")
            launch = it.getBoolean("launch", true)
            prevEpisode = it.getString("prev")
            isDownloadMenu = it.getBoolean("isDownload")
            episodes = it.getStringArrayList("episodes")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        return binding.root
    }

    interface EpisodeDownloadListener {
        fun onFinishingUserSelection(selectedServerName: String,
                                     selectedSubtitles: MutableList<String>,
                                     selectedAudioTracks: MutableList<String>)
    }
    class EpisodeDownloadHandler(private val _onFinishingUserSelection: (String, MutableList<String>, MutableList<String>) -> Unit)
        : EpisodeDownloadListener{
        override fun onFinishingUserSelection(selectedServerName: String,
                                              selectedSubtitles: MutableList<String>,
                                              selectedAudioTracks: MutableList<String>) {
            _onFinishingUserSelection(selectedServerName, selectedSubtitles, selectedAudioTracks)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var loaded = false
        model.getMedia().observe(viewLifecycleOwner) { m ->
            media = m
            if (media != null && !loaded) {
                loaded = true

                // Initialize the REAL remembered-choice preference
                // BEFORE any remembered auto-selection logic can
                // read it. The remembered selected-server auto path
                // below runs before initializeVideoServerSelector()
                // (which also refreshes/binds this same preference
                // for the manual picker UI), so without this the
                // auto path would always see the field default
                // `false` even when the setting is actually ON.
                makeDefault = PrefManager.getVal(PrefName.MakeDefault)

                fun fail(resId: Int){
                    ContextCompat.getMainExecutor(context ?: currContext() ?: return).execute {
                        snackString(getString(resId))
                        tryWith {
                            dismissAllowingStateLoss()
                        }
                    }
                }
                fun initializeVideoServerSelector(ep: Episode, onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) {
                    binding.selectorRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        bottomMargin = navBarHeight
                    }
                    binding.selectorRecyclerView.adapter = null
                    binding.selectorProgressBar.visibility = View.VISIBLE
                    binding.selectorEmpty.visibility = View.GONE
                    binding.selectorRecyclerView.visibility = View.VISIBLE
                    binding.selectorHeaderTitle.text = getString(R.string.picker_episode_title, ep.number)
                    val sourceName = model.watchSources?.get(media?.selected?.sourceIndex ?: 0)?.name.orEmpty()
                    binding.selectorHeaderMeta.text = getString(R.string.picker_meta_loading, sourceName)
                    makeDefault = PrefManager.getVal(PrefName.MakeDefault)
                    binding.selectorMakeDefault.isChecked = makeDefault
                    binding.selectorMakeDefault.setOnClickListener {
                        makeDefault = binding.selectorMakeDefault.isChecked
                        PrefManager.setVal(PrefName.MakeDefault, makeDefault)
                        (binding.selectorRecyclerView.adapter as? ExtractorAdapter)?.rebuild()
                    }
                    // Source Picker v3 Final: nothing found stays in the sheet
                    // with Retry / Change source instead of snackbar + dismiss.
                    fun showEmpty() {
                        binding.selectorProgressBar.visibility = View.GONE
                        binding.selectorRecyclerView.visibility = View.GONE
                        binding.selectorFilters.visibility = View.GONE
                        binding.selectorNotice.visibility = View.GONE
                        binding.selectorEmpty.visibility = View.VISIBLE
                        binding.selectorEmptyTitle.text = getString(R.string.picker_empty_title, ep.number)
                        binding.selectorEmptyMessage.text = getString(R.string.picker_empty_message, sourceName)
                        binding.selectorEmptyRetry.setOnClickListener {
                            ep.allStreams = false
                            initializeVideoServerSelector(ep, onEpisodeDownloadHandler)
                        }
                        binding.selectorEmptyChangeSource.setOnClickListener {
                            tryWith { dismissAllowingStateLoss() }
                        }
                    }
                    binding.selectorRecyclerView.layoutManager =
                        LinearLayoutManager(
                            requireActivity(),
                            LinearLayoutManager.VERTICAL,
                            false
                        )
                    val adapter = ExtractorAdapter(onEpisodeDownloadHandler)
                    binding.selectorRecyclerView.adapter = adapter
                    if (!ep.allStreams) {
                        ep.extractorCallback = { extractor ->
                            scope.launch(Dispatchers.Main) {
                                if (_binding == null || !isAdded) return@launch
                                adapter.add(extractor)
                                binding.selectorProgressBar.visibility = View.GONE
                            }
                        }
                        scope.launch(Dispatchers.IO) {
                            model.loadEpisodeVideos(ep, media!!.selected!!.sourceIndex)
                            withContext(Dispatchers.Main) {
                                if (_binding == null || !isAdded) return@withContext
                                binding.selectorProgressBar.visibility = View.GONE
                                if (adapter.links.isEmpty()) {
                                    showEmpty()
                                    return@withContext
                                }
                                if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                                    adapter.performClick(0)
                                }
                            }
                        }
                    } else {
                        val epKey = media?.anime?.episodes?.getEpisodeKey(ep.number) ?: media?.anime?.selectedEpisode
                        if (epKey != null) {
                            media!!.anime?.episodes?.set(epKey, ep)
                        }
                        adapter.addAll(ep.extractors)
                        if (ep.extractors.isNullOrEmpty()) {
                            showEmpty()
                            return
                        }
                        if (model.watchSources!!.isDownloadedSource(media?.selected!!.sourceIndex)) {
                            adapter.performClick(0)
                        }
                        binding.selectorProgressBar.visibility = View.GONE
                    }
                }
                suspend fun loadEpisodeSingleServer(episodeName: String, selectedServerName: String): Boolean{
                    val ep = media?.anime?.episodes?.getEpisode(episodeName) ?: media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode)
                    if (ep == null) return false
                    episode = ep

                    var success = false
                    scope.launch(Dispatchers.IO) {
                        success = model.loadEpisodeSingleVideo(
                            ep,
                            media!!.selected!!,
                            selectedServerName = selectedServerName
                        )
                    }.join()
                    Log.d("AnimeDownloader", "Loading Episode Server State: $success")
                    return success
                }
                fun startEpisodeDownload(episodeName: String, selectedServerName: String,
                                         selectedSubtitles: MutableList<String>,
                                         selectedAudioTracks: MutableList<String>){
                    fun downloadUsingSingleServer(extractor: VideoExtractor, currentEp: Episode): Boolean {
                        currentEp.selectedExtractor = extractor.server.name
                        // MAX MERGE M3 (upstream e954d9f1): preferred-resolution
                        // pick for downloads; falls back to clamped manual index.
                        val sourceName = model.watchSources?.get(media?.selected?.sourceIndex ?: 0)?.name
                        val preferredResolutions = PrefManager.getPreferredDownloadResolutions(sourceName)
                        val autoPriority = PrefManager.getVal<Boolean>(PrefName.AutoSelectResolutionPriority)
                        val bestVideo = if (autoPriority && extractor.videos.isNotEmpty()) {
                            findBestVideoForDownload(extractor.videos, preferredResolutions)
                        } else null
                        val chosenVideoIndex = if (bestVideo != null) {
                            extractor.videos.indexOf(bestVideo).takeIf { it >= 0 } ?: 0
                        } else {
                            if (currentEp.selectedVideo >= extractor.videos.size) 0 else currentEp.selectedVideo
                        }
                        currentEp.selectedVideo = chosenVideoIndex
                        if (currentEp.selectedVideo >= extractor.videos.size) {
                            currentEp.selectedVideo = 0
                        }
                        val epKey = media?.anime?.episodes?.getEpisodeKey(currentEp.number) ?: currentEp.number
                        media?.anime?.episodes?.get(epKey)?.let { mapEp ->
                            mapEp.selectedExtractor = extractor.server.name
                            mapEp.selectedVideo = currentEp.selectedVideo
                        }
                        if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
                            val act = activity ?: currActivity()
                            if (act != null) {
                                download(
                                    act,
                                    currentEp,
                                    media!!.userPreferredName
                                )
                            }
                        }
                        else {
                            val downloadAddonManager: DownloadAddonManager = Injekt.get()
                            if (!downloadAddonManager.isAvailable()) {
                                val context = context ?: currContext()
                                context?.customAlertDialog()?.apply {
                                    setTitle(R.string.download_addon_not_installed)
                                    setMessage(R.string.would_you_like_to_install)
                                    setPosButton(R.string.yes) {
                                        context.startActivity(
                                            Intent(context, SettingsAddonActivity::class.java)
                                        )
                                    }
                                    setNegButton(R.string.no) { }
                                    show()
                                }
                                return false
                            }
                            val subtitles = extractor.subtitles
                            val subtitlesToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            subtitles.forEach {
                                if(it.language in selectedSubtitles || selectedSubtitles.isEmpty()){
                                    subtitlesToDownload.add(Pair(it.file.url, it.language))
                                }
                            }

                            val audioTracks = extractor.audioTracks
                            val audioTracksToDownload: MutableList<Pair<String, String>> = mutableListOf()
                            audioTracks.forEach {
                                if(it.lang in selectedAudioTracks || selectedAudioTracks.isEmpty()){
                                    audioTracksToDownload.add(Pair(it.url, it.lang))
                                }
                            }

                            val selectedVideo =
                                if (extractor.videos.isNotEmpty()) {
                                    if (currentEp.selectedVideo in extractor.videos.indices) extractor.videos[currentEp.selectedVideo] else extractor.videos[0]
                                } else null
                            val activity = activity ?: currActivity()
                            selectedVideo?.file?.url?.let { url ->
                                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                                    val torrentManager = Injekt.get<TorrentServerManager>()
                                    if (!torrentManager.isAvailable()) {
                                        toast(R.string.torrent_addon_not_available)
                                        return false
                                    }
                                    runBlocking {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                torrentManager.activeTorrentHash?.let {
                                                    torrentManager.removeTorrent(it)
                                                }
                                                val index = if (url.contains("index=")) {
                                                    url.substringAfter("index=")
                                                        .toIntOrNull() ?: 0
                                                } else 0
                                                Logger.log("Sending: ${url}, ${selectedVideo.quality}, $index")
                                                val currentTorrent = torrentManager.addTorrent(
                                                    url,
                                                    selectedVideo.quality.toString(),
                                                    "",
                                                    "",
                                                    false
                                                )
                                                torrentManager.activeTorrentHash =
                                                    currentTorrent.hash

                                                // Pre-buffer the first piece and release temporary check lease
                                                val prebufferResult = torrentManager.prebufferWithResult(currentTorrent.hash!!, index)
                                                prebufferResult.lease?.close()

                                                selectedVideo.file.url =
                                                    torrentManager.getLink(currentTorrent, index)
                                                Logger.log("Received: ${selectedVideo.file.url}")
                                            }

                                        } catch (e: Exception) {
                                            Injekt.get<CrashlyticsInterface>()
                                                .logException(e)
                                            Logger.log(e)
                                            toast("Error starting video: ${e.message}")
                                            return@runBlocking
                                        }
                                    }
                                }
                            }
                            val act = activity ?: currActivity()
                            if (selectedVideo != null && act != null) {
                                Helper.startAnimeDownloadService(
                                    act,
                                    media!!.mainName(),
                                    currentEp.number,
                                    selectedVideo,
                                    subtitlesToDownload,
                                    audioTracksToDownload,
                                    media,
                                    currentEp.thumb?.url ?: media!!.banner
                                    ?: media!!.cover
                                )
                                val intent =
                                    Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
                                        putExtra(
                                            AnimeWatchFragment.EXTRA_EPISODE_NUMBER,
                                            currentEp.number,
                                        )
                                        putExtra("mediaId", media?.id)
                                    }
                                act.sendBroadcast(intent)
                            } else if (selectedVideo == null) {
                                snackString(R.string.no_video_selected)
                            }
                        }
                        return true
                    }

                    val ep = media?.anime?.episodes?.getEpisode(episodeName) ?: media?.anime?.episodes?.get(episodeName)
                    if (ep == null) {
                        fail(R.string.auto_select_server_error)
                        return
                    }
                    val epKey = media?.anime?.episodes?.getEpisodeKey(episodeName) ?: episodeName
                    media?.anime?.selectedEpisode = epKey
                    episode = ep

                    Log.d("AnimeDownloader", "Downloading Episode: ${ep.number}, server: $selectedServerName")

                    val selectedExtractor = ep.extractors?.find { it.server.name == selectedServerName }
                    if (selectedExtractor == null)
                        fail(R.string.auto_select_server_error)
                    else {
                        media!!.anime?.episodes?.set(epKey, ep)
                        if(!downloadUsingSingleServer(selectedExtractor, ep))
                            fail(R.string.auto_select_server_error)
                    }
                }

                Log.d("AnimeDownloader", "Selected Server for watching: $selected")
                if(episodes.isNullOrEmpty()){
                    fail(R.string.empty_episodes_list)
                }
                if (isDownloadMenu == false) {
                    val rawKey = episodes?.get(0)
                    val ep = media?.anime?.episodes?.getEpisode(rawKey)
                    val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
                    media?.anime?.selectedEpisode = actualKey
                    episode = ep
                    if (ep != null) {
                        if (selected != null && media?.format != "LOCAL") {
                            binding.selectorListContainer.visibility = View.GONE
                            binding.selectorAutoListContainer.visibility = View.VISIBLE
                            binding.selectorAutoText.text = selected?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: selected
                            binding.selectorCancel.setOnClickListener {
                                media!!.selected!!.server = null
                                model.saveSelected(media!!.id, media!!.selected!!)
                                SelectedKeyStore.save(media!!.id, null)
                                tryWith {
                                    dismissAllowingStateLoss()
                                }
                            }

                            fun failToList() {
                                // Recovery after a failed auto-list path:
                                //
                                // 1. If a stored exact key OR a stored family
                                //    preference is present, load all extractors
                                //    and run `RecoveryCoordinator.resolve`.
                                // 2. If a family auto-resolve was already
                                //    attempted for THIS episode in THIS dialog
                                //    attempt, do NOT attempt it again here. The
                                //    persisted family preference is left intact.
                                // 3. On Resolved, drive the auto-list with the
                                //    resolved candidate (write SelectedKey,
                                //    Selected.server, selectedExtractor).
                                // 4. On NoMatch / Ambiguous / suppressed,
                                //    clear Selected.server + SelectedKey,
                                //    KEEP family, fall back to manual picker.
                                val storedKey = SelectedKeyStore.load(media!!.id)
                                val storedFamily = SelectedFamilyStore.load(media!!.id)
                                if ((storedKey != null || storedFamily != null)
                                    && familyAttemptedForEpisode.shouldAttempt(actualKey)
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        if (!ep.allStreams) {
                                            model.loadEpisodeVideos(
                                                ep, media!!.selected!!.sourceIndex,
                                            )
                                        }
                                        withContext(Dispatchers.Main) {
                                            val servers = ep.extractors?.map { it.server }
                                                ?: emptyList()
                                            val recovery = RecoveryCoordinator.resolve(
                                                storedExactKey = storedKey,
                                                storedFamily = storedFamily,
                                                selectedName = null,
                                                servers = servers,
                                            )
                                            if (recovery is RecoveryResult.Resolved) {
                                                val chosen = recovery.server.name
                                                // One coherent auto-recovery
                                                // transaction. The runtime handle
                                                // is ALWAYS applied below.
                                                // Remembered persistence follows
                                                // the real MakeDefault setting
                                                // (loaded before the auto branch):
                                                // ON persists the legacy server
                                                // choice and WRITEs/CLEARs the
                                                // exact slot; OFF persists
                                                // nothing new and leaves the
                                                // family preference untouched.
                                                val autoPersistence =
                                                    RememberedSelectionPolicy.autoRecoveryPersistence(
                                                        makeDefault, recovery.exactKey,
                                                    )
                                                if (autoPersistence.persistLegacyServer) {
                                                    media!!.selected!!.server = chosen
                                                    media!!.selected!!.video = 0
                                                    model.saveSelected(
                                                        media!!.id, media!!.selected!!,
                                                    )
                                                }
                                                when (autoPersistence.exactAction) {
                                                    RememberedSelectionPolicy.ExactSlotAction.WRITE ->
                                                        SelectedKeyStore.save(
                                                            media!!.id, recovery.exactKey,
                                                        )
                                                    RememberedSelectionPolicy.ExactSlotAction.CLEAR ->
                                                        SelectedKeyStore.save(media!!.id, null)
                                                    RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE -> Unit
                                                }
                                                media!!.anime!!.episodes
                                                    ?.getEpisode(actualKey)?.selectedExtractor = chosen
                                                media!!.anime!!.episodes
                                                    ?.getEpisode(actualKey)?.selectedVideo = 0
                                                startExoplayer(media!!)
                                            } else {
                                                snackString(getString(R.string.auto_select_server_error))
                                                media!!.selected!!.server = null
                                                model.saveSelected(
                                                    media!!.id, media!!.selected!!,
                                                )
                                                SelectedKeyStore.save(media!!.id, null)
                                                binding.selectorAutoListContainer.visibility = View.GONE
                                                binding.selectorListContainer.visibility = View.VISIBLE
                                                initializeVideoServerSelector(ep)
                                            }
                                        }
                                    }
                                } else {
                                    snackString(getString(R.string.auto_select_server_error))
                                    media!!.selected!!.server = null
                                    model.saveSelected(media!!.id, media!!.selected!!)
                                    SelectedKeyStore.save(media!!.id, null)
                                    binding.selectorAutoListContainer.visibility = View.GONE
                                    binding.selectorListContainer.visibility = View.VISIBLE
                                    initializeVideoServerSelector(ep)
                                }
                            }

                            fun load() {
                                // FINAL-PORT C4 correction (HOLD1-A): the
                                // fallback chain below runs only after the
                                // guard above attempted the explicit named
                                // load. Preferred biases safe fallback only.
                                // Persistence still follows the local
                                // makeDefault policy, and never anchors a
                                // fallback choice: on fallback the runtime
                                // follows the match but stored defaults keep
                                // pointing at the explicit selection.
                                val matchedExtractor =
                                    if (model.watchSources!!.isDownloadedSource(media!!.selected!!.sourceIndex)) {
                                        ep.extractors?.firstOrNull()
                                    } else {
                                        ep.extractors?.find { it.server.name == selected }
                                            ?: preferredPlayableFallback(ep.extractors)
                                            ?: ep.extractors?.firstOrNull()
                                    }

                                val size = matchedExtractor?.videos?.size

                                if (matchedExtractor != null && size != null && size > 0) {
                                    val currentKey = media!!.anime!!.selectedEpisode ?: actualKey
                                    media!!.anime!!.episodes?.getEpisode(currentKey)?.selectedExtractor = matchedExtractor.server.name
                                    val chosenVideo = if (media!!.selected!!.video in 0 until size) media!!.selected!!.video else 0
                                    media!!.anime!!.episodes?.getEpisode(currentKey)?.selectedVideo = chosenVideo
                                    media!!.selected!!.server = matchedExtractor.server.name
                                    if (makeDefault && matchedExtractor.server.name == selected) {
                                        val provider = StableCandidateBuilder.providerOf(matchedExtractor.server)
                                        val exact = StableCandidateBuilder.exactKey(provider, matchedExtractor.server)
                                        SelectedKeyStore.save(media!!.id, exact)
                                    }
                                    startExoplayer(media!!)
                                } else failToList()
                            }

                            // FINAL-PORT C4 correction (HOLD1-A): a cached
                            // preferred extractor must not mask a missing
                            // explicit selection — the named single-server
                            // load is attempted first; fallback happens in
                            // load() only afterwards.
                            val hasExplicit = hasExplicitServer(ep.extractors, selected)

                            if (!hasExplicit && selected != null) {
                                scope.launch{
                                    val success = withContext(Dispatchers.IO){
                                        loadEpisodeSingleServer(ep.number, selected!!)
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (_binding == null || !isAdded) return@withContext
                                        if (!success) {
                                            failToList()
                                        } else {
                                            load()
                                        }
                                    }
                                }
                            } else load()
                        }
                        else
                            initializeVideoServerSelector(ep)
                    }
                }
                else {
                    binding.selectorMakeDefault.visibility = View.GONE
                    val rawKey = episodes?.get(0)
                    val ep = media?.anime?.episodes?.getEpisode(rawKey)
                    val actualKey = media?.anime?.episodes?.getEpisodeKey(rawKey) ?: rawKey
                    media?.anime?.selectedEpisode = actualKey
                    episode = ep

                    if (ep != null) {
                        val downloadHandler =
                            EpisodeDownloadHandler(_onFinishingUserSelection = { selectedServerName,
                                                                                 selectedSubtitles,
                                                                                 selectedAudioTracks ->
                                binding.selectorListContainer.visibility = View.GONE
                                binding.selectorAutoListContainer.visibility = View.VISIBLE
                                binding.selectorTitle.text = "Starting Download"
                                binding.selectorAutoText.text =
                                    "Starting download using server:\n$selectedServerName"
                                binding.selectorCancel.visibility = View.GONE

                                scope.launch(Dispatchers.IO) {
                                    val currentEpisodes = episodes ?: return@launch
                                    val serverSelectionScope = CoroutineScope(Dispatchers.IO)
                                    val serverSelectionTasks = mutableListOf<Deferred<Unit>>()
                                    for (episodeName in currentEpisodes.drop(1)) {
                                        serverSelectionTasks.add(serverSelectionScope.async {
                                            if(!loadEpisodeSingleServer(episodeName, selectedServerName)){
                                                Log.d("AnimeDownloader", "Error loading server $selectedServerName for episode $episodeName")
                                                fail(R.string.auto_select_server_error)
                                            }
                                        })
                                    }
                                    serverSelectionTasks.awaitAll()

                                    for(episodeName in currentEpisodes){
                                        startEpisodeDownload(episodeName, selectedServerName, selectedSubtitles, selectedAudioTracks)
                                    }
                                    withContext(Dispatchers.Main) {
                                        tryWith{
                                            dismissAllowingStateLoss()
                                        }
                                    }
                                }
                            })
                        initializeVideoServerSelector(ep, downloadHandler)
                    }
                }
            }
        }
        super.onViewCreated(view, savedInstanceState)
    }

    private val externalPlayerResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        Logger.log(result.data.toString())
    }

    private fun exportMagnetIntent(episode: Episode, video: Video): Intent {
        val amnis = "com.amnis"
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(amnis, "$amnis.gui.player.PlayerActivity")
            data = Uri.parse(video.file.url)
            putExtra("title", "${media?.name} - ${episode.title}")
            putExtra("position", 0)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("secure_uri", true)
            val headersArray = arrayOf<String>()
            video.file.headers.forEach {
                headersArray.plus(arrayOf(it.key, it.value))
            }
            putExtra("headers", headersArray)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("UnsafeOptInUsageError")
    fun startExoplayer(media: Media) {
        if (!isAdded || _binding == null) return
        prevEpisode = null

        episode?.let { ep ->
            val video = ep.extractors?.find {
                it.server.name == ep.selectedExtractor
            }?.videos?.getOrNull(ep.selectedVideo)
            video?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentManager = Injekt.get<TorrentServerManager>()
                    if (torrentManager.isAvailable()) {
                        val act = activity ?: currActivity()
                        dismissAllowingStateLoss()
                        if (launch == true) {
                            stopAddingToList()
                            val epKey = media.anime?.selectedEpisode
                            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: ep
                            model.setEpisode(targetEp, "startExo launch")

                            val index = if (url.contains("index=")) {
                                url.substringAfter("index=").toIntOrNull() ?: 0
                            } else 0

                            val hash = try {
                                if (url.startsWith("magnet:")) torrentManager.parseMagnetHash(url) else ""
                            } catch (_: Exception) { "" }

                            // Trigger immediate background pre-buffering on stream selection
                            torrentManager.prebufferTorrent(url, index, video.quality.toString())

                            if (hash.isNotBlank()) {
                                val link = torrentManager.getLink(hash, index)
                                video.file.url = link
                                targetEp.extractors?.find { it.server.name == targetEp.selectedExtractor }?.videos?.getOrNull(targetEp.selectedVideo)?.file?.url = link
                            }

                            if (act != null && !act.isFinishing && !act.isDestroyed) {
                                val intent = Intent(act, ExoplayerView::class.java).apply {
                                    ExoplayerView.media = media
                                    ExoplayerView.initialized = true
                                }
                                act.startActivity(intent)
                            }
                        } else {
                            val epKey = media.anime?.selectedEpisode
                            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: ep
                            model.setEpisode(targetEp, "startExo no launch")
                        }
                        return
                    } else {
                        try {
                            externalPlayerResult.launch(exportMagnetIntent(ep, video))
                        } catch (e: ActivityNotFoundException) {
                            val amnis = "com.amnis"
                            try {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=$amnis")
                                    )
                                )
                                dismissAllowingStateLoss()
                            } catch (e: ActivityNotFoundException) {
                                startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=$amnis")
                                    )
                                )
                            }
                        }
                    }
                    return
                }
            }
        }

        dismissAllowingStateLoss()
        if (launch!!) {
            stopAddingToList()
            val intent = Intent(activity, ExoplayerView::class.java)
            ExoplayerView.media = media
            ExoplayerView.initialized = true
            startActivity(intent)
        } else {
            val epKey = media.anime?.selectedEpisode
            val targetEp = media.anime?.episodes?.getEpisode(epKey) ?: episode
            if (targetEp != null) {
                model.setEpisode(targetEp, "startExo no launch")
            }
        }
    }

    private fun stopAddingToList() {
        episode?.extractorCallback = null
        episode?.also {
            it.extractors = it.extractors?.toMutableList()
        }
    }

    /**
     * Source Picker v3 Final list. Holds every loaded extractor in
     * [links] (same as before) and renders them as one flat list of
     * cards built by [PickerListBuilder]: stream links of one group and
     * audio share a card with quality buttons, torrents keep one card
     * each. All selection, download and long-press behaviour goes
     * through [RowActions], which is the previous per-server code.
     */
    private inner class ExtractorAdapter(private val onEpisodeDownloadHandler: EpisodeDownloadHandler? = null) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val links = mutableListOf<VideoExtractor>()
        private val actions = java.util.IdentityHashMap<VideoExtractor, RowActions>()
        private var list = PickerList(emptyList(), null, -1, false)
        private var visible: List<PickerEntry> = emptyList()
        private var filter: AudioKind? = null
        private var filterChosenByUser = false
        private val expanded = mutableSetOf<String>()
        private val viewTypeStream = 0
        private val viewTypeRelease = 1

        fun actionsFor(extractor: VideoExtractor): RowActions =
            actions.getOrPut(extractor) { RowActions(extractor, onEpisodeDownloadHandler) }

        override fun getItemCount(): Int = visible.size

        override fun getItemViewType(position: Int): Int =
            if (visible[position] is PickerEntry.StreamGroup) viewTypeStream else viewTypeRelease

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == viewTypeStream) {
                StreamHolder(ItemSourceStreamBinding.inflate(inflater, parent, false))
            } else {
                ReleaseHolder(ItemSourceReleaseBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val entry = visible[position]) {
                is PickerEntry.StreamGroup -> bindStream((holder as StreamHolder).binding, entry)
                is PickerEntry.Release -> bindRelease((holder as ReleaseHolder).binding, entry)
            }
        }

        fun add(videoExtractor: VideoExtractor) {
            if (videoExtractor.videos.isNotEmpty()) {
                val existingIndex = links.indexOfFirst { it.server.name == videoExtractor.server.name }
                if (existingIndex >= 0) {
                    links[existingIndex] = videoExtractor
                } else {
                    // FINAL-PORT C4 (upstream 6f77ea20): preferred servers
                    // list first; stable otherwise.
                    if (videoExtractor.isPreferred) links.add(0, videoExtractor) else links.add(videoExtractor)
                }
                rebuild()
            }
        }

        fun addAll(extractors: List<VideoExtractor>?) {
            if (extractors == null) return
            links.addAll(extractors.sortedByDescending { it.isPreferred })
            rebuild()
        }

        fun performClick(position: Int) {
            try {
                val extractor = links[position]
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
                    currentEp.selectedExtractor = extractor.server.name
                    currentEp.selectedVideo = 0
                }
                if (epKey != null) {
                    media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                    media?.anime?.episodes?.get(epKey)?.selectedVideo = 0
                }
                startExoplayer(media!!)
            } catch (e: Exception) {
                Injekt.get<CrashlyticsInterface>().logException(e)
            }
        }

        // ---- list building ------------------------------------------------

        @SuppressLint("NotifyDataSetChanged")
        fun rebuild() {
            val mediaId = media?.id
            val storedKey = mediaId?.let { SelectedKeyStore.load(it) }
            val storedFamily = mediaId?.let { SelectedFamilyStore.load(it) }
            val rows = mutableListOf<PickerRow>()
            links.forEachIndexed { ei, extractor ->
                val server = extractor.server
                val provider = StableCandidateBuilder.providerOf(server)
                val exact = StableCandidateBuilder.exactKey(provider, server)
                val family = StableCandidateBuilder.familyCandidate(server)
                val aniVideo = server.video
                extractor.videos.forEachIndexed { vi, video ->
                    val viewModel = summarize(extractor, video)
                    rows += PickerRow(
                        ref = RowRef(ei, vi),
                        provider = provider ?: sourceName(),
                        presentation = viewModel.presentation,
                        header = viewModel.header?.takeIf { it.isNotBlank() } ?: viewModel.displayLabel,
                        resolution = ResolutionLabel.resolve(aniVideo?.resolution, server.name)
                            ?: video.quality?.takeIf { it >= 144 },
                        transport = TransportLabel.of(video.file.url, video.format?.name),
                        audioKind = AudioKindDetector.detect(server.name),
                        preferred = extractor.isPreferred || aniVideo?.preferred == true,
                        rememberedExact = storedKey != null && exact == storedKey,
                        rememberedFamily = storedFamily != null && family != null &&
                            family.providerPkg == storedFamily.providerPkg &&
                            family.groupKey == storedFamily.groupKey,
                    )
                }
            }
            list = PickerListBuilder.build(rows, hasRememberedFamily = storedFamily != null)
            val showFilter = AudioKindFilter.shouldShow(list.entries)
            if (!showFilter) {
                filter = null
            } else if (!filterChosenByUser) {
                filter = list.bestIndex.takeIf { it >= 0 }?.let { list.entries[it].audioKind }
            }
            visible = AudioKindFilter.apply(list.entries, filter)
            notifyDataSetChanged()
            onListChanged(showFilter)
        }

        private fun sourceName(): String? =
            model.watchSources?.get(media?.selected?.sourceIndex ?: 0)?.name

        private fun summarize(extractor: VideoExtractor, video: Video): SourceRowViewModel =
            SourceRowSummarizer.build(
                displayLabel = extractor.server.name,
                qualityText = extractor.server.name,
                format = video.format,
                url = video.file.url,
                fileName = EditotsuInternalData.parse(extractor.server.video?.internalData)?.fileName,
            )

        private fun onListChanged(showFilter: Boolean) {
            if (_binding == null) return
            val releases = list.entries.count { it is PickerEntry.Release }
            val source = sourceName().orEmpty()
            binding.selectorHeaderMeta.text = when {
                list.entries.isEmpty() -> getString(R.string.picker_meta_loading, source)
                releases > 0 -> getString(R.string.picker_meta_releases, source, releases)
                else -> getString(R.string.picker_meta_servers, source, list.entries.size)
            }
            binding.selectorNotice.isVisible = makeDefault && list.rememberedFamilyMissing
            binding.selectorNotice.setText(R.string.picker_family_missing)
            bindFilters(showFilter)
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun bindFilters(show: Boolean) {
            val group = binding.selectorFilters
            group.setOnCheckedStateChangeListener(null)
            group.removeAllViews()
            group.isVisible = show
            if (!show) return
            val options = listOf<AudioKind?>(null) + AudioKindFilter.kinds(list.entries)
            options.forEach { kind ->
                val count = AudioKindFilter.count(list.entries, kind)
                val label = when (kind) {
                    null -> getString(R.string.picker_filter_all, count)
                    AudioKind.SUB -> getString(R.string.picker_filter_sub, count)
                    AudioKind.DUB -> getString(R.string.picker_filter_dub, count)
                    AudioKind.DUAL -> getString(R.string.picker_filter_dual, count)
                }
                val chip = com.google.android.material.chip.Chip(group.context).apply {
                    id = View.generateViewId()
                    text = label
                    tag = kind
                    isCheckable = true
                    isCheckedIconVisible = false
                    isChecked = kind == filter
                }
                group.addView(chip)
            }
            group.setOnCheckedStateChangeListener { g, ids ->
                val chip = ids.firstOrNull()?.let { g.findViewById<com.google.android.material.chip.Chip>(it) }
                    ?: return@setOnCheckedStateChangeListener
                filter = chip.tag as? AudioKind
                filterChosenByUser = true
                visible = AudioKindFilter.apply(list.entries, filter)
                notifyDataSetChanged()
            }
        }

        private fun isBest(entry: PickerEntry): Boolean {
            val best = list.best ?: return false
            return best.ref in entry.refs
        }

        private fun bestText(): String? = when (list.best?.reason) {
            BestReason.REMEMBERED -> getString(R.string.picker_best_remembered)
            BestReason.SAME_GROUP -> getString(R.string.picker_best_same_group)
            BestReason.EXTENSION_PREFERRED -> getString(R.string.picker_best_preferred)
            null -> null
        }

        private fun markBest(card: androidx.cardview.widget.CardView, why: android.widget.TextView, entry: PickerEntry) {
            val best = isBest(entry)
            card.foreground = if (best) {
                ContextCompat.getDrawable(card.context, R.drawable.source_card_best_outline)
            } else {
                val tv = android.util.TypedValue()
                card.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ContextCompat.getDrawable(card.context, tv.resourceId)
            }
            why.isVisible = best
            why.text = if (best) bestText() else null
        }

        private fun keyOf(entry: PickerEntry): String =
            entry.refs.firstOrNull()?.let { ref ->
                "${links.getOrNull(ref.extractor)?.server?.name}#${ref.video}"
            }.orEmpty()

        private fun bindExpand(button: View, details: android.widget.TextView, entry: PickerEntry, text: () -> String) {
            val key = keyOf(entry)
            val open = key in expanded
            details.isVisible = open
            if (open) details.text = text()
            button.setOnClickListener {
                if (key in expanded) expanded.remove(key) else expanded.add(key)
                val nowOpen = key in expanded
                details.isVisible = nowOpen
                if (nowOpen) details.text = text()
            }
        }

        // ---- stream card ---------------------------------------------------

        private fun bindStream(b: ItemSourceStreamBinding, entry: PickerEntry.StreamGroup) {
            b.streamName.text = entry.name
            b.streamAudio.isVisible = entry.audioTag != null
            b.streamAudio.text = entry.audioTag
            markBest(b.sourceCard, b.streamWhy, entry)

            val top = entry.primary.firstOrNull()
            val topExtractor = top?.let { links.getOrNull(it.ref.extractor) }
            val subtitles = topExtractor?.subtitles.orEmpty()
            b.streamSub.isVisible = subtitles.isNotEmpty()
            if (topExtractor != null) actionsFor(topExtractor).setupRowSubtitleButton(b.streamSub, subtitles)

            b.streamQualities.removeAllViews()
            entry.primary.forEach { variant ->
                b.streamQualities.addView(
                    qualityButton(b.streamQualities, entry, variant, filled = list.isRememberedQuality(variant.ref)),
                )
            }
            b.streamAlternates.removeAllViews()
            b.streamAlternates.isVisible = entry.alternates.isNotEmpty()
            if (entry.alternates.isNotEmpty()) {
                val label = android.widget.TextView(b.root.context).apply {
                    text = entry.alternateLabel?.let { getString(R.string.picker_also_as, it) }
                        ?: getString(R.string.picker_also)
                    alpha = 0.75f
                    textSize = 13f
                    typeface = ResourcesCompat.getFont(context, R.font.poppins)
                }
                b.streamAlternates.addView(label, flexParams(b.root.context, endDp = 8, bottomDp = 0))
                entry.alternates.forEach { variant ->
                    b.streamAlternates.addView(qualityButton(
                        b.streamAlternates, entry, variant,
                        filled = list.isRememberedQuality(variant.ref), compact = true,
                    ))
                }
            }
            bindExpand(b.streamExpand, b.streamDetails, entry) {
                (entry.primary + entry.alternates).joinToString("\n") { v ->
                    val raw = links.getOrNull(v.ref.extractor)?.server?.name?.lineSequence()?.firstOrNull().orEmpty()
                    listOfNotNull(v.label, v.transport).joinToString(" · ") + ": " + raw
                }
            }
        }

        private fun flexParams(context: android.content.Context, endDp: Int, bottomDp: Int) =
            com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                val d = context.resources.displayMetrics.density
                marginEnd = (endDp * d).toInt()
                bottomMargin = (bottomDp * d).toInt()
            }

        private fun qualityButton(
            parent: ViewGroup,
            entry: PickerEntry.StreamGroup,
            variant: QualityVariant,
            filled: Boolean,
            compact: Boolean = false,
        ): View {
            val context = parent.context
            val d = context.resources.displayMetrics.density
            val tv = android.widget.TextView(context).apply {
                text = variant.label
                textSize = if (compact) 13f else 14f
                typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
                gravity = android.view.Gravity.CENTER
                minHeight = ((if (compact) 36 else 44) * d).toInt()
                minWidth = ((if (compact) 52 else 64) * d).toInt()
                val padH = ((if (compact) 10 else 14) * d).toInt()
                setPadding(padH, 0, padH, 0)
                setBackgroundResource(if (filled) R.drawable.quality_button_filled else R.drawable.quality_button_outlined)
                setTextColor(
                    context.getThemeColor(
                        if (filled) com.google.android.material.R.attr.colorOnPrimaryContainer
                        else com.google.android.material.R.attr.colorOnSurface,
                    ),
                )
                contentDescription = getString(R.string.picker_play_quality, entry.name, variant.label)
                isClickable = true
                isFocusable = true
            }
            val extractor = links.getOrNull(variant.ref.extractor)
            if (extractor != null) {
                val rowActions = actionsFor(extractor)
                tv.setSafeOnClickListener {
                    rowActions.onRowClicked(variant.ref.video) {
                        rowActions.startDownload(variant.ref.video, extractor.subtitles)
                    }
                }
                tv.setOnLongClickListener { rowActions.onRowLongClicked(variant.ref.video) }
            }
            tv.layoutParams = flexParams(context, endDp = 8, bottomDp = if (compact) 0 else 8)
            return tv
        }

        // ---- release card --------------------------------------------------

        private fun bindRelease(b: ItemSourceReleaseBinding, entry: PickerEntry.Release) {
            val ref = entry.row.ref
            val extractor = links.getOrNull(ref.extractor) ?: return
            val video = extractor.videos.getOrNull(ref.video) ?: return
            val viewModel = summarize(extractor, video)
            val rowActions = actionsFor(extractor)

            val header = viewModel.header?.takeIf { it.isNotBlank() } ?: viewModel.displayLabel
            b.sourceHeader.text = header

            val seeders = SeederHealth.parse(viewModel.meta.firstOrNull { it.icon == SourceMetaIcon.SEEDERS }?.text)
            b.sourceSeeders.isVisible = seeders != null
            if (seeders != null) {
                b.sourceSeeders.text = if (SeederHealth.isLow(seeders)) {
                    getString(R.string.picker_seeds_low, seeders)
                } else {
                    getString(R.string.picker_seeds, seeders)
                }
                b.sourceSeeders.setTextColor(
                    b.root.context.getThemeColor(
                        if (SeederHealth.isLow(seeders)) com.google.android.material.R.attr.colorError
                        else com.google.android.material.R.attr.colorPrimary,
                    ),
                )
            }

            val title = viewModel.cleanSubtitle?.takeIf { it.isNotBlank() } ?: viewModel.subtitle
            b.sourceTitle.isVisible = !title.isNullOrBlank() && title != header
            b.sourceTitle.text = title

            markBest(b.sourceCard, b.sourceWhy, entry)

            b.sourceTags.removeAllViews()
            viewModel.pills.forEach { pill -> b.sourceTags.addView(makePillTextView(b.root.context, pill)) }
            b.sourceTags.overflowView = android.widget.TextView(b.root.context).apply {
                setBackgroundResource(R.drawable.pill_more)
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val padH = (5 * resources.displayMetrics.density).toInt()
                val padV = (2 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setSingleLine(true)
                alpha = 0.8f
                setOnClickListener { b.sourceExpand.performClick() }
            }

            val size = viewModel.meta.firstOrNull { it.icon == SourceMetaIcon.SIZE }?.text
            b.sourceSize.isVisible = !size.isNullOrBlank()
            b.sourceSize.text = size

            val subtitles = extractor.subtitles
            b.sourceSub.isVisible = subtitles.isNotEmpty()
            rowActions.setupRowSubtitleButton(b.sourceSub, subtitles)
            b.sourceDownload.isVisible = isDownloadMenu == true
            rowActions.setupRowDownloadButton(b.sourceDownload, ref.video, subtitles)

            bindExpand(b.sourceExpand, b.sourceDetails, entry) {
                val qualityText = extractor.server.name
                val fields = SourceRowClassifier.parseRelease(
                    qualityText,
                    EditotsuInternalData.parse(extractor.server.video?.internalData)?.fileName,
                )
                val tags = viewModel.pills.joinToString(" · ") { it.text }
                (CompactDetails.lines(
                    group = fields.releaseGroup,
                    tracker = viewModel.tracker ?: fields.indexer ?: fields.tracker,
                    fileName = CompactDetails.fileLine(qualityText),
                    rawName = SourceRowClassifier.extractReleaseName(qualityText),
                    transport = entry.row.transport,
                ) + listOfNotNull(tags.takeIf { it.isNotBlank() }?.let { "Tags" to it }))
                    .joinToString("\n") { (label, value) -> "$label: $value" }
            }

            b.sourceCard.setSafeOnClickListener {
                rowActions.onRowClicked(ref.video) { b.sourceDownload.performClick() }
            }
            b.sourceCard.setOnLongClickListener { rowActions.onRowLongClicked(ref.video) }
        }

        // ---- tags (unchanged look from v3) ---------------------------------

        private fun makePillTextView(
            context: android.content.Context,
            pill: SourceRowPill,
        ): android.widget.TextView {
            val tv = android.widget.TextView(context)
            tv.text = pill.text
            tv.setTextColor(pillTextColor(pill.kind))
            tv.setBackgroundResource(pillBackground(pill.kind))
            val padH = (4 * context.resources.displayMetrics.density).toInt()
            val padV = (2 * context.resources.displayMetrics.density).toInt()
            tv.setPadding(padH, padV, padH, padV)
            tv.textSize = 11f
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.maxLines = 1
            tv.setSingleLine(true)
            return tv
        }

        private fun pillBackground(kind: SourcePillKind): Int = when (kind) {
            SourcePillKind.QUALITY -> R.drawable.pill_quality
            SourcePillKind.CODEC -> R.drawable.pill_codec
            SourcePillKind.BIT_DEPTH -> R.drawable.pill_bit_depth
            SourcePillKind.AUDIO_FMT -> R.drawable.pill_audio
            SourcePillKind.SUB_LANG -> R.drawable.pill_sub
            SourcePillKind.AUDIO_MODE -> R.drawable.pill_audio_mode
            SourcePillKind.HDR -> R.drawable.pill_hdr
            SourcePillKind.HDR10 -> R.drawable.pill_hdr10
            SourcePillKind.HDR10_PLUS -> R.drawable.pill_hdr10_plus
            SourcePillKind.DOLBY_VISION -> R.drawable.pill_dolby_vision
            SourcePillKind.BATCH -> R.drawable.pill_batch
            SourcePillKind.CENSOR -> R.drawable.pill_audio_mode
            SourcePillKind.REVISION -> R.drawable.pill_batch
            SourcePillKind.SERVICE -> R.drawable.pill_bit_depth
        }

        private fun pillTextColor(kind: SourcePillKind): Int {
            val context = ani.dantotsu.currContext() ?: return 0
            val attr = when (kind) {
                SourcePillKind.QUALITY -> com.google.android.material.R.attr.colorOnPrimaryContainer
                SourcePillKind.CODEC -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.BIT_DEPTH -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.AUDIO_FMT -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.SUB_LANG -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.AUDIO_MODE -> com.google.android.material.R.attr.colorOnPrimaryContainer
                SourcePillKind.HDR -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.HDR10 -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.HDR10_PLUS -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.DOLBY_VISION -> com.google.android.material.R.attr.colorOnTertiaryContainer
                SourcePillKind.BATCH -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.CENSOR -> com.google.android.material.R.attr.colorOnPrimaryContainer
                SourcePillKind.REVISION -> com.google.android.material.R.attr.colorOnSecondaryContainer
                SourcePillKind.SERVICE -> com.google.android.material.R.attr.colorOnSecondaryContainer
            }
            val typedValue = android.util.TypedValue()
            val resolved = context.theme.resolveAttribute(attr, typedValue, true)
            return if (resolved) typedValue.data else 0
        }

        private inner class StreamHolder(val binding: ItemSourceStreamBinding) :
            RecyclerView.ViewHolder(binding.root)

        private inner class ReleaseHolder(val binding: ItemSourceReleaseBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    /**
     * Per-extractor actions: selection transaction, subtitle download,
     * download flow and long-press. This is the previous per-server
     * adapter's code, kept as it was; only the RecyclerView parts left.
     */
    private inner class RowActions(
        private val extractor: VideoExtractor,
        private val onEpisodeDownloadHandler: EpisodeDownloadHandler?,
    ) {
        fun setupRowSubtitleButton(
            button: android.widget.ImageButton,
            subtitles: List<Subtitle>,
        ) {
            button.setOnClickListener {
                if (subtitles.isNotEmpty()) {
            val subtitleNames = subtitles.map { it.language }
            var subtitleToDownload: Subtitle? = null
            val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
            val epNumber = currentEp?.number ?: media?.anime?.selectedEpisode ?: "1"
            (activity ?: currActivity())?.customAlertDialog()?.apply {
                setTitle(R.string.download_subtitle)
                singleChoiceItems(subtitleNames.toTypedArray(),  dismissOnSelect = false) { which ->
                    subtitleToDownload = subtitles[which]
                }
                setPosButton(R.string.download) {
                    scope.launch(Dispatchers.IO) {
                        if (subtitleToDownload != null) {
                            SubtitleDownloader.downloadSubtitle(
                                context ?: currContext() ?: return@launch,
                                subtitleToDownload.file.url,
                                DownloadedType(
                                    media!!.mainName(),
                                    epNumber,
                                    MediaType.ANIME
                                )
                            )
                        }
                    }
                }
                setNegButton(R.string.cancel) {}
            }?.show()
                } else {
            snackString(R.string.no_subtitles_available)
                }
            }
        }

        fun setupRowDownloadButton(
            button: android.widget.ImageButton,
            position: Int,
            subtitles: List<Subtitle>,
        ) {
            button.setSafeOnClickListener { startDownload(position, subtitles) }
        }

        /** Download flow for one video; the old button body, callable from a quality button. */
        fun startDownload(position: Int, subtitles: List<Subtitle>) {
            run startDownload@{
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
            currentEp.selectedExtractor = extractor.server.name
            currentEp.selectedVideo = position
                }
                if (epKey != null) {
            media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
            media?.anime?.episodes?.get(epKey)?.selectedVideo = position
                }
                if ((PrefManager.getVal(PrefName.DownloadManager) as Int) != 0) {
            val act = activity ?: currActivity()
            if (act != null && currentEp != null) {
                download(
                    act,
                    currentEp,
                    media!!.userPreferredName
                )
            }
                }
                else {
            val ep = currentEp ?: return@startDownload
            val selectedVideo =
                if (extractor.videos.size > ep.selectedVideo) extractor.videos[ep.selectedVideo] else extractor.videos.getOrNull(0)
            val downloadAddonManager: DownloadAddonManager = Injekt.get()
            if (!downloadAddonManager.isAvailable()) {
                val context = context ?: currContext()
                context?.customAlertDialog()?.apply {
                    setTitle(R.string.download_addon_not_installed)
                    setMessage(R.string.would_you_like_to_install)
                    setPosButton(R.string.yes) {
                        ContextCompat.startActivity(
                            context,
                            Intent(context, SettingsAddonActivity::class.java),
                            null
                        )
                    }
                    setNegButton(R.string.no) {
                        return@setNegButton
                    }
                    show()
                }
                dismissAllowingStateLoss()
                return@startDownload
            }
            selectedVideo?.file?.url?.let { url ->
                if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    val torrentManager = Injekt.get<TorrentServerManager>()
                    if (!torrentManager.isAvailable()) {
                        toast(R.string.torrent_addon_not_available)
                        return@startDownload
                    }
                }
            }

            val subtitleNames = subtitles.map { it.language }
            var selectedSubtitles: MutableList<String> = mutableListOf()
            var selectedAudioTracks: MutableList<String> = mutableListOf()

            val currContext = currContext() ?: requireContext()

            fun go(){
                onEpisodeDownloadHandler?.onFinishingUserSelection(extractor.server.name, selectedSubtitles, selectedAudioTracks)
            }

            fun checkAudioTracks() {
                val audioTracks = extractor.audioTracks.map { it.lang }
                if (audioTracks.isNotEmpty()) {
                    val audioNamesArray = audioTracks.toTypedArray()
                    val checkedItems = BooleanArray(audioNamesArray.size) { false }

                    currContext.customAlertDialog().apply { // ToTest
                        setTitle(R.string.download_audio_tracks)
                        multiChoiceItems(audioNamesArray, checkedItems) {
                            it.forEachIndexed { index, isChecked ->
                                val audioName = extractor.audioTracks[index].lang
                                if (isChecked) {
                                    selectedAudioTracks.add(audioName)
                                } else {
                                    selectedAudioTracks.remove(audioName)
                                }
                            }
                        }
                        setPosButton(R.string.download) {
                            go()
                        }
                        setNegButton(R.string.skip) {
                            selectedAudioTracks = mutableListOf()
                            go()
                        }
                        setNeutralButton(R.string.cancel) {
                            selectedAudioTracks = mutableListOf()
                        }
                        show()
                    }
                } else {
                    go()
                }
            }
            if (subtitles.isNotEmpty()) { // ToTest
                val subtitleNamesArray = subtitleNames.toTypedArray()
                val checkedItems = BooleanArray(subtitleNamesArray.size) { index ->
                    val name = subtitleNamesArray[index]
                    val isDefaultMatch = name.contains("English", true) || name.contains("en", true) || (subtitles.size == 1)
                    if (isDefaultMatch) {
                        selectedSubtitles.add(subtitles[index].language)
                    }
                    isDefaultMatch
                }

                currContext.customAlertDialog().apply {
                    setTitle(R.string.download_subtitle)
                    multiChoiceItems(subtitleNamesArray, checkedItems) {
                        it.forEachIndexed { index, isChecked ->
                            val subtitleName = subtitles[index].language
                            if (isChecked) {
                                if (!selectedSubtitles.contains(subtitleName)) selectedSubtitles.add(subtitleName)
                            } else {
                                selectedSubtitles.remove(subtitleName)
                            }
                        }
                    }
                    setPosButton(R.string.download) {
                        checkAudioTracks()
                    }
                    setNegButton(R.string.skip) {
                        selectedSubtitles = mutableListOf()
                        checkAudioTracks()
                    }
                    setNeutralButton(R.string.cancel) {
                        selectedSubtitles = mutableListOf()
                    }
                    show()
                }
            } else {
                checkAudioTracks()
            }
                }
            }
        }

        fun onRowClicked(
            position: Int,
            downloadClick: () -> Unit,
        ) {
            if (isDownloadMenu == true) {
                downloadClick()
                return
            }
            tryWith(true) {
                val currentEp = media?.anime?.episodes?.getEpisode(media?.anime?.selectedEpisode) ?: episode
                val epKey = media?.anime?.episodes?.getEpisodeKey(media?.anime?.selectedEpisode) ?: media?.anime?.selectedEpisode
                if (currentEp != null) {
                    currentEp.selectedExtractor = extractor.server.name
                    currentEp.selectedVideo = position
                }
                if (epKey != null) {
                    media?.anime?.episodes?.get(epKey)?.selectedExtractor = extractor.server.name
                    media?.anime?.episodes?.get(epKey)?.selectedVideo = position
                }
                if (makeDefault) {
                    media!!.selected!!.server = extractor.server.name
                    media!!.selected!!.video = position
                    model.saveSelected(media!!.id, media!!.selected!!)
                    val provider = StableCandidateBuilder.providerOf(extractor.server)
                    val exact = StableCandidateBuilder.exactKey(provider, extractor.server)
                    val candidate = StableCandidateBuilder.familyCandidate(extractor.server)
                    // Exact slot is CURRENT-candidate identity:
                    // OFF leaves it alone, ON writes the
                    // current key, ON with an unkeyable
                    // candidate clears a stale previous key.
                    when (RememberedSelectionPolicy.exactSlotAction(
                        makeDefault, exact,
                    )) {
                        RememberedSelectionPolicy.ExactSlotAction.WRITE ->
                            SelectedKeyStore.save(media!!.id, exact)
                        RememberedSelectionPolicy.ExactSlotAction.CLEAR ->
                            SelectedKeyStore.save(media!!.id, null)
                        RememberedSelectionPolicy.ExactSlotAction.NO_CHANGE -> Unit
                    }
                    // Family slot is independent of the exact
                    // slot: REPLACE on a confident new
                    // family, PRESERVE (leave untouched) when
                    // the pick is not confidently
                    // family-shaped, so parser uncertainty
                    // cannot silently delete the user's
                    // previous family choice.
                    when (RememberedSelectionPolicy.familySlotAction(
                        makeDefault, candidate,
                    )) {
                        RememberedSelectionPolicy.FamilySlotAction.REPLACE -> {
                            val c = candidate!!
                            SelectedFamilyStore.save(media!!.id, FamilyPayload(
                                providerPkg = c.providerPkg,
                                groupKey = c.groupKey,
                                soft = c.soft,
                            ))
                        }
                        RememberedSelectionPolicy.FamilySlotAction.PRESERVE,
                        RememberedSelectionPolicy.FamilySlotAction.NO_CHANGE,
                        -> Unit
                    }
                }
                Log.d("AnimeDownloader", "Should start the player")
                startExoplayer(media!!)
            }
        }

        /**
         * Row long-click (copy link / open externally) shared by the
         * full release card and the inline stream card. Moved verbatim
         * from [UrlViewHolder.init]; behavior is identical.
         */
        fun onRowLongClicked(position: Int): Boolean {
            val video = extractor.videos[position]
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(video.file.url), "video/*")
            }
            copyToClipboard(video.file.url, true)
            dismissAllowingStateLoss()
            startActivity(Intent.createChooser(intent, "Open Video in :"))
            return true
        }
    }

    companion object {
        fun newInstance(
            server: String? = null,
            la: Boolean = true,
            prev: String? = null,
            isDownload: Boolean,
            episodes: ArrayList<String>
        ): SelectorDialogFragment =
            SelectorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("server", server)
                    putBoolean("launch", la)
                    putString("prev", prev)
                    putBoolean("isDownload", isDownload)
                    putStringArrayList("episodes", episodes)
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {}

    override fun onDismiss(dialog: DialogInterface) {
        if (launch == false) {
            activity?.hideSystemBars()
            model.epChanged.postValue(true)
            if (prevEpisode != null) {
                media?.anime?.selectedEpisode = prevEpisode
                model.setEpisode(media?.anime?.episodes?.get(prevEpisode) ?: return, "prevEp")
            }
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
